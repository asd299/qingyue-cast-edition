package dev.anilbeesetti.nextplayer.feature.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.cast.CastDevice
import dev.anilbeesetti.nextplayer.core.cast.CastError
import dev.anilbeesetti.nextplayer.core.cast.CastStatus
import dev.anilbeesetti.nextplayer.core.cast.CastUiState
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

/**
 * Device sheet for casting: pick a renderer, follow the session and stop it.
 * Mirrors the cast panel of the Quark player while playback continues on the phone.
 */
@Composable
fun CastDevicePanel(
    state: CastUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onDeviceClick: (CastDevice) -> Unit,
    onPlayPauseClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val scrimInteractionSource = remember { MutableInteractionSource() }
    val panelInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.Black.copy(alpha = 0.7f))
            .clickable(
                interactionSource = scrimInteractionSource,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(fraction = 0.92f)
                .clickable(
                    interactionSource = panelInteractionSource,
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                CastPanelHeader(
                    state = state,
                    onRefresh = onRefresh,
                    onDismiss = onDismiss,
                )
                when {
                    state.status == CastStatus.CASTING ||
                        state.status == CastStatus.CONNECTING -> CastSessionView(
                        state = state,
                        onPlayPauseClick = onPlayPauseClick,
                        onStopClick = onStopClick,
                    )

                    else -> CastDeviceListView(
                        state = state,
                        onDeviceClick = onDeviceClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun CastPanelHeader(
    state: CastUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (state.status) {
        CastStatus.CASTING -> state.castingDevice?.name
            ?.let { name ->
                stringResource(
                    id = R.string.cast_casting_to,
                    name,
                )
            }
            ?: stringResource(R.string.cast_to_device)

        CastStatus.CONNECTING -> stringResource(
            id = R.string.cast_connecting_to,
            state.castingDevice?.name ?: stringResource(R.string.cast_unknown_device),
        )

        else -> stringResource(R.string.cast_to_device)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(weight = 1f),
        )
        if (state.status == CastStatus.IDLE) {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = NextIcons.Update,
                    contentDescription = stringResource(R.string.cast_refresh),
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = NextIcons.Close,
                contentDescription = stringResource(R.string.cast_close),
            )
        }
    }
}

@Composable
private fun CastSessionView(
    state: CastUiState,
    onPlayPauseClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.status == CastStatus.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = state.castingDevice?.name.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        imageVector = if (state.isPlaying) NextIcons.Pause else NextIcons.Play,
                        contentDescription = stringResource(
                            id = if (state.isPlaying) R.string.cast_pause else R.string.cast_play,
                        ),
                    )
                }
                Text(
                    text = stringResource(
                        id = if (state.isPlaying) R.string.cast_pause else R.string.cast_play,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        OutlinedButton(
            onClick = onStopClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.cast_stop))
        }
    }
}

@Composable
private fun CastDeviceListView(state: CastUiState, onDeviceClick: (CastDevice) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        when {
            state.status == CastStatus.DISCOVERING -> CastStatusRow(
                text = stringResource(R.string.cast_searching),
                showProgress = true,
            )

            state.error != null -> CastStatusRow(
                text = stringResource(
                    id = when (state.error) {
                        CastError.CONNECTION_FAILED -> R.string.cast_error_connection
                        else -> R.string.cast_error_discovery
                    },
                ),
            )

            state.devices.isEmpty() -> CastStatusRow(
                text = stringResource(R.string.cast_no_devices),
            )

            else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(
                    items = state.devices,
                    key = { it.udn },
                ) { device ->
                    CastDeviceRow(
                        device = device,
                        onClick = { onDeviceClick(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CastStatusRow(text: String, showProgress: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CastDeviceRow(device: CastDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = 20.dp,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = NextIcons.Cast,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = device.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(weight = 1f),
        )
    }
}
