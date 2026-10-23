package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.cast.CastIneligibilityReason
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
class PlayerCastActionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun modernToolbarShowsTheEnabledCastAction() {
        var clicks = 0
        showLayout(
            useLegacyLayout = false,
            action = PlayerCastActionState(connectedDeviceName = null, disabledReason = null),
            onCastClick = { clicks += 1 },
        )

        compose.onNodeWithContentDescription("Cast").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun legacyHeaderShowsTheEnabledCastAction() {
        var clicks = 0
        showLayout(
            useLegacyLayout = true,
            action = PlayerCastActionState(connectedDeviceName = null, disabledReason = null),
            onCastClick = { clicks += 1 },
        )

        compose.onNodeWithContentDescription("Cast").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun disabledActionNamesItsReasonInTheModernLayout() {
        assertDisabledReason(useLegacyLayout = false)
    }

    @Test
    fun disabledActionNamesItsReasonInTheLegacyLayout() {
        assertDisabledReason(useLegacyLayout = true)
    }

    @Test
    fun connectedActionNamesTheDeviceInTheModernLayout() {
        assertConnectedDevice(useLegacyLayout = false)
    }

    @Test
    fun connectedActionNamesTheDeviceInTheLegacyLayout() {
        assertConnectedDevice(useLegacyLayout = true)
    }

    private fun assertDisabledReason(useLegacyLayout: Boolean) {
        var clicks = 0
        showLayout(
            useLegacyLayout = useLegacyLayout,
            action = PlayerCastActionState(
                connectedDeviceName = null,
                disabledReason = CastIneligibilityReason.Torrent,
            ),
            onCastClick = { clicks += 1 },
        )

        compose
            .onNodeWithContentDescription("Cast unavailable: torrent and P2P streams can't be cast")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private fun assertConnectedDevice(useLegacyLayout: Boolean) {
        showLayout(
            useLegacyLayout = useLegacyLayout,
            action = PlayerCastActionState(
                connectedDeviceName = "Living Room TV",
                disabledReason = null,
            ),
            onCastClick = {},
        )

        compose.onNodeWithContentDescription("Casting to Living Room TV").assertIsDisplayed()
    }

    private fun showLayout(
        useLegacyLayout: Boolean,
        action: PlayerCastActionState,
        onCastClick: () -> Unit,
    ) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 360.dp)) {
                    PlayerControlsShell(
                        title = "Title",
                        streamTitle = "Source",
                        providerName = "Provider",
                        seasonNumber = null,
                        episodeNumber = null,
                        episodeTitle = null,
                        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false, durationMs = 60_000L),
                        displayedPositionMs = 10_000L,
                        metrics = PlayerLayoutMetrics.fromWidth(640.dp),
                        resizeMode = PlayerResizeMode.Fit,
                        isLocked = false,
                        useLegacyLayout = useLegacyLayout,
                        onLockToggle = {},
                        onBack = {},
                        onTogglePlayback = {},
                        onSeekBack = {},
                        onSeekForward = {},
                        onResizeModeClick = {},
                        onSpeedClick = {},
                        onSubtitleClick = {},
                        onAudioClick = {},
                        onScrubChange = {},
                        onScrubFinished = {},
                        horizontalSafePadding = 0.dp,
                        castAction = action,
                        onCastClick = onCastClick,
                    )
                }
            }
        }
    }
}
