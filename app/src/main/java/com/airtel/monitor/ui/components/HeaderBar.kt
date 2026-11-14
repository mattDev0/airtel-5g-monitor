package com.airtel.monitor.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.data.model.ConnectionStatus
import com.airtel.monitor.ui.theme.*

/**
 * Slim app bar shown at the top of every tab.
 *
 * Title + live status on the left; on the right only a pause/resume toggle and an
 * overflow menu. Refresh, poll interval, DNS, settings and reboot live in the menu
 * so the header no longer competes with the content cards below it.
 */
@Composable
fun HeaderBar(
    connectionStatus: ConnectionStatus,
    isPaused: Boolean,
    pollInterval: Float,
    routerHost: String,
    onTogglePause: () -> Unit,
    onForceRefresh: () -> Unit,
    onSelectPollInterval: (Float) -> Unit,
    onOpenDns: () -> Unit,
    onOpenReboot: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

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
        isPaused -> "Paused" to StatusWarning
        connectionStatus == ConnectionStatus.CONNECTED -> "Live" to StatusSuccess
        connectionStatus == ConnectionStatus.CONNECTING -> "Connecting" to MaterialTheme.colorScheme.primary
        connectionStatus == ConnectionStatus.RECONNECTING -> "Reconnecting" to StatusError
        else -> "Offline" to StatusError
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
                text = "Airtel 5G",
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
                    text = "· ${formatInterval(pollInterval)} · $routerHost",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onTogglePause) {
            Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (isPaused) "Resume" else "Pause",
                tint = if (isPaused) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More",
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
                    text = { Text("Refresh now") },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    onClick = { menuExpanded = false; onForceRefresh() }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Update every",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                listOf(1.0f, 1.5f, 2.0f, 3.0f, 5.0f).forEach { interval ->
                    val selected = interval == pollInterval
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = formatInterval(interval),
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
                    text = { Text("DNS") },
                    leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    onClick = { menuExpanded = false; onOpenDns() }
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = { menuExpanded = false; onOpenSettings() }
                )
                DropdownMenuItem(
                    text = { Text("Reboot router", color = StatusError) },
                    leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = StatusError) },
                    onClick = { menuExpanded = false; onOpenReboot() }
                )
            }
        }
    }
}

private fun formatInterval(seconds: Float): String =
    if (seconds % 1f == 0f) "${seconds.toInt()}s" else "${seconds}s"
