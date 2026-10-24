package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.cast.CastController
import com.nuvio.app.features.cast.CastMediaRequest
import com.nuvio.app.features.cast.CastRemoteSnapshot
import com.nuvio.app.features.cast.CastSessionState
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_cast_error_failed
import nuvio.composeapp.generated.resources.player_cast_session_ended
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerCastHandoffTest {

    @Test
    fun selectingATargetLoadsTheActiveStreamWithMetadata() = runTest {
        val controller = HandoffCastController()
        val runtime = runtime(controller).apply { scope = backgroundScope }

        controller.connect("Living Room TV")

        val request = controller.loaded.single()
        assertEquals("https://example.com/episode.mp4", request.url)
        assertEquals("video/mp4", request.mimeType)
        assertEquals("Show Name", request.title)
        assertEquals("S1E2 · Episode title", request.subtitle)
        assertEquals("https://example.com/poster.jpg", request.artworkUrl)
        assertEquals(351_000L, request.startPositionMs)
    }

    @Test
    fun localPlaybackPausesBeforeTheRequestIsDispatched() = runTest {
        val events = mutableListOf<String>()
        val controller = HandoffCastController(onLoad = { events += "cast.load" })
        val local = HandoffPlayerEngine(onCommand = { events += "local.$it" })
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }

        controller.connect("Living Room TV")

        assertEquals(listOf("local.pause", "cast.load"), events)
    }

    @Test
    fun remoteSnapshotsDriveThePlaybackSnapshotAndLocalSnapshotsAreIgnored() = runTest {
        val controller = HandoffCastController()
        val runtime = runtime(controller).apply { scope = backgroundScope }
        controller.connect("Living Room TV")

        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(positionMs = 90_000L, durationMs = 600_000L, isPlaying = true, isLoading = false),
        )

        assertEquals(90_000L, runtime.playbackSnapshot.positionMs)
        assertEquals(600_000L, runtime.playbackSnapshot.durationMs)
        assertTrue(runtime.playbackSnapshot.isPlaying)

        val accepted = runtime.acceptLocalSnapshot(
            PlayerPlaybackSnapshot(isLoading = false, isPlaying = false, positionMs = 5_000L),
            runtime.activePlaybackKey,
        )

        assertFalse(accepted)
        assertEquals(90_000L, runtime.playbackSnapshot.positionMs)
        assertTrue(runtime.playbackSnapshot.isPlaying)
    }

    @Test
    fun playPauseAndSeekRouteToTheRemoteWhileCasting() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")
        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(positionMs = 100_000L, durationMs = 600_000L, isPlaying = true, isLoading = false),
        )
        local.commands.clear()

        runtime.togglePlayback()
        assertEquals(listOf("pause"), controller.commands)

        runtime.setPlaybackPlaying(true)
        assertEquals(listOf("pause", "play"), controller.commands)

        runtime.seekBy(10_000L)
        val seekTarget = controller.commands.last().removePrefix("seekTo:").toLong()
        assertTrue(seekTarget in 110_000L..110_100L, "expected ~110000 but was $seekTarget")

        assertTrue(local.commands.isEmpty())
    }

    @Test
    fun doubleTapSeekRoutesToTheRemote() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")
        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(positionMs = 100_000L, durationMs = 600_000L, isPlaying = true, isLoading = false),
        )
        local.commands.clear()

        runtime.handleDoubleTapSeek(PlayerSeekDirection.Forward)

        assertEquals("seekTo:110000", controller.commands.last())
        assertTrue(local.commands.isEmpty())
    }

    @Test
    fun aFinishedRemoteSnapshotMarksThePlaybackEnded() = runTest {
        val controller = HandoffCastController()
        val runtime = runtime(controller).apply { scope = backgroundScope }
        controller.connect("Living Room TV")

        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(
                positionMs = 600_000L,
                durationMs = 600_000L,
                isPlaying = false,
                isLoading = false,
                isEnded = true,
            ),
        )

        assertTrue(runtime.playbackSnapshot.isEnded)
        runtime.togglePlayback()
        assertEquals(listOf("seekTo:0", "play"), controller.commands)
    }

    @Test
    fun stoppingWithoutAnyRemoteStatusResumesPausedAtTheHandoffPosition() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")

        controller.end()

        assertEquals(listOf("pause", "seekTo:351000", "pause"), local.commands)
        assertFalse(runtime.shouldPlay)
    }

    @Test
    fun stoppingFromPlayingResumesLocalPlaybackAtTheRemotePosition() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")
        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(positionMs = 90_000L, durationMs = 600_000L, isPlaying = true, isLoading = false),
        )

        controller.end()

        assertFalse(runtime.isCastCasting)
        assertNull(runtime.castRemoteSnapshot)
        assertEquals(listOf("pause", "seekTo:90000", "play"), local.commands)
        assertTrue(runtime.shouldPlay)
        assertEquals(CastSessionState.Disconnected, runtime.castSessionState)
        assertEquals(Res.string.player_cast_session_ended, runtime.castMessageRes)
    }

    @Test
    fun aFailedLoadShowsTheFailureMessageAndRestoresThePreCastPosition() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")
        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(positionMs = 90_000L, durationMs = 600_000L, isPlaying = true, isLoading = false),
        )
        local.commands.clear()

        controller.fail()

        assertEquals(Res.string.player_cast_error_failed, runtime.castMessageRes)
        assertFalse(runtime.isCastCasting)
        assertNull(runtime.castRemoteSnapshot)
        assertEquals(351_000L, runtime.playbackSnapshot.positionMs)
        assertEquals(listOf("seekTo:351000", "play"), local.commands)
        assertTrue(runtime.shouldPlay)
        assertEquals(CastSessionState.Disconnected, runtime.castSessionState)
        assertEquals(1, controller.endedSessions)
        assertTrue(runtime.castActionState?.isEnabled == true)
        assertFalse(runtime.castActionState?.isConnected == true)
    }

    @Test
    fun noPlayingWithinFifteenSecondsIsTreatedAsAFailure() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")
        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(positionMs = 0L, durationMs = 600_000L, isPlaying = false, isLoading = true),
        )

        advanceTimeBy(14_000L)
        assertTrue(runtime.isCastCasting)

        advanceTimeBy(2_000L)

        assertFalse(runtime.isCastCasting)
        assertEquals(Res.string.player_cast_error_failed, runtime.castMessageRes)
        assertEquals(351_000L, runtime.playbackSnapshot.positionMs)
        assertEquals(listOf("pause", "seekTo:351000", "play"), local.commands)
        assertEquals(1, controller.endedSessions)
    }

    @Test
    fun aRemoteThatStartsPlayingWithinFifteenSecondsKeepsTheSession() = runTest {
        val controller = HandoffCastController()
        val runtime = runtime(controller).apply { scope = backgroundScope }
        controller.connect("Living Room TV")

        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(positionMs = 12_000L, durationMs = 600_000L, isPlaying = true, isLoading = false),
        )
        advanceTimeBy(20_000L)

        assertTrue(runtime.isCastCasting)
        assertNull(runtime.castMessageRes)
        assertEquals(0, controller.endedSessions)
    }

    @Test
    fun switchingSourceWhileConnectedReloadsTheTargetWithoutStartingLocalAudio() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")
        local.commands.clear()

        runtime.switchToSource(
            StreamItem(
                url = "https://example.com/other.mp4",
                streamType = "mp4",
                name = "Other Source",
                addonName = "Provider",
                addonId = "addon",
            ),
        )

        assertEquals(2, controller.loaded.size)
        val reload = controller.loaded.last()
        assertEquals("https://example.com/other.mp4", reload.url)
        assertEquals(351_000L, reload.startPositionMs)
        assertTrue(runtime.isCastCasting)
        assertFalse(runtime.shouldPlay)
        assertFalse(local.commands.contains("play"))
    }

    @Test
    fun advancingToTheNextEpisodeReloadsTheTargetWithTheEpisodeMetadata() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")
        local.commands.clear()

        runtime.switchToEpisodeStream(
            stream = StreamItem(
                url = "https://example.com/episode-3.mp4",
                streamType = "mp4",
                name = "Next Source",
                addonName = "Provider",
                addonId = "addon",
            ),
            episode = MetaVideo(
                id = "tt1234567:2:1",
                title = "Next Episode",
                season = 2,
                episode = 1,
            ),
        )

        assertEquals(2, controller.loaded.size)
        val reload = controller.loaded.last()
        assertEquals("https://example.com/episode-3.mp4", reload.url)
        assertEquals("S2E1 · Next Episode", reload.subtitle)
        assertEquals(0L, reload.startPositionMs)
        assertTrue(runtime.isCastCasting)
        assertFalse(runtime.shouldPlay)
        assertFalse(local.commands.contains("play"))
    }

    @Test
    fun stoppingFromPausedResumesLocalPlaybackPaused() = runTest {
        val controller = HandoffCastController()
        val local = HandoffPlayerEngine()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            playerController = local
        }
        controller.connect("Living Room TV")
        runtime.onCastRemoteSnapshot(
            CastRemoteSnapshot(positionMs = 42_000L, durationMs = 600_000L, isPlaying = false, isLoading = false),
        )

        controller.end()

        assertEquals(listOf("pause", "seekTo:42000", "pause"), local.commands)
        assertFalse(runtime.shouldPlay)
    }

    private fun runtime(controller: CastController): PlayerScreenRuntime = PlayerScreenRuntime(
        args = PlayerScreenArgs(
            profileId = 1,
            title = "Show Name",
            sourceUrl = "https://example.com/episode.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Provider",
            streamTitle = "Source",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = "https://example.com/poster.jpg",
            background = null,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Episode title",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt1234567:1:2",
            parentMetaId = "tt1234567",
            parentMetaType = "series",
            providerAddonId = null,
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
        castControllerFactory = { controller },
    ).apply {
        playbackSnapshot = PlayerPlaybackSnapshot(
            isLoading = false,
            isPlaying = true,
            positionMs = 351_000L,
            durationMs = 1_200_000L,
        )
    }
}

private class HandoffCastController(
    private val onLoad: () -> Unit = {},
) : CastController {
    override var sessionState: CastSessionState = CastSessionState.Disconnected
        private set
    override var onSessionStateChanged: ((CastSessionState) -> Unit)? = null
    override var onRemoteSnapshotChanged: ((CastRemoteSnapshot) -> Unit)? = null
    override var onPlaybackFailed: (() -> Unit)? = null

    val loaded = mutableListOf<CastMediaRequest>()
    val commands = mutableListOf<String>()
    var endedSessions = 0
        private set

    fun connect(deviceName: String) {
        sessionState = CastSessionState.Connected(deviceName)
        onSessionStateChanged?.invoke(sessionState)
    }

    fun end() {
        sessionState = CastSessionState.Disconnected
        onSessionStateChanged?.invoke(sessionState)
    }

    fun fail() {
        onPlaybackFailed?.invoke()
    }

    override fun showDevicePicker() = Unit

    override fun load(request: CastMediaRequest) {
        loaded += request
        onLoad()
    }

    override fun play() {
        commands += "play"
    }

    override fun pause() {
        commands += "pause"
    }

    override fun seekTo(positionMs: Long) {
        commands += "seekTo:$positionMs"
    }

    override fun seekBy(offsetMs: Long) {
        commands += "seekBy:$offsetMs"
    }

    override fun endSession() {
        endedSessions += 1
        end()
    }

    override fun close() = Unit
}

private class HandoffPlayerEngine(
    private val onCommand: (String) -> Unit = {},
) : PlayerEngineController {
    val commands = mutableListOf<String>()

    private fun record(command: String) {
        commands += command
        onCommand(command)
    }

    override fun play() = record("play")

    override fun pause() = record("pause")

    override fun seekTo(positionMs: Long) = record("seekTo:$positionMs")

    override fun seekBy(offsetMs: Long) = record("seekBy:$offsetMs")

    override fun retry() = Unit

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun getAudioTracks(): List<AudioTrack> = emptyList()

    override fun getSubtitleTracks(): List<SubtitleTrack> = emptyList()

    override fun applyAudioLanguagePreferences(languages: List<String>) = Unit

    override fun selectAudioTrack(index: Int) = Unit

    override fun selectSubtitleTrack(index: Int) = Unit

    override fun setSubtitleUri(url: String) = Unit

    override fun clearExternalSubtitle() = Unit

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
}
