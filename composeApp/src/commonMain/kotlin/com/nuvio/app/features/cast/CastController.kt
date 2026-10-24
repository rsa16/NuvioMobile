package com.nuvio.app.features.cast

internal sealed interface CastSessionState {
    data object Unavailable : CastSessionState
    data object Disconnected : CastSessionState
    data object Connecting : CastSessionState
    data class Connected(val deviceName: String) : CastSessionState
}

// reciever playback state
internal data class CastRemoteSnapshot(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val isEnded: Boolean = false,
)

internal interface CastController {
    val sessionState: CastSessionState

    var onSessionStateChanged: ((CastSessionState) -> Unit)?
    var onRemoteSnapshotChanged: ((CastRemoteSnapshot) -> Unit)?
    var onPlaybackFailed: (() -> Unit)?

    fun showDevicePicker()

    fun load(request: CastMediaRequest)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun endSession()
    fun close()
}

internal class UnavailableCastController : CastController {
    override val sessionState: CastSessionState = CastSessionState.Unavailable
    override var onSessionStateChanged: ((CastSessionState) -> Unit)? = null
    override var onRemoteSnapshotChanged: ((CastRemoteSnapshot) -> Unit)? = null
    override var onPlaybackFailed: (() -> Unit)? = null

    override fun showDevicePicker() = Unit
    override fun load(request: CastMediaRequest) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun seekBy(offsetMs: Long) = Unit
    override fun endSession() = Unit
    override fun close() = Unit
}

internal expect fun createCastController(): CastController
