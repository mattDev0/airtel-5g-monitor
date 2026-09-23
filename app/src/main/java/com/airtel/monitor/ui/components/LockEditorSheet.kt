@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.airtel.monitor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.airtel.monitor.data.model.BandLockConfig
import com.airtel.monitor.data.model.CellLockConfig
import com.airtel.monitor.data.model.LockSettings
import com.airtel.monitor.data.model.LockState
import com.airtel.monitor.data.model.LteLockCell
import com.airtel.monitor.data.model.NrLockCell
import com.airtel.monitor.ui.theme.StatusError
import com.airtel.monitor.ui.theme.StatusSuccess
import com.airtel.monitor.ui.theme.StatusWarning

private enum class LockTab { BANDS, CELLS }

/** Band lock and local physical cell lock, mirroring the web UI's Advanced Settings tabs. */
@Composable
fun LockEditorSheet(
    settings: LockSettings?,
    networkType: String,
    isLoading: Boolean,
    isSaving: Boolean,
    message: String?,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onSaveBands: (lock4g: Boolean, bands4g: Set<Int>, lock5g: Boolean, bands5g: Set<Int>) -> Unit,
    onSaveLte: (enabled: Boolean, cells: List<LteLockCell>) -> Unit,
    onSaveNr: (enabled: Boolean, cells: List<NrLockCell>) -> Unit
) {
    var tab by remember { mutableStateOf(LockTab.CELLS) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CellTower, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Network Locks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (isLoading || isSaving) LoadingIndicator(modifier = Modifier.size(28.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
            ) {
                ToggleButton(
                    checked = tab == LockTab.CELLS,
                    onCheckedChange = { tab = LockTab.CELLS },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton }
                ) { Text("Cell Lock") }
                ToggleButton(
                    checked = tab == LockTab.BANDS,
                    onCheckedChange = { tab = LockTab.BANDS },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton }
                ) { Text("Band Lock") }
            }

            message?.let {
                val failed = !it.contains("confirmed")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = (if (failed) StatusWarning else StatusSuccess).copy(alpha = 0.15f)
                ) {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            when {
                settings == null && isLoading -> Text("Reading lock settings…", style = MaterialTheme.typography.bodyMedium)
                settings == null -> TextButton(onClick = onReload) { Text("Couldn't read the router. Retry") }
                tab == LockTab.CELLS -> CellLockTab(settings.cells, networkType, isSaving, onSaveLte, onSaveNr)
                else -> BandLockTab(settings.bands, networkType, isSaving, onSaveBands)
            }
        }
    }
}

// ---- Band lock ----

@Composable
private fun BandLockTab(
    config: BandLockConfig,
    networkType: String,
    isSaving: Boolean,
    onSave: (Boolean, Set<Int>, Boolean, Set<Int>) -> Unit
) {
    var lock4g by remember(config) { mutableStateOf(config.lock4gEnabled) }
    var bands4g by remember(config) { mutableStateOf(config.locked4g.ifEmpty { config.supported4g.toSet() }) }
    var lock5g by remember(config) { mutableStateOf(config.lock5gEnabled) }
    var bands5g by remember(config) { mutableStateOf(config.locked5g.ifEmpty { config.supported5g.toSet() }) }
    var confirm by remember { mutableStateOf(false) }

    Hint("Only the ticked bands are allowed while a lock is on. Turning a lock off lets the router use every band it supports.")

    BandSection(
        title = "4G bands",
        enabled = lock4g,
        onEnabledChange = { lock4g = it },
        supported = config.supported4g,
        selected = bands4g,
        label = { "B$it" },
        onToggle = { b -> bands4g = if (b in bands4g) bands4g - b else bands4g + b }
    )

    BandSection(
        title = "5G bands",
        enabled = lock5g,
        onEnabledChange = { lock5g = it },
        supported = config.supported5g,
        selected = bands5g,
        label = { "n$it" },
        onToggle = { b -> bands5g = if (b in bands5g) bands5g - b else bands5g + b }
    )
    if (networkType.contains("NSA", ignoreCase = true)) {
        Hint("You're on 5G NSA. The router notes that a 5G band lock only takes effect in SA mode.")
    }

    Button(
        onClick = { confirm = true },
        enabled = !isSaving && (!lock4g || bands4g.isNotEmpty()) && (!lock5g || bands5g.isNotEmpty()),
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save band lock") }

    if (confirm) {
        ConfirmApply(
            text = "The modem re-registers with the network, so the internet can drop for a few seconds.",
            onConfirm = { confirm = false; onSave(lock4g, bands4g, lock5g, bands5g) },
            onDismiss = { confirm = false }
        )
    }
}

@Composable
private fun BandSection(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    supported: List<Int>,
    selected: Set<Int>,
    label: (Int) -> String,
    onToggle: (Int) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (enabled) "Locked to ${selected.size} of ${supported.size}" else "All bands allowed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (supported.isEmpty()) {
                Text("The router didn't report any supported bands.", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                supported.forEach { band ->
                    FilterChip(
                        selected = band in selected,
                        onClick = { onToggle(band) },
                        enabled = enabled,
                        label = { Text(label(band), fontFamily = FontFamily.Monospace) },
                        leadingIcon = if (band in selected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null
                    )
                }
            }
        }
    }
}

// ---- Cell lock ----

@Composable
private fun CellLockTab(
    config: CellLockConfig,
    networkType: String,
    isSaving: Boolean,
    onSaveLte: (Boolean, List<LteLockCell>) -> Unit,
    onSaveNr: (Boolean, List<NrLockCell>) -> Unit
) {
    var lteOn by remember(config) { mutableStateOf(config.lteEnabled) }
    var lteCells by remember(config) { mutableStateOf(config.lteCells) }
    var nrOn by remember(config) { mutableStateOf(config.nrEnabled) }
    var nrCells by remember(config) { mutableStateOf(config.nrCells) }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }

    if (networkType.contains("NSA", ignoreCase = true)) {
        Hint("You're on 5G NSA: lock 4G and 5G together, because the 5G link rides on the 4G anchor cell.")
    }
    Hint("If a lock leaves the router without signal, it's still reachable over Wi-Fi, so you can unlock it here.")

    CellSection(
        title = "4G cell",
        state = config.lteState,
        enabled = lteOn,
        onEnabledChange = { lteOn = it },
        current = config.current4g?.let { "Now on EARFCN ${it.earfcn} · PCI ${it.pci}" },
        rows = lteCells.map { "EARFCN ${it.earfcn}" to "PCI ${it.pci.ifEmpty { "any" }}" },
        onRemove = { i -> lteCells = lteCells.filterIndexed { j, _ -> j != i } },
        onUseCurrent = config.current4g?.takeIf { it !in lteCells }?.let { cur -> { lteCells = lteCells + cur } },
        addFields = listOf("EARFCN", "PCI (optional)"),
        onAdd = { v -> lteCells = lteCells + LteLockCell(v[0], v[1]) },
        saveLabel = "Apply 4G lock",
        saveEnabled = !isSaving && (!lteOn || lteCells.isNotEmpty()),
        onSave = { confirm = { onSaveLte(lteOn, lteCells) } }
    )

    CellSection(
        title = "5G cell",
        state = config.nrState,
        enabled = nrOn,
        onEnabledChange = { nrOn = it },
        current = config.current5g?.let { "Now on n${it.band} · ARFCN ${it.arfcn} · PCI ${it.pci}" },
        rows = nrCells.map { "n${it.band} · ARFCN ${it.arfcn}" to "PCI ${it.pci.ifEmpty { "any" }}" },
        onRemove = { i -> nrCells = nrCells.filterIndexed { j, _ -> j != i } },
        onUseCurrent = config.current5g?.takeIf { it !in nrCells }?.let { cur -> { nrCells = nrCells + cur } },
        addFields = listOf("Band (e.g. 78)", "ARFCN", "PCI (optional)"),
        onAdd = { v -> nrCells = nrCells + NrLockCell(v[0], v[1], v[2]) },
        saveLabel = "Apply 5G lock",
        saveEnabled = !isSaving && (!nrOn || nrCells.isNotEmpty()),
        onSave = { confirm = { onSaveNr(nrOn, nrCells) } }
    )

    confirm?.let { action ->
        ConfirmApply(
            text = "The modem re-attaches to the network, so the internet can drop for a few seconds. Locking to a cell it can't hear leaves it without service until you change the lock.",
            onConfirm = { confirm = null; action() },
            onDismiss = { confirm = null }
        )
    }
}

@Composable
private fun CellSection(
    title: String,
    state: LockState,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    current: String?,
    rows: List<Pair<String, String>>,
    onRemove: (Int) -> Unit,
    onUseCurrent: (() -> Unit)?,
    addFields: List<String>,
    onAdd: (List<String>) -> Unit,
    saveLabel: String,
    saveEnabled: Boolean,
    onSave: () -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    val inputs = remember(addFields) { mutableStateListOf(*Array(addFields.size) { "" }) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val (text, color) = when (state) {
                            LockState.LOCKED -> "Locked" to StatusSuccess
                            LockState.FAILED -> "Lock failed" to StatusError
                            LockState.UNLOCKED -> "Auto" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
                            Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    current?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (enabled) {
                rows.forEachIndexed { i, (main, sub) ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(main, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text(sub, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = { onRemove(i) }) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onUseCurrent?.let {
                        FilledTonalButton(onClick = it, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Use current cell")
                        }
                    }
                    OutlinedButton(onClick = { adding = !adding }, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Add manually")
                    }
                }

                if (adding) {
                    addFields.forEachIndexed { i, label ->
                        OutlinedTextField(
                            value = inputs[i],
                            onValueChange = { v -> inputs[i] = v.filter { it.isDigit() } },
                            label = { Text(label) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val required = addFields.indices.filter { !addFields[it].contains("optional") }
                    TextButton(
                        onClick = {
                            onAdd(inputs.toList())
                            for (i in inputs.indices) inputs[i] = ""
                            adding = false
                        },
                        enabled = required.all { inputs[it].isNotBlank() }
                    ) { Text("Add to list") }
                }
            }

            Button(onClick = onSave, enabled = saveEnabled, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                Text(if (enabled) saveLabel else "Unlock (router picks the cell)")
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfirmApply(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply to router?") },
        text = { Text(text) },
        confirmButton = { Button(onClick = onConfirm, shapes = ButtonDefaults.shapes()) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
