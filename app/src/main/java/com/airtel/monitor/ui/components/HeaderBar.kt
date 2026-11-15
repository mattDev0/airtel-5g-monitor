package com.airtel.monitor.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.R
import com.airtel.monitor.core.util.NetworkFormatters
import com.airtel.monitor.data.model.ConnectionStatus
import com.airtel.monitor.ui.theme.AirtelTheme

/**
 * Slim app bar shown at the top of every tab.
 *
 * Title + live status on the left; on the right an optional unread SMS badge button,
 * a pause/resume toggle, and an overflow menu with Refresh, poll interval, and Reboot.
 */
@Composable
fun HeaderBar(
    connectionStatus: ConnectionStatus,
    isPaused: Boolean,
    pollInterval: Float,
    routerHost: String,
    smsUnreadCount: Int = 0,
    onOpenSms: () -> Unit = {},
    onTogglePause: () -> Unit,
    onForceRefresh: () -> Unit,
    onSelectPollInterval: (Float) -> Unit,
    onOpenReboot: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val semantic = AirtelTheme.colors

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val (statusText, statusColor) = when {
        isPaused -> stringResource(R.string.status_paused) to semantic.warning
        connectionStatus == ConnectionStatus.CONNECTED -> stringResource(R.string.status_live) to semantic.success
        connectionStatus == ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting) to MaterialTheme.colorScheme.primary
        connectionStatus == ConnectionStatus.RECONNECTING -> stringResource(R.string.status_reconnecting) to semantic.error
        else -> stringResource(R.string.status_offline) to semantic.error
    }
    val pulsing = connectionStatus == ConnectionStatus.CONNECTED && !isPaused

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Router,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.brand_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .scale(if (pulsing) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
                Text(
                    text = "· ${NetworkFormatters.formatPollInterval(pollInterval)} · $routerHost",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (smsUnreadCount > 0) {
            IconButton(onClick = onOpenSms) {
                BadgedBox(
                    badge = {
                        Badge {
                            Text(if (smsUnreadCount > 99) "99+" else smsUnreadCount.toString())
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Email,
                        contentDescription = stringResource(R.string.cd_unread_sms),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        IconButton(onClick = onTogglePause) {
            Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = stringResource(
                    if (isPaused) R.string.cd_resume_polling else R.string.cd_pause_polling
                ),
                tint = if (isPaused) semantic.warning else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.cd_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_refresh_now)) },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    onClick = { menuExpanded = false; onForceRefresh() }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.menu_update_every),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                listOf(1.0f, 1.5f, 2.0f, 3.0f, 5.0f).forEach { interval ->
                    val selected = interval == pollInterval
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = NetworkFormatters.formatPollInterval(interval),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = { menuExpanded = false; onSelectPollInterval(interval) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_reboot_router), color = semantic.error) },
                    leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = semantic.error) },
                    onClick = { menuExpanded = false; onOpenReboot() }
                )
            }
        }
    }
}
