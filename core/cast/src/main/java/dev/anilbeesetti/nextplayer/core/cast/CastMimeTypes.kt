package dev.anilbeesetti.nextplayer.core.cast

import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap

/** Best effort MIME type for the `res@protocolInfo` and HTTP `Content-Type` of a cast stream. */
object CastMimeTypes {

    fun resolve(
        contentResolver: ContentResolver,
        uri: Uri,
        hint: String? = null,
        title: String? = null,
    ): String {
        hint?.takeIf { it.isNotBlank() && it != "*/*" }?.let { return it }
        runCatching { contentResolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val extension = extensionOf(value = uri.lastPathSegment).ifEmpty {
            extensionOf(value = title)
        }
        if (extension.isNotEmpty()) {
            EXTENSION_MIME_TYPES[extension]?.let { return it }
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
        return DEFAULT_MIME_TYPE
    }

    private fun extensionOf(value: String?): String = value
        ?.substringAfterLast(
            delimiter = '.',
            missingDelimiterValue = "",
        )
        ?.lowercase()
        ?.takeIf { it.length in 2..5 }
        .orEmpty()

    private const val DEFAULT_MIME_TYPE = "video/mp4"

    private val EXTENSION_MIME_TYPES = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "ts" to "video/mp2t",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "flv" to "video/x-flv",
        "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
    )
}
