package com.airtel.monitor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.data.model.DnsConfig
import com.airtel.monitor.ui.theme.StatusError
import com.airtel.monitor.ui.theme.StatusSuccess
import com.airtel.monitor.ui.theme.StatusWarning

@Composable
fun LanDnsCard(
    dnsConfig: DnsConfig,
    isLoaded: Boolean,
    isLoading: Boolean,
    isSaving: Boolean,
    message: String?,
    onReload: () -> Unit,
    onSaveDns: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var primaryInput by remember(dnsConfig.primary) { mutableStateOf(dnsConfig.primary) }
    var secondaryInput by remember(dnsConfig.secondary) { mutableStateOf(dnsConfig.secondary) }
    var showConfirmSave by remember { mutableStateOf(false) }

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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "LAN DNS",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Router DHCP DNS assignment",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Until the router has answered, dnsConfig holds defaults, not data.
                val badgeColor = when {
                    !isLoaded -> MaterialTheme.colorScheme.onSurfaceVariant
                    dnsConfig.dhcpEnabled -> StatusSuccess
                    else -> StatusWarning
                }
                Surface(
                    shape = CircleShape,
                    color = badgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = when {
                            !isLoaded -> "Loading"
                            dnsConfig.dhcpEnabled -> "DHCP Active"
                            else -> "DHCP Off"
                        },
                        color = badgeColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Inputs Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = primaryInput,
                    onValueChange = { primaryInput = it },
                    label = { Text("Primary DNS", style = MaterialTheme.typography.bodySmall) },
                    placeholder = { Text("1.1.1.1", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                OutlinedTextField(
                    value = secondaryInput,
                    onValueChange = { secondaryInput = it },
                    label = { Text("Secondary DNS", style = MaterialTheme.typography.bodySmall) },
                    placeholder = { Text("Optional", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }

            if (message != null) {
                Text(
                    text = message,
                    color = if (message.startsWith("Error")) StatusError else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onReload,
                    enabled = !isLoading && !isSaving
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reload", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { showConfirmSave = true },
                    enabled = isLoaded && !isLoading && !isSaving && primaryInput.isNotBlank(),
                    shape = CircleShape
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Saving...", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Text("Save DNS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showConfirmSave) {
        AlertDialog(
            onDismissRequest = { showConfirmSave = false },
            title = {
                Text(
                    text = "Confirm DNS Change",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                val secText = if (secondaryInput.isBlank()) "router IP (192.168.1.1)" else secondaryInput.trim()
                Text(
                    text = "Set router DNS to:\n• Primary: ${primaryInput.trim()}\n• Secondary: $secText\n\nThe router will restart its DHCP service for ~30 seconds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmSave = false
                        onSaveDns(primaryInput.trim(), secondaryInput.trim())
                    },
                    shape = CircleShape
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmSave = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

