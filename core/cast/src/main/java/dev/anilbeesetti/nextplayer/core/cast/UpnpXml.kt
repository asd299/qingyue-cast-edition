package dev.anilbeesetti.nextplayer.core.cast

import java.util.Locale

internal fun escapeXml(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}

/** AVTransport expects `HH:MM:SS` for relative seeks. */
internal fun formatTransportTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        hours,
        minutes,
        seconds,
    )
}

internal fun parseTransportTime(value: String?): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty() || text.uppercase(Locale.US).startsWith("NOT_IMPLEMENTED")) return null
    val parts = text.split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toDoubleOrNull() ?: return null
    return (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
}

/** DIDL-Lite item metadata describing the stream the renderer has to pull. */
internal fun didlMetadata(title: String, url: String, mimeType: String): String {
    val mediaClass = if (mimeType.startsWith("audio")) "audioItem" else "videoItem"
    return buildString {
        append("<DIDL-Lite")
        append(" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
        append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
        append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
        append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
        append("<dc:title>${escapeXml(title)}</dc:title>")
        append("<upnp:class>object.item.$mediaClass</upnp:class>")
        append("<res protocolInfo=\"http-get:*:$mimeType:*\">${escapeXml(url)}</res>")
        append("</item>")
        append("</DIDL-Lite>")
    }
}
