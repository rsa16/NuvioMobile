package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.cast.CastController
import com.nuvio.app.features.cast.CastMediaRequest
import com.nuvio.app.features.cast.CastRemoteSnapshot
import com.nuvio.app.features.cast.CastSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerCastSubtitlesTest {

    @Test
    fun castingCarriesTheLocalSidecarSelectionAndSkipsUnsupportedSources() = runTest {
        val controller = SubtitleCastController()
        val runtime = runtime(controller).apply {
            scope = backgroundScope
            subtitleDelayMs = 1_500
            addonSubtitles = listOf(
                sidecar("https://example.com/english.srt", "English"),
                sidecar("https://example.com/spanish.vtt", "Spanish"),
                sidecar("https://example.com/styled.ass", "Styled"),
                sidecar("file:/data/user/0/app/files/local.srt", "Local"),
            )
            subtitleTracks = listOf(
                SubtitleTrack(index = 3, id = "embedded", label = "Embedded", isSelected = true),
            )
            selectedAddonSubtitleId = "https://example.com/english.srt"
        }

        controller.connect("Living Room TV")

        val request = controller.loaded.single()
        assertEquals(1_500, request.subtitleDelayMs)
        assertEquals(listOf("https://example.com/english.srt", "https://example.com/spanish.vtt"), request.subtitleSources.map { it.url })
        assertEquals(listOf(1L, 2L), request.subtitleSources.map { it.trackId })
        assertEquals(1L, request.activeSubtitleTrackId)
        assertEquals(
            listOf("https://example.com/english.srt"),
            request.subtitleSources.filter { it.trackId == 1L }.map { it.url },
        )
        assertTrue(request.subtitleSources.none { it.url.endsWith(".ass") })
        assertTrue(request.subtitleSources.none { it.url.startsWith("file:") })
        assertEquals(2, runtime.castSubtitleSources.size)
    }

    @Test
    fun switchingTheSidecarWhileCastingUpdatesTheRemoteActiveTrack() = runTest {
        val controller = SubtitleCastController()
        val runtime = castingRuntime(controller)

        runtime.selectCastSubtitle(runtime.addonSubtitles.last())

        assertEquals(2L, runtime.castActiveSubtitleTrackId)
        assertEquals(listOf<Long?>(2L), controller.activeSubtitleTrackIds)
    }

    @Test
    fun choosingOffClearsTheRemoteTrackAndTheSelectedSource() = runTest {
        val controller = SubtitleCastController()
        val runtime = castingRuntime(controller)

        runtime.clearCastSubtitle()

        assertNull(runtime.castActiveSubtitleTrackId)
        assertEquals(listOf<Long?>(null), controller.activeSubtitleTrackIds)
    }

    @Test
    fun stoppingTheCastClearsTheHostedSubtitleSelection() = runTest {
        val controller = SubtitleCastController()
        val runtime = castingRuntime(controller)

        controller.end()

        assertTrue(runtime.castSubtitleSources.isEmpty())
        assertNull(runtime.castActiveSubtitleTrackId)
    }

    private fun castingRuntime(controller: SubtitleCastController): PlayerScreenRuntime =
        runtime(controller).apply {
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            addonSubtitles = listOf(
                sidecar("https://example.com/english.srt", "English"),
                sidecar("https://example.com/spanish.vtt", "Spanish"),
            )
            selectedAddonSubtitleId = "https://example.com/english.srt"
            controller.connect("Living Room TV")
        }

    private fun sidecar(url: String, display: String) = AddonSubtitle(
        id = display,
        url = url,
        language = display.take(2).lowercase(),
        display = display,
    )

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
            poster = null,
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
    )
}

private class SubtitleCastController : CastController {
    override var sessionState: CastSessionState = CastSessionState.Disconnected
        private set
    override var onSessionStateChanged: ((CastSessionState) -> Unit)? = null
    override var onRemoteSnapshotChanged: ((CastRemoteSnapshot) -> Unit)? = null
    override var onPlaybackFailed: (() -> Unit)? = null

    val loaded = mutableListOf<CastMediaRequest>()
    val activeSubtitleTrackIds = mutableListOf<Long?>()

    fun connect(deviceName: String) {
        sessionState = CastSessionState.Connected(deviceName)
        onSessionStateChanged?.invoke(sessionState)
    }

    fun end() {
        sessionState = CastSessionState.Disconnected
        onSessionStateChanged?.invoke(sessionState)
    }

    override fun showDevicePicker() = Unit

    override fun load(request: CastMediaRequest) {
        loaded += request
    }

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun seekBy(offsetMs: Long) = Unit

    override fun setActiveSubtitleTrack(trackId: Long?) {
        activeSubtitleTrackIds += trackId
    }

    override fun endSession() {
        end()
    }

    override fun close() = Unit
}
