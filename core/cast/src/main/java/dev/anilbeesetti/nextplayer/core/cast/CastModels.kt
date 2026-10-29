package dev.anilbeesetti.nextplayer.core.cast

/** A DLNA media renderer found on the local network. */
data class CastDevice(
    val udn: String,
    val name: String,
    val location: String,
    val controlUrl: String,
)

enum class CastStatus {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CASTING,
}

enum class CastError {
    DISCOVERY_FAILED,
    CONNECTION_FAILED,
}

data class CastUiState(
    val status: CastStatus = CastStatus.IDLE,
    val devices: List<CastDevice> = emptyList(),
    val castingDevice: CastDevice? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: CastError? = null,
)
