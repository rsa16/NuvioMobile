package com.nuvio.app.features.cast

internal sealed interface CastSessionState {
    data object Unavailable : CastSessionState
    data object Disconnected : CastSessionState
    data object Connecting : CastSessionState
    data class Connected(val deviceName: String) : CastSessionState
}

internal interface CastController {
    val sessionState: CastSessionState

    var onSessionStateChanged: ((CastSessionState) -> Unit)?

    fun showDevicePicker()
    fun close()
}

internal class UnavailableCastController : CastController {
    override val sessionState: CastSessionState = CastSessionState.Unavailable
    override var onSessionStateChanged: ((CastSessionState) -> Unit)? = null

    override fun showDevicePicker() = Unit

    override fun close() = Unit
}

internal expect fun createCastController(): CastController
