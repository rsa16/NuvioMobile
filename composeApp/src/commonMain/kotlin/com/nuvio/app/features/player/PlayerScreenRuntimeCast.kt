package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.cast.CastIneligibilityReason
import com.nuvio.app.features.cast.CastMediaRequest
import com.nuvio.app.features.cast.CastRemoteSnapshot
import com.nuvio.app.features.cast.CastSessionState
import com.nuvio.app.features.cast.CastSource
import com.nuvio.app.features.cast.CastSourceEligibility
import com.nuvio.app.features.cast.CastSubtitleSource
import com.nuvio.app.features.cast.buildCastMediaRequest
import com.nuvio.app.features.cast.castSourceEligibility
import com.nuvio.app.features.cast.isCastSubtitleSupported
import kotlinx.coroutines.delay
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

internal fun PlayerScreenRuntime.onCastSessionStateChanged(state: CastSessionState) {
    castSessionState = state
    when (state) {
        is CastSessionState.Connected -> beginCastHandoff()
        CastSessionState.Disconnected -> finishCastHandoff()
        CastSessionState.Connecting,
        CastSessionState.Unavailable,
        -> Unit
    }
}

/**
 * First we pause local, then dispatch the request. On handoff back,
 * the local playback should smoothly continue.
 */
internal fun PlayerScreenRuntime.beginCastHandoff() {
    if (isCastCasting) return
    if (currentCastEligibility !is CastSourceEligibility.Eligible) return

    val startPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
    castHandoffStartPositionMs = startPositionMs
    castWasPlayingBeforeHandoff = playbackSnapshot.isPlaying
    hasCastPlaybackStarted = false
    castRemoteSnapshot = null
    prepareCastSubtitles()
    isCastCasting = true

    shouldPlay = false
    playerController?.pause()

    castController.load(buildCurrentCastMediaRequest(startPositionMs))
    startCastPlaybackTimeout()
}

// reloads cast target after the active stream changes.
internal fun PlayerScreenRuntime.onCastPlaybackSourceChanged() {
    if (!isCastCasting) return
    when (val eligibility = currentCastEligibility) {
        CastSourceEligibility.Eligible -> reloadCastForActiveSource()
        is CastSourceEligibility.Ineligible -> {
            finishCastHandoff(showMessage = false)
            endCastSession()
            castMessageRes = eligibility.reason.messageRes
        }
    }
}

internal fun PlayerScreenRuntime.onCastRemoteSnapshot(snapshot: CastRemoteSnapshot) {
    if (!isCastCasting) return
    if (snapshot.isPlaying) {
        hasCastPlaybackStarted = true
        castLoadTimeoutJob?.cancel()
        castLoadTimeoutJob = null
    }
    castRemoteSnapshot = snapshot
    val previous = playbackSnapshot
    updatePlaybackSnapshot(
        PlayerPlaybackSnapshot(
            isLoading = snapshot.isLoading,
            isPlaying = snapshot.isPlaying,
            isEnded = snapshot.isEnded,
            durationMs = snapshot.durationMs.takeIf { it > 0L } ?: previous.durationMs,
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            bufferedPositionMs = snapshot.positionMs.coerceAtLeast(0L),
            playbackSpeed = previous.playbackSpeed,
            videoWidth = previous.videoWidth,
            videoHeight = previous.videoHeight,
        ),
    )
    if (!snapshot.isLoading) initialLoadCompleted = true
}

/**
 * If cast is rejected, local playback should resume where it left off.
 * End session so the action button updates state.
 */
internal fun PlayerScreenRuntime.onCastPlaybackFailed() {
    if (!isCastCasting) return
    val positionMs = castHandoffStartPositionMs
    val wasPlaying = castWasPlayingBeforeHandoff
    restoreLocalPlayback(positionMs = positionMs, wasPlaying = wasPlaying)
    castMessageRes = Res.string.player_cast_error_failed
    endCastSession()
}

/**
 * Resumes Local Playback at the last remote position, playing only when the
 * receiver was playing.
 */
internal fun PlayerScreenRuntime.finishCastHandoff(showMessage: Boolean = true) {
    if (!isCastCasting) return
    val remote = castRemoteSnapshot
    val positionMs = (remote?.positionMs ?: playbackSnapshot.positionMs).coerceAtLeast(0L)
    val wasPlaying = remote?.isPlaying == true

    restoreLocalPlayback(positionMs = positionMs, wasPlaying = wasPlaying)
    if (showMessage) {
        castMessageRes = Res.string.player_cast_session_ended
    }
}

private fun PlayerScreenRuntime.reloadCastForActiveSource() {
    val startPositionMs = activeInitialPositionMs.coerceAtLeast(0L)
    castHandoffStartPositionMs = startPositionMs
    castWasPlayingBeforeHandoff = playbackSnapshot.isPlaying
    hasCastPlaybackStarted = false
    castRemoteSnapshot = null
    prepareCastSubtitles()
    shouldPlay = false

    playerController?.pause()
    castController.pause()
    castController.load(buildCurrentCastMediaRequest(startPositionMs))
    startCastPlaybackTimeout()
}

// map sidecars and cast track IDs together
private fun PlayerScreenRuntime.prepareCastSubtitles() {
    val sources = visibleAddonSubtitles
        .filterNot { subtitle ->
            subtitle.url.isLocalSubtitleUri() || !isCastSubtitleSupported(subtitle.url)
        }
        .distinctBy { it.url }
        .mapIndexed { index, subtitle ->
            CastSubtitleSource(
                trackId = index.toLong() + 1L,
                url = subtitle.url,
                language = subtitle.language,
                label = subtitle.display,
                headers = subtitle.headers,
            )
        }
    castSubtitleSources = sources
    castActiveSubtitleTrackId = selectedAddonSubtitle
        ?.let { selected -> sources.firstOrNull { it.url == selected.url } }
        ?.trackId
}

internal fun PlayerScreenRuntime.selectCastSubtitle(subtitle: AddonSubtitle) {
    if (!isCastCasting) return
    val source = castSubtitleSources.firstOrNull { it.url == subtitle.url }
    if (source == null) {
        castMessageRes = Res.string.player_cast_subtitle_unavailable
        return
    }
    castActiveSubtitleTrackId = source.trackId
    castController.setActiveSubtitleTrack(source.trackId)
}

internal fun PlayerScreenRuntime.clearCastSubtitle() {
    if (!isCastCasting) return
    castActiveSubtitleTrackId = null
    castController.setActiveSubtitleTrack(null)
}

private fun PlayerScreenRuntime.startCastPlaybackTimeout() {
    if (!hasScope) return
    castLoadTimeoutJob?.cancel()
    castLoadTimeoutJob = scope.launch {
        delay(CastPlaybackStartTimeoutMs)
        if (isCastCasting && !hasCastPlaybackStarted) {
            onCastPlaybackFailed()
        }
    }
}

private fun PlayerScreenRuntime.restoreLocalPlayback(positionMs: Long, wasPlaying: Boolean) {
    flushWatchProgress()

    castLoadTimeoutJob?.cancel()
    castLoadTimeoutJob = null
    hasCastPlaybackStarted = false
    isCastCasting = false
    castRemoteSnapshot = null
    castSubtitleSources = emptyList()
    castActiveSubtitleTrackId = null
    isScrubbingTimeline = false
    scrubbingPositionMs = null
    playbackSnapshot = playbackSnapshot.copy(
        isPlaying = false,
        isLoading = true,
        positionMs = positionMs,
    )
    playerController?.seekTo(positionMs)
    shouldPlay = wasPlaying
    if (wasPlaying) playerController?.play() else playerController?.pause()
    controlsVisible = true
}

private fun PlayerScreenRuntime.endCastSession() {
    castSessionState = CastSessionState.Disconnected
    castController.endSession()
}

internal fun PlayerScreenRuntime.acceptLocalSnapshot(
    snapshot: PlayerPlaybackSnapshot,
    playbackKey: PlaybackKey,
): Boolean {
    if (isCastCasting) {
        return false
    }
    return updatePlaybackSnapshot(snapshot, playbackKey)
}

private fun PlayerScreenRuntime.buildCurrentCastMediaRequest(startPositionMs: Long): CastMediaRequest =
    buildCastMediaRequest(
        url = activeSourceUrl,
        streamType = activeStreamType,
        title = title.ifBlank { activeStreamTitle },
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        episodeTitle = activeEpisodeTitle,
        artworkUrl = poster ?: background,
        startPositionMs = startPositionMs,
        subtitleSources = castSubtitleSources,
        activeSubtitleTrackId = castActiveSubtitleTrackId,
        subtitleDelayMs = subtitleDelayMs,
    )

private fun PlayerScreenRuntime.showCastIneligibility(reason: CastIneligibilityReason) {
    scope.launch {
        NuvioToastController.show(getString(reason.messageRes))
    }
}

@Composable
internal fun PlayerScreenRuntime.BindCastSessionEffects() {
    LaunchedEffect(castMessageRes) {
        val messageRes = castMessageRes ?: return@LaunchedEffect
        castMessageRes = null
        NuvioToastController.show(getString(messageRes))
    }
    DisposableEffect(castController) {
        onDispose {
            castController.onRemoteSnapshotChanged = null
            castController.onPlaybackFailed = null
            castController.close()
        }
    }
}

private const val CastPlaybackStartTimeoutMs = 15_000L

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
