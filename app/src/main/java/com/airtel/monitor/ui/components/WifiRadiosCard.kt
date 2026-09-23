@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.data.model.WifiRadioConfig
import com.airtel.monitor.ui.theme.StatusError
import com.airtel.monitor.ui.theme.StatusSuccess
import com.airtel.monitor.ui.theme.StatusWarning

private enum class PendingRadioChange {
    TOGGLE_24G,
    TOGGLE_5G
}

@Composable
fun WifiRadiosCard(
    wifiConfig: WifiRadioConfig,
    isLoading: Boolean,
    isSaving: Boolean,
    message: String?,
    onReload: () -> Unit,
    onToggle24g: (Boolean) -> Unit,
    onToggle5g: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingChange by remember { mutableStateOf<PendingRadioChange?>(null) }

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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Wi-Fi Radios",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "2.4 GHz and 5 GHz broadcast control",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onReload,
                    enabled = !isLoading && !isSaving,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isLoading) {
                        LoadingIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload Wi-Fi status",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 2.4 GHz Radio Item
            RadioControlRow(
                title = "2.4 GHz Network",
                ssid = wifiConfig.ssid24g,
                channel = wifiConfig.channel24g,
                isEnabled = wifiConfig.enabled24g,
                canToggle = !isLoading && !isSaving,
                onToggleRequested = {
                    pendingChange = PendingRadioChange.TOGGLE_24G
                }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                thickness = 0.8.dp
            )

            // 5 GHz Radio Item
            RadioControlRow(
                title = "5 GHz Network",
                ssid = wifiConfig.ssid5g,
                channel = wifiConfig.channel5g,
                isEnabled = wifiConfig.enabled5g,
                canToggle = !isLoading && !isSaving,
                onToggleRequested = {
                    pendingChange = PendingRadioChange.TOGGLE_5G
                }
            )

            // Saving In-Progress Notice
            AnimatedVisibility(visible = isSaving) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LoadingIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Applying radio changes...",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Wi-Fi will momentarily drop (~5-10s) as the router reloads.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Status or Error Message
            if (message != null && !isSaving) {
                Text(
                    text = message,
                    color = if (message.startsWith("Error")) StatusError else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    // Safe Confirmation Dialog
    pendingChange?.let { change ->
        val is24g = (change == PendingRadioChange.TOGGLE_24G)
        val currentlyEnabled = if (is24g) wifiConfig.enabled24g else wifiConfig.enabled5g
        val targetEnabled = !currentlyEnabled
        val bandLabel = if (is24g) "2.4 GHz" else "5 GHz"
        val actionLabel = if (targetEnabled) "Turn On" else "Turn Off"
        val otherLabel = if (is24g) "5 GHz" else "2.4 GHz"
        val otherEnabled = if (is24g) wifiConfig.enabled5g else wifiConfig.enabled24g
        // Turning off the only broadcasting band would disconnect this phone for good.
        val blocked = !targetEnabled && !otherEnabled

        AlertDialog(
            onDismissRequest = { pendingChange = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = if (targetEnabled) MaterialTheme.colorScheme.primary else StatusWarning
                )
            },
            title = {
                Text(
                    text = if (blocked) "Can't turn off $bandLabel" else "$actionLabel $bandLabel Wi-Fi?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when {
                            blocked -> "$otherLabel is off, so $bandLabel is your only Wi-Fi. Turning it off would disconnect every wireless device, including this phone, and the app couldn't turn it back on. Turn on $otherLabel first."
                            targetEnabled -> "The router will enable the $bandLabel radio broadcast."
                            else -> "The router will disable the $bandLabel radio broadcast. Connected devices on this band will disconnect."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!blocked) Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = "Notice: The router wireless subsystem will restart immediately. Wi-Fi connections will briefly drop for 5–10 seconds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (blocked) {
                    TextButton(onClick = { pendingChange = null }) {
                        Text("OK")
                    }
                } else Button(
                    onClick = {
                        val toExecute = change
                        pendingChange = null
                        if (toExecute == PendingRadioChange.TOGGLE_24G) {
                            onToggle24g(targetEnabled)
                        } else {
                            onToggle5g(targetEnabled)
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = if (targetEnabled) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(actionLabel)
                }
            },
            dismissButton = if (blocked) null else {
                {
                    TextButton(onClick = { pendingChange = null }) {
                        Text("Cancel")
                    }
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun RadioControlRow(
    title: String,
    ssid: String,
    channel: String,
    isEnabled: Boolean,
    canToggle: Boolean,
    onToggleRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            // Icon Pill
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEnabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
            }

            // Info Column
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = CircleShape,
                        color = if (isEnabled) StatusSuccess.copy(alpha = 0.12f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ) {
                        Text(
                            text = if (isEnabled) "Active" else "Off",
                            color = if (isEnabled) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                val subtitleText = buildString {
                    if (ssid.isNotBlank()) {
                        append(ssid)
                    } else {
                        append(if (isEnabled) "Broadcasting" else "Disabled")
                    }
                    if (channel.isNotBlank()) {
                        append(" • Ch $channel")
                    }
                }

                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        // Toggle Switch
        Switch(
            checked = isEnabled,
            onCheckedChange = { onToggleRequested() },
            enabled = canToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )
    }
}
