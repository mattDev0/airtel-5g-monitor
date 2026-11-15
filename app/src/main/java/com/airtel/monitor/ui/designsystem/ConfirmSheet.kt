@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.airtel.monitor.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.ui.theme.AirtelTheme

enum class ConfirmTier {
    /** Tier 2: connectivity change (explains what drops). */
    CONNECTIVITY,
    /** Tier 3: destructive action (requires typing confirmation token). */
    DESTRUCTIVE
}

@Composable
fun ConfirmSheet(
    title: String,
    impactMessage: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tier: ConfirmTier = ConfirmTier.CONNECTIVITY,
    requiredConfirmationText: String? = null
) {
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    var typedInput by remember { mutableStateOf("") }

    val expectedText = if (tier == ConfirmTier.DESTRUCTIVE) {
        requiredConfirmationText?.takeIf { it.isNotBlank() } ?: "ZLT X17M"
    } else null

    val confirmEnabled = expectedText == null ||
        typedInput.trim().equals(expectedText.trim(), ignoreCase = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = if (tier == ConfirmTier.DESTRUCTIVE) colors.error else colors.warning,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Surface(
                shape = AirtelTheme.shapes.tile,
                color = if (tier == ConfirmTier.DESTRUCTIVE) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Text(
                    text = impactMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tier == ConfirmTier.DESTRUCTIVE) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.md)
                )
            }

            if (expectedText != null) {
                Text(
                    text = stringResource(R.string.confirm_type_prompt, expectedText),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = typedInput,
                    onValueChange = { typedInput = it },
                    placeholder = { Text(stringResource(R.string.confirm_type_placeholder, expectedText)) },
                    singleLine = true,
                    shape = AirtelTheme.shapes.input,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(spacing.sm))
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    shapes = ButtonDefaults.shapes(),
                    colors = if (tier == ConfirmTier.DESTRUCTIVE) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(confirmLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
