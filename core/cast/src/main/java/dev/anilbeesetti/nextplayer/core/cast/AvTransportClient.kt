package dev.anilbeesetti.nextplayer.core.cast

import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

internal data class TransportInfo(
    val positionMs: Long,
    val durationMs: Long,
    val state: String?,
)

/** Minimal AVTransport:1 client for one renderer. */
internal class AvTransportClient(private val device: CastDevice) {

    suspend fun setAvTransportUri(url: String, metadata: String) = execute(
        action = "SetAVTransportURI",
        arguments = buildString {
            append("<InstanceID>0</InstanceID>")
            append("<CurrentURI>${escapeXml(url)}</CurrentURI>")
            append("<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>")
        },
    )

    suspend fun play() = execute(
        action = "Play",
        arguments = PLAY_ARGUMENTS,
    )

    suspend fun pause() = execute(
        action = "Pause",
        arguments = INSTANCE_ARGUMENTS,
    )

    suspend fun stop() = execute(
        action = "Stop",
        arguments = INSTANCE_ARGUMENTS,
    )

    suspend fun seek(positionMs: Long) = execute(
        action = "Seek",
        arguments = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
            "<Target>${formatTransportTime(positionMs)}</Target>",
    )

    suspend fun transportInfo(): TransportInfo = withContext(Dispatchers.IO) {
        val positions = soapRequest(
            action = "GetPositionInfo",
            arguments = INSTANCE_ARGUMENTS,
        ).soapValues()
        val transport = soapRequest(
            action = "GetTransportInfo",
            arguments = INSTANCE_ARGUMENTS,
        ).soapValues()
        TransportInfo(
            positionMs = parseTransportTime(positions["RelTime"]) ?: 0L,
            durationMs = parseTransportTime(positions["TrackDuration"]) ?: 0L,
            state = transport["CurrentTransportState"],
        )
    }

    private suspend fun execute(action: String, arguments: String) {
        withContext(Dispatchers.IO) {
            soapRequest(
                action = action,
                arguments = arguments,
            )
        }
    }

    private fun soapRequest(action: String, arguments: String): String {
        val connection = URL(device.controlUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty(
                "Content-Type",
                "text/xml; charset=\"utf-8\"",
            )
            connection.setRequestProperty(
                "SOAPACTION",
                "\"$AV_TRANSPORT_SERVICE#$action\"",
            )
            connection.outputStream.use {
                it.write(
                    soapEnvelope(
                        action = action,
                        arguments = arguments,
                    ).toByteArray(),
                )
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("SOAP $action failed with HTTP $responseCode")
            }
            val response = connection.inputStream.use { it.readBytes() }
            return String(
                response,
                Charsets.UTF_8,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun soapEnvelope(action: String, arguments: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
        append("<s:Envelope")
        append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"")
        append(" xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">")
        append("<s:Body>")
        append("<u:$action xmlns:u=\"$AV_TRANSPORT_SERVICE\">")
        append(arguments)
        append("</u:$action>")
        append("</s:Body>")
        append("</s:Envelope>")
    }

    private fun String.soapValues(): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setFeature(
            XmlPullParser.FEATURE_PROCESS_NAMESPACES,
            false,
        )
        parser.setInput(
            ByteArrayInputStream(toByteArray()),
            null,
        )
        val values = mutableMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(':')
                if (tag in INTERESTING_TAGS && tag !in values) {
                    values[tag] = parser.nextText().trim()
                }
            }
            event = parser.next()
        }
        return values
    }

    private companion object {
        const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        const val INSTANCE_ARGUMENTS = "<InstanceID>0</InstanceID>"
        const val PLAY_ARGUMENTS = "$INSTANCE_ARGUMENTS<Speed>1</Speed>"
        const val TIMEOUT_MS = 5_000
        val INTERESTING_TAGS = setOf(
            "RelTime",
            "TrackDuration",
            "CurrentTransportState",
        )
    }
}
