package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.cast.CastController
import com.nuvio.app.features.cast.CastIneligibilityReason
import com.nuvio.app.features.cast.CastMediaRequest
import com.nuvio.app.features.cast.CastRemoteSnapshot
import com.nuvio.app.features.cast.CastSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerCastRuntimeTest {

    @Test
    fun unavailableCastingHidesTheAction() {
        val controller = FakeCastController(CastSessionState.Unavailable)
        val runtime = runtime(controller)

        assertNull(runtime.castActionState)
        runtime.performCastAction()
        assertEquals(0, controller.pickerShows)
    }

    @Test
    fun castableSourceShowsAnEnabledActionThatOpensThePicker() {
        val controller = FakeCastController()
        val runtime = runtime(controller)

        val action = runtime.castActionState
        assertTrue(action != null)
        assertTrue(action.isEnabled)
        assertFalse(action.isConnected)

        runtime.performCastAction()
        assertEquals(1, controller.pickerShows)
    }

    @Test
    fun ineligibleSourcesCarryTheirReason() {
        val controller = FakeCastController()
        val cases = listOf(
            CastIneligibilityReason.Torrent to runtime(
                controller,
                sourceUrl = "https://example.com/movie.mp4",
                torrentInfoHash = "abc123",
            ),
            CastIneligibilityReason.LocalFile to runtime(
                controller,
                sourceUrl = "file:/data/user/0/app/files/movie.mp4",
            ),
            CastIneligibilityReason.External to runtime(
                controller,
                sourceUrl = "https://example.com/movie.mp4",
                youTubeId = "dQw4w9WgXcQ",
            ),
            CastIneligibilityReason.UnsupportedProtocol to runtime(
                controller,
                sourceUrl = "rtsp://example.com/live",
            ),
            CastIneligibilityReason.Loopback to runtime(
                controller,
                sourceUrl = "http://127.0.0.1:8080/movie.mp4",
            ),
        )

        cases.forEach { (expected, runtime) ->
            val action = runtime.castActionState
            assertTrue(action != null)
            assertFalse(action.isEnabled)
            assertEquals(expected, action.disabledReason)
        }
        assertEquals(0, controller.pickerShows)
    }

    @Test
    fun sessionChangesArePublishedToTheRuntime() {
        val controller = FakeCastController()
        val runtime = runtime(controller)

        controller.connect("Living Room TV")
        assertEquals(CastSessionState.Connected("Living Room TV"), runtime.castSessionState)
        assertTrue(runtime.castActionState?.isConnected == true)
        assertEquals("Living Room TV", runtime.castActionState?.connectedDeviceName)

        runtime.performCastAction()
        assertEquals(1, controller.pickerShows)

        controller.end()
        assertEquals(CastSessionState.Disconnected, runtime.castSessionState)
        assertTrue(runtime.castActionState?.isEnabled == true)
    }

    private fun runtime(
        controller: CastController,
        sourceUrl: String = "https://example.com/movie.mp4",
        torrentInfoHash: String? = null,
        youTubeId: String? = null,
    ): PlayerScreenRuntime = PlayerScreenRuntime(
        args = PlayerScreenArgs(
            profileId = 1,
            title = "Title",
            sourceUrl = sourceUrl,
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
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            contentType = "movie",
            videoId = "tt1234567",
            parentMetaId = "tt1234567",
            parentMetaType = "movie",
            providerAddonId = null,
            torrentInfoHash = torrentInfoHash,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
            stream = youTubeId?.let {
                com.nuvio.app.features.streams.StreamItem(
                    ytId = it,
                    addonName = "Test",
                    addonId = "test",
                )
            },
        ),
        castControllerFactory = { controller },
    )
}

private class FakeCastController(
    initialState: CastSessionState = CastSessionState.Disconnected,
) : CastController {
    override var sessionState: CastSessionState = initialState
        private set
    override var onSessionStateChanged: ((CastSessionState) -> Unit)? = null
    override var onRemoteSnapshotChanged: ((CastRemoteSnapshot) -> Unit)? = null

    var pickerShows = 0
        private set

    fun connect(deviceName: String) {
        sessionState = CastSessionState.Connected(deviceName)
        onSessionStateChanged?.invoke(sessionState)
    }

    fun end() {
        sessionState = CastSessionState.Disconnected
        onSessionStateChanged?.invoke(sessionState)
    }

    override fun showDevicePicker() {
        pickerShows += 1
    }

    override fun load(request: CastMediaRequest) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun seekBy(offsetMs: Long) = Unit

    override fun close() {
        sessionState = CastSessionState.Disconnected
    }
}
