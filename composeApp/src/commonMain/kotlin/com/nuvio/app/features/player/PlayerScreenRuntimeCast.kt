package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.cast.CastIneligibilityReason
import com.nuvio.app.features.cast.CastSessionState
import com.nuvio.app.features.cast.CastSource
import com.nuvio.app.features.cast.CastSourceEligibility
import com.nuvio.app.features.cast.castSourceEligibility
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal data class PlayerCastActionState(
    val connectedDeviceName: String?,
    val disabledReason: CastIneligibilityReason?,
) {
    val isConnected: Boolean get() = connectedDeviceName != null
    val isEnabled: Boolean get() = isConnected || disabledReason == null
}

internal val PlayerScreenRuntime.castActionState: PlayerCastActionState?
    get() = when (val session = castSessionState) {
        CastSessionState.Unavailable -> null
        is CastSessionState.Connected -> PlayerCastActionState(
            connectedDeviceName = session.deviceName,
            disabledReason = null,
        )
        CastSessionState.Connecting,
        CastSessionState.Disconnected,
        -> PlayerCastActionState(
            connectedDeviceName = null,
            disabledReason = currentCastEligibility.ineligibleReason(),
        )
    }

private val PlayerScreenRuntime.currentCastEligibility: CastSourceEligibility
    get() = castSourceEligibility(
        CastSource(
            url = activeSourceUrl,
            torrentInfoHash = activeTorrentInfoHash,
            youTubeId = activeStream?.ytId,
            externalUrl = activeStream?.externalUrl,
        ),
    )

internal fun PlayerScreenRuntime.performCastAction() {
    val action = castActionState ?: return
    if (action.isConnected) {
        castController.showDevicePicker()
        return
    }
    when (val eligibility = currentCastEligibility) {
        CastSourceEligibility.Eligible -> castController.showDevicePicker()
        is CastSourceEligibility.Ineligible -> showCastIneligibility(eligibility.reason)
    }
}

private fun PlayerScreenRuntime.showCastIneligibility(reason: CastIneligibilityReason) {
    scope.launch {
        NuvioToastController.show(getString(reason.messageRes))
    }
}

@Composable
internal fun PlayerScreenRuntime.BindCastSessionEffects() {
    DisposableEffect(castController) {
        onDispose { castController.close() }
    }
}

private fun CastSourceEligibility.ineligibleReason(): CastIneligibilityReason? =
    (this as? CastSourceEligibility.Ineligible)?.reason

internal val CastIneligibilityReason.messageRes: StringResource
    get() = when (this) {
        CastIneligibilityReason.Torrent -> Res.string.player_cast_reason_torrent
        CastIneligibilityReason.LocalFile -> Res.string.player_cast_reason_local_file
        CastIneligibilityReason.External -> Res.string.player_cast_reason_external
        CastIneligibilityReason.UnsupportedProtocol -> Res.string.player_cast_reason_unsupported_protocol
        CastIneligibilityReason.Loopback -> Res.string.player_cast_reason_loopback
    }
