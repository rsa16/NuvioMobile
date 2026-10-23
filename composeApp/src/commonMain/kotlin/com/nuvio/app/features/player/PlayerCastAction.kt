package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerCastActionIcon(
    state: PlayerCastActionState,
    onClick: () -> Unit,
    buttonSize: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    containerColor: Color = Color.Transparent,
) {
    val description = when {
        state.isConnected -> {
            val deviceName = state.connectedDeviceName.orEmpty()
            if (deviceName.isBlank()) {
                stringResource(Res.string.player_cast_action_connected_unknown)
            } else {
                stringResource(Res.string.player_cast_action_connected, deviceName)
            }
        }
        state.disabledReason != null -> stringResource(
            Res.string.player_cast_action_disabled,
            stringResource(state.disabledReason.messageRes),
        )
        else -> stringResource(Res.string.player_cast_action)
    }
    val tint = when {
        state.isConnected -> MaterialTheme.colorScheme.primary
        state.isEnabled -> Color.White
        else -> Color.White.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (state.isConnected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
