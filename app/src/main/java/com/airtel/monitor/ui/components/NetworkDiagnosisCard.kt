package com.airtel.monitor.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airtel.monitor.data.model.DiagnosisMode
import com.airtel.monitor.data.model.DiagnosisState
import com.airtel.monitor.ui.theme.StatusError
import com.airtel.monitor.ui.theme.StatusSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosisCard(
    diagnosisState: DiagnosisState,
    onModeChanged: (DiagnosisMode) -> Unit,
    onTargetChanged: (String) -> Unit,
    onPingTimesChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val terminalScrollState = rememberScrollState()

    // Follow new output only while the user is at the bottom. Snapping to the
    // bottom on every update made it impossible to scroll back through results.
    var followOutput by remember { mutableStateOf(true) }
    LaunchedEffect(terminalScrollState.isScrollInProgress) {
        if (!terminalScrollState.isScrollInProgress) {
            followOutput = terminalScrollState.value >= terminalScrollState.maxValue - 24
        }
    }
    // maxValue changes after layout, so this runs once the new line is measured.
    LaunchedEffect(terminalScrollState) {
        snapshotFlow { terminalScrollState.maxValue }.collect { max ->
            if (followOutput) terminalScrollState.scrollTo(max)
        }
    }
    LaunchedEffect(diagnosisState.isRunning) {
        if (diagnosisState.isRunning) followOutput = true
    }

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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkCheck,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Network Diagnosis",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Ping & Traceroute diagnostics",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Status Badge
                Surface(
                    shape = CircleShape,
                    color = if (diagnosisState.isRunning) {
                        StatusSuccess.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (diagnosisState.isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = StatusSuccess
                            )
                        }
                        Text(
                            text = if (diagnosisState.isRunning) "Running" else "Ready",
                            color = if (diagnosisState.isRunning) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Mode Selector (Ping vs Traceroute)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = diagnosisState.mode == DiagnosisMode.PING,
                    onClick = { onModeChanged(DiagnosisMode.PING) },
                    label = { Text("Ping Test") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    shape = CircleShape,
                    enabled = !diagnosisState.isRunning,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                FilterChip(
                    selected = diagnosisState.mode == DiagnosisMode.TRACEROUTE,
                    onClick = { onModeChanged(DiagnosisMode.TRACEROUTE) },
                    label = { Text("Traceroute") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Route,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    shape = CircleShape,
                    enabled = !diagnosisState.isRunning,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            // Target Host Input
            OutlinedTextField(
                value = diagnosisState.target,
                onValueChange = onTargetChanged,
                label = { Text("Target Host / IP", style = MaterialTheme.typography.bodySmall) },
                placeholder = { Text("8.8.8.8 or google.com", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = !diagnosisState.isRunning,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            // Quick Target Suggestions & Ping Packet Count
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quick:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf("8.8.8.8", "1.1.1.1", "google.com").forEach { quickTarget ->
                        SuggestionChip(
                            onClick = { onTargetChanged(quickTarget) },
                            label = { Text(quickTarget, style = MaterialTheme.typography.labelSmall) },
                            shape = CircleShape,
                            enabled = !diagnosisState.isRunning
                        )
                    }
                }

                if (diagnosisState.mode == DiagnosisMode.PING) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Packets:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(4, 10, 20).forEach { count ->
                            FilterChip(
                                selected = diagnosisState.pingTimes == count,
                                onClick = { onPingTimesChanged(count) },
                                label = { Text("$count", style = MaterialTheme.typography.labelSmall) },
                                shape = CircleShape,
                                enabled = !diagnosisState.isRunning,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }
            }

            // Error Message (if any)
            diagnosisState.error?.let { err ->
                Text(
                    text = err,
                    color = StatusError,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (diagnosisState.output.isNotEmpty()) {
                    TextButton(
                        onClick = onClear,
                        enabled = !diagnosisState.isRunning
                    ) {
                        Icon(imageVector = Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (diagnosisState.isRunning) {
                        FilledTonalButton(
                            onClick = onStop,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = CircleShape
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                onStart()
                            },
                            enabled = diagnosisState.target.isNotBlank(),
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = if (diagnosisState.mode == DiagnosisMode.PING) Icons.Default.PlayArrow else Icons.Default.Route,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (diagnosisState.mode == DiagnosisMode.PING) "Start Ping" else "Start Trace",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Monospace Terminal Console Output Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 130.dp, max = 260.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(
                        width = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    // Terminal Top Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(StatusError.copy(alpha = 0.7f)))
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(StatusSuccess.copy(alpha = 0.7f)))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (diagnosisState.mode == DiagnosisMode.PING) "PING CONSOLE" else "TRACEROUTE CONSOLE",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        if (diagnosisState.output.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Diagnosis Output", diagnosisState.output)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Output copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy output",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 0.5.dp
                    )

                    // Logs Scroll Container
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(terminalScrollState)
                    ) {
                        if (diagnosisState.output.isEmpty()) {
                            Text(
                                text = "Enter a host or IP above and tap Start to run diagnostic test directly from the router.",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        } else {
                            Text(
                                text = diagnosisState.output,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
