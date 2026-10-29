package dev.anilbeesetti.nextplayer.core.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Streams one media item over HTTP on the local network so a renderer can pull it.
 * Range requests are answered with `206`, which most renderers need to start playback.
 */
class LocalMediaServer(private val context: Context) {

    private val lock = Any()

    @Volatile
    private var served: ServedMedia? = null
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var cachedFile: File? = null

    fun start(): Int = synchronized(lock) {
        val existing = serverSocket
        if (existing != null) return existing.localPort
        val socket = ServerSocket(0)
        val pool = Executors.newCachedThreadPool()
        serverSocket = socket
        executor = pool
        pool.execute {
            acceptLoop(
                socket = socket,
                pool = pool,
            )
        }
        socket.localPort
    }

    /**
     * Registers the item that is served from now on and returns the request path. Items whose
     * size is unknown are copied into the cache first, because renderers require a length.
     */
    suspend fun serve(uri: Uri, mimeType: String): String = withContext(Dispatchers.IO) {
        releaseCache()
        val media = when (uri.scheme) {
            "file" -> {
                val file = File(uri.path ?: throw IOException("Missing path for $uri"))
                if (!file.isFile) throw IOException("Missing file $uri")
                ServedMedia(
                    path = newPath(),
                    length = file.length(),
                    mimeType = mimeType,
                    source = LocalFile(file = file),
                )
            }

            else -> {
                val length = contentLength(uri = uri)
                if (length > 0) {
                    ServedMedia(
                        path = newPath(),
                        length = length,
                        mimeType = mimeType,
                        source = ContentUri(uri = uri),
                    )
                } else {
                    val cached = materialize(uri = uri)
                    ServedMedia(
                        path = newPath(),
                        length = cached.length(),
                        mimeType = mimeType,
                        source = LocalFile(file = cached),
                    )
                }
            }
        }
        served = media
        media.path
    }

    fun stop() {
        var socket: ServerSocket? = null
        var pool: ExecutorService? = null
        synchronized(lock) {
            served = null
            socket = serverSocket
            pool = executor
            serverSocket = null
            executor = null
        }
        runCatching { socket?.close() }
        pool?.shutdownNow()
        releaseCache()
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            runCatching {
                pool.execute { handleRequest(client = client) }
            }.onFailure {
                runCatching { client.close() }
            }
        }
    }

    private fun handleRequest(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                val reader = BufferedReader(
                    InputStreamReader(
                        socket.getInputStream(),
                        Charsets.US_ASCII,
                    ),
                )
                val requestLine = reader.readLine() ?: return
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val isRangeHeader = line.startsWith(
                        prefix = "Range:",
                        ignoreCase = true,
                    )
                    if (isRangeHeader) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                val method = requestLine.substringBefore(' ')
                val path = requestLine.substringAfter(
                    delimiter = ' ',
                    missingDelimiterValue = "",
                ).substringBefore(' ')
                val media = served
                if (media == null || path != media.path) {
                    writeHeaders(
                        output = socket.getOutputStream(),
                        status = "404 Not Found",
                        headers = mapOf("Content-Length" to "0"),
                    )
                    return
                }

                val range = parseRange(
                    header = rangeHeader,
                    total = media.length,
                )
                val start = range?.start ?: 0L
                val end = range?.end ?: (media.length - 1)
                val output = socket.getOutputStream()
                writeHeaders(
                    output = output,
                    status = if (range != null) "206 Partial Content" else "200 OK",
                    headers = buildMap {
                        put(
                            "Content-Type",
                            media.mimeType,
                        )
                        put(
                            "Content-Length",
                            (end - start + 1).toString(),
                        )
                        put(
                            "Accept-Ranges",
                            "bytes",
                        )
                        if (range != null) {
                            put(
                                "Content-Range",
                                "bytes $start-$end/${media.length}",
                            )
                        }
                    },
                )
                if (method != "HEAD") {
                    media.openStream(context = context).use { input ->
                        skipFully(
                            input = input,
                            count = start,
                        )
                        copy(
                            source = input,
                            target = output,
                            length = end - start + 1,
                        )
                    }
                }
                output.flush()
            }
        } catch (_: IOException) {
            // The renderer may drop the connection at any point; nothing to recover.
        }
    }

    private fun writeHeaders(
        output: OutputStream,
        status: String,
        headers: Map<String, String>,
    ) {
        val text = buildString {
            append("HTTP/1.1 $status\r\n")
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(text.toByteArray())
    }

    private fun parseRange(header: String?, total: Long): ByteRange? {
        if (header == null || total <= 0) return null
        val spec = header.substringAfter(
            delimiter = "bytes=",
            missingDelimiterValue = "",
        ).substringBefore(',')
        val parts = spec.split('-')
        if (parts.size != 2) return null
        val startText = parts[0].trim()
        val endText = parts[1].trim()
        return when {
            startText.isEmpty() -> {
                val suffixLength = endText.toLongOrNull() ?: return null
                ByteRange(
                    start = (total - suffixLength).coerceAtLeast(0),
                    end = total - 1,
                )
            }

            else -> {
                val start = startText.toLongOrNull() ?: return null
                val end = endText.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                if (start > end) {
                    null
                } else {
                    ByteRange(
                        start = start,
                        end = end,
                    )
                }
            }
        }
    }

    private fun contentLength(uri: Uri): Long {
        val assetLength = runCatching {
            context.contentResolver.openAssetFileDescriptor(
                uri,
                "r",
            )?.use { it.length }
        }.getOrNull()
        if (assetLength != null && assetLength > 0) return assetLength
        val queryLength = runCatching {
            context.contentResolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                        cursor.getLong(index)
                    } else {
                        0L
                    }
                }
        }.getOrNull()
        return queryLength?.takeIf { it > 0 } ?: 0L
    }

    private fun materialize(uri: Uri): File {
        val file = File(
            context.cacheDir,
            "cast-${UUID.randomUUID()}",
        )
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(
                    out = output,
                    bufferSize = BUFFER_SIZE,
                )
            }
        } ?: throw IOException("Cannot open $uri")
        cachedFile = file
        return file
    }

    private fun releaseCache() {
        cachedFile?.delete()
        cachedFile = null
    }

    private fun newPath(): String = "/media/${UUID.randomUUID()}"

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun copy(source: InputStream, target: OutputStream, length: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val read = source.read(
                buffer,
                0,
                minOf(
                    buffer.size.toLong(),
                    remaining,
                ).toInt(),
            )
            if (read <= 0) break
            target.write(
                buffer,
                0,
                read,
            )
            remaining -= read
        }
    }

    private data class ByteRange(val start: Long, val end: Long)

    private class ServedMedia(
        val path: String,
        val length: Long,
        val mimeType: String,
        val source: MediaSource,
    ) {
        fun openStream(context: Context): InputStream = source.open(context = context)
    }

    private sealed interface MediaSource {
        fun open(context: Context): InputStream
    }

    private class ContentUri(private val uri: Uri) : MediaSource {
        override fun open(context: Context): InputStream =
            context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
    }

    private class LocalFile(private val file: File) : MediaSource {
        override fun open(context: Context): InputStream = FileInputStream(file)
    }

    companion object {
        private const val READ_TIMEOUT_MS = 5_000
        private const val BUFFER_SIZE = 16 * 1024

        /** IPv4 address the renderer can reach, preferring the active network. */
        fun localAddress(context: Context): String? {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeAddress = connectivityManager?.let { manager ->
                manager.activeNetwork?.let { network ->
                    manager.getLinkProperties(network)
                        ?.linkAddresses
                        ?.map { it.address }
                        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                }
            }
            if (activeAddress != null) return activeAddress.hostAddress
            return runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .flatMap { Collections.list(it.inetAddresses) }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
            }.getOrNull()
        }
    }
}
