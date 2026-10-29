package dev.anilbeesetti.nextplayer.core.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * Finds DLNA media renderers with an SSDP search and resolves the AVTransport control URL from
 * each device description. A renderer without AVTransport (printers, gateways, ...) is ignored.
 */
class SsdpDiscovery(private val context: Context) {

    suspend fun discover(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): List<CastDevice> =
        withContext(Dispatchers.IO) {
            val multicastLock = acquireMulticastLock()
            try {
                searchLocations(timeoutMs = timeoutMs)
                    .mapNotNull { location ->
                        runCatching { resolveDevice(location = location) }.getOrNull()
                    }
                    .distinctBy { it.udn }
            } finally {
                runCatching { multicastLock?.release() }
            }
        }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun searchLocations(timeoutMs: Long): Set<String> {
        val locations = linkedSetOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = RESPONSE_TIMEOUT_MS
            val address = InetAddress.getByName(SSDP_ADDRESS)
            repeat(SEARCH_ATTEMPTS) {
                val packet = DatagramPacket(
                    SEARCH_REQUEST.toByteArray(),
                    SEARCH_REQUEST.length,
                    address,
                    SSDP_PORT,
                )
                socket.send(packet)
                Thread.sleep(SEARCH_INTERVAL_MS)
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(RESPONSE_BUFFER_SIZE)
            while (System.currentTimeMillis() < deadline) {
                val response = DatagramPacket(
                    buffer,
                    buffer.size,
                )
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                parseLocation(response = response).let { location ->
                    if (location != null) locations.add(location)
                }
            }
        }
        return locations
    }

    private fun parseLocation(response: DatagramPacket): String? {
        val text = String(
            response.data,
            0,
            response.length,
            Charsets.UTF_8,
        )
        return text.lineSequence()
            .firstOrNull {
                it.startsWith(
                    prefix = "LOCATION:",
                    ignoreCase = true,
                )
            }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf {
                it.startsWith(
                    prefix = "http",
                    ignoreCase = true,
                )
            }
    }

    private fun resolveDevice(location: String): CastDevice? {
        val connection = URL(location).openConnection() as? HttpURLConnection ?: return null
        return try {
            connection.connectTimeout = CONNECTION_TIMEOUT_MS
            connection.readTimeout = CONNECTION_TIMEOUT_MS
            connection.setRequestProperty(
                "Accept",
                "text/xml",
            )
            if (connection.responseCode !in 200..299) return null
            val description = connection.inputStream.use { it.readBytes() }
            parseDescription(
                location = location,
                description = description,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun parseDescription(location: String, description: ByteArray): CastDevice? {
        val parser = Xml.newPullParser()
        parser.setFeature(
            XmlPullParser.FEATURE_PROCESS_NAMESPACES,
            false,
        )
        parser.setInput(
            ByteArrayInputStream(description),
            null,
        )
        val base = URL(location)
        var name: String? = null
        var udn: String? = null
        var controlUrl: String? = null
        var serviceType: String? = null
        var serviceControlUrl: String? = null
        var inService = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.tagName()) {
                    "friendlyName" -> if (name == null) name = parser.nextText().trim()

                    "UDN" -> if (udn == null) udn = parser.nextText().trim()

                    "service" -> {
                        inService = true
                        serviceType = null
                        serviceControlUrl = null
                    }

                    "serviceType" -> if (inService) serviceType = parser.nextText().trim()

                    "controlURL" -> if (inService) serviceControlUrl = parser.nextText().trim()
                }

                XmlPullParser.END_TAG -> if (parser.tagName() == "service") {
                    if (controlUrl == null && serviceType?.contains("AVTransport") == true) {
                        controlUrl = serviceControlUrl?.let {
                            resolveUrl(
                                base = base,
                                path = it,
                            )
                        }
                    }
                    inService = false
                }
            }
            event = parser.next()
        }

        val deviceUdn = udn?.substringAfter("uuid:")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val transportUrl = controlUrl ?: return null
        return CastDevice(
            udn = deviceUdn,
            name = name?.takeIf { it.isNotEmpty() } ?: deviceUdn,
            location = location,
            controlUrl = transportUrl,
        )
    }

    private fun XmlPullParser.tagName(): String = name.substringAfter(':')

    private fun resolveUrl(base: URL, path: String): String? = runCatching {
        URL(
            base,
            path,
        ).toString()
    }.getOrNull()

    private companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val SEARCH_ATTEMPTS = 3
        const val SEARCH_INTERVAL_MS = 300L
        const val RESPONSE_TIMEOUT_MS = 500
        const val RESPONSE_BUFFER_SIZE = 2_048
        const val DISCOVERY_TIMEOUT_MS = 4_000L
        const val CONNECTION_TIMEOUT_MS = 4_000
        const val MULTICAST_LOCK_TAG = "nextplayer-dlna"
        val SEARCH_REQUEST = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
            append("\r\n")
        }
    }
}
