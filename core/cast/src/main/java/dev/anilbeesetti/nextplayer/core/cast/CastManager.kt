package dev.anilbeesetti.nextplayer.core.cast

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns discovery, the local media server and the AVTransport session of one renderer.
 * All state changes are published through [state] so the player UI stays a thin adapter.
 */
class CastManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = SsdpDiscovery(context = appContext)
    private val mediaServer = LocalMediaServer(context = appContext)

    private val stateInternal = MutableStateFlow(CastUiState())
    private var client: AvTransportClient? = null
    private var pollingJob: Job? = null
    private var discoveryJob: Job? = null

    val state: StateFlow<CastUiState> = stateInternal.asStateFlow()

    fun discover() {
        if (stateInternal.value.status == CastStatus.CASTING) return
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            stateInternal.update {
                it.copy(
                    status = CastStatus.DISCOVERING,
                    devices = emptyList(),
                    error = null,
                )
            }
            runCatching { discovery.discover() }
                .onSuccess { devices ->
                    stateInternal.update {
                        it.copy(
                            status = CastStatus.IDLE,
                            devices = devices,
                        )
                    }
                }
                .onFailure {
                    stateInternal.update {
                        it.copy(
                            status = CastStatus.IDLE,
                            devices = emptyList(),
                            error = CastError.DISCOVERY_FAILED,
                        )
                    }
                }
        }
    }

    fun cast(
        device: CastDevice,
        uri: Uri,
        title: String,
        mimeType: String,
        positionMs: Long,
    ) {
        discoveryJob?.cancel()
        scope.launch {
            stopDevice()
            stateInternal.update {
                it.copy(
                    status = CastStatus.CONNECTING,
                    castingDevice = device,
                    isPlaying = false,
                    error = null,
                )
            }
            runCatching {
                val port = mediaServer.start()
                val path = mediaServer.serve(
                    uri = uri,
                    mimeType = mimeType,
                )
                val host = LocalMediaServer.localAddress(context = appContext)
                    ?: throw IllegalStateException("No local network address")
                val url = "http://$host:$port$path"
                val transport = AvTransportClient(device = device).also { client = it }
                transport.setAvTransportUri(
                    url = url,
                    metadata = didlMetadata(
                        title = title,
                        url = url,
                        mimeType = mimeType,
                    ),
                )
                transport.play()
                if (positionMs > 0) {
                    transport.seek(positionMs = positionMs)
                }
                positionMs
            }.onSuccess { startPosition ->
                stateInternal.update {
                    it.copy(
                        status = CastStatus.CASTING,
                        isPlaying = true,
                        positionMs = startPosition,
                        durationMs = 0L,
                    )
                }
                startPolling()
            }.onFailure {
                stopDevice()
                stateInternal.update {
                    it.copy(
                        status = CastStatus.IDLE,
                        castingDevice = null,
                        isPlaying = false,
                        error = CastError.CONNECTION_FAILED,
                    )
                }
            }
        }
    }

    fun play() {
        scope.launch {
            val transport = client ?: return@launch
            runCatching { transport.play() }
            stateInternal.update { it.copy(isPlaying = true) }
        }
    }

    fun pause() {
        scope.launch {
            val transport = client ?: return@launch
            runCatching { transport.pause() }
            stateInternal.update { it.copy(isPlaying = false) }
        }
    }

    fun stopCasting() {
        scope.launch {
            val transport = client
            runCatching { transport?.stop() }
            stopDevice()
            stateInternal.update {
                it.copy(
                    status = CastStatus.IDLE,
                    castingDevice = null,
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = 0L,
                )
            }
        }
    }

    fun release() {
        val transport = client
        stopDevice()
        transport?.let {
            runCatching { releaseScope.launch { it.stop() } }
        }
        scope.cancel()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val transport = client ?: break
                val info = runCatching { transport.transportInfo() }.getOrNull() ?: continue
                stateInternal.update {
                    it.copy(
                        positionMs = info.positionMs,
                        durationMs = info.durationMs,
                        isPlaying = info.state == "PLAYING",
                    )
                }
            }
        }
    }

    private fun stopDevice() {
        pollingJob?.cancel()
        pollingJob = null
        client = null
        mediaServer.stop()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
