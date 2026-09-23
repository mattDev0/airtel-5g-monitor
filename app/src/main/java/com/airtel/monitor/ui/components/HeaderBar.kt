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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.data.model.ConnectionStatus
import com.airtel.monitor.ui.theme.*

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
    var pollMenuExpanded by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Row: App Title, Subtitle, and Live Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Icon Box
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "Router",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Airtel 5G",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Status Pill
                            val (badgeBg, badgeText, badgeColor) = when {
                                isPaused -> Triple(StatusWarning.copy(alpha = 0.15f), "PAUSED", StatusWarning)
                                connectionStatus == ConnectionStatus.CONNECTED -> Triple(StatusSuccess.copy(alpha = 0.15f), "LIVE", StatusSuccess)
                                connectionStatus == ConnectionStatus.CONNECTING -> Triple(MaterialTheme.colorScheme.primaryContainer, "CONNECTING", MaterialTheme.colorScheme.primary)
                                connectionStatus == ConnectionStatus.RECONNECTING -> Triple(StatusError.copy(alpha = 0.15f), "RECONNECTING", StatusError)
                                else -> Triple(StatusError.copy(alpha = 0.15f), "OFFLINE", StatusError)
                            }

                            Surface(
                                shape = CircleShape,
                                color = badgeBg
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .scale(if (connectionStatus == ConnectionStatus.CONNECTED && !isPaused) pulseScale else 1f)
                                            .clip(CircleShape)
                                            .background(badgeColor)
                                    )
                                    Text(
                                        text = badgeText,
                                        color = badgeColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }

                        Text(
                            text = "ZLT X17M • $routerHost",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 1.dp
            )

            // Minimal Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Poll Interval Selector
                Box {
                    Surface(
                        onClick = { pollMenuExpanded = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Poll: ${pollInterval}s",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = pollMenuExpanded,
                        onDismissRequest = { pollMenuExpanded = false }
                    ) {
                        listOf(1.0f, 1.5f, 2.0f, 3.0f, 5.0f).forEach { interval ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${interval}s ${if (interval == 1.0f) "(Fast)" else if (interval == 2.0f) "(Balanced)" else ""}",
                                        color = if (interval == pollInterval) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (interval == pollInterval) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onSelectPollInterval(interval)
                                    pollMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                // Minimal Action Icon Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pause/Resume: a toggle, so it morphs to its "checked" shape while paused
                    FilledTonalIconToggleButton(
                        checked = isPaused,
                        onCheckedChange = { onTogglePause() },
                        shapes = IconButtonDefaults.toggleableShapes(),
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Refresh Button
                    FilledTonalIconButton(
                        onClick = onForceRefresh,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // DNS Button
                    FilledTonalIconButton(
                        onClick = onOpenDns,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Dns,
                            contentDescription = "DNS",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Settings Button
                    FilledTonalIconButton(
                        onClick = onOpenSettings,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Reboot Button
                    FilledTonalIconButton(
                        onClick = onOpenReboot,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = StatusError.copy(alpha = 0.15f),
                            contentColor = StatusError
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Reboot",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

