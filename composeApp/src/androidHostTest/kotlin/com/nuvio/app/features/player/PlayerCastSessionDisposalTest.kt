package com.nuvio.app.features.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.app.features.cast.CastController
import com.nuvio.app.features.cast.CastMediaRequest
import com.nuvio.app.features.cast.CastRemoteSnapshot
import com.nuvio.app.features.cast.CastSessionState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerCastSessionDisposalTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun leavingThePlayerClosesTheCastController() {
        val controller = RecordingCastController()
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs(), castControllerFactory = { controller })
        val playerVisible = mutableStateOf(true)

        compose.setContent {
            if (playerVisible.value) runtime.BindCastSessionEffects()
        }
        compose.waitForIdle()
        assertEquals(0, controller.closes)

        compose.runOnIdle { playerVisible.value = false }
        compose.waitForIdle()

        assertEquals(1, controller.closes)
    }

    private fun testPlayerScreenArgs() = PlayerScreenArgs(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
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
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
    )
}

private class RecordingCastController : CastController {
    override var sessionState: CastSessionState = CastSessionState.Connected("Living Room TV")
        private set
    override var onSessionStateChanged: ((CastSessionState) -> Unit)? = null
    override var onRemoteSnapshotChanged: ((CastRemoteSnapshot) -> Unit)? = null

    var closes = 0
        private set

    override fun showDevicePicker() = Unit

    override fun load(request: CastMediaRequest) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun seekBy(offsetMs: Long) = Unit

    override fun close() {
        closes += 1
        sessionState = CastSessionState.Disconnected
        onSessionStateChanged = null
    }
}
