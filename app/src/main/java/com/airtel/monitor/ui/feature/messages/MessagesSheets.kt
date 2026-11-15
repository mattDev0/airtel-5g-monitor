@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.airtel.monitor.ui.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.airtel.monitor.R
import com.airtel.monitor.core.util.SmsValidators
import com.airtel.monitor.data.model.SmsFolder
import com.airtel.monitor.data.model.SmsMessage
import com.airtel.monitor.data.model.SmsSettingsConfig
import com.airtel.monitor.ui.designsystem.SettingSwitchRow
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

@Composable
internal fun MessageDetailSheet(
    message: SmsMessage,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onReply: (String) -> Unit,
    onForward: (String) -> Unit,
    onDeleteRequest: (SmsMessage) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = message.phoneNumber,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (message.timestamp.isNotBlank()) {
                    Text(
                        text = message.timestamp,
                        style = MaterialTheme.typography.labelMedium.merge(TabularMonospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                shape = AirtelTheme.shapes.tile,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message.text.ifBlank { "-" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(spacing.lg)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                if (message.folder == SmsFolder.INBOX) {
                    FilledTonalButton(
                        onClick = { onReply(message.phoneNumber) },
                        enabled = !isBusy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null)
                        Spacer(Modifier.width(spacing.xs))
                        Text(stringResource(R.string.messages_reply))
                    }
                }
                OutlinedButton(
                    onClick = { onForward(message.text) },
                    enabled = !isBusy,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    Spacer(Modifier.width(spacing.xs))
                    Text(stringResource(R.string.messages_forward))
                }
            }

            OutlinedButton(
                onClick = { onDeleteRequest(message) },
                enabled = !isBusy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(spacing.xs))
                Text(stringResource(R.string.messages_delete))
            }
        }
    }
}

@Composable
internal fun ComposeSmsSheet(
    initialPhone: String,
    initialBody: String,
    maxWeightedLength: Int,
    isBusy: Boolean,
    serverMessage: String?,
    onDismiss: () -> Unit,
    onRequestSend: (phone: String, body: String) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var phone by remember(initialPhone) { mutableStateOf(initialPhone) }
    var body by remember(initialBody) { mutableStateOf(initialBody) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val effectiveLimit = maxWeightedLength.coerceIn(1, 4096)
    val weightedCount = SmsValidators.weightedSmsLength(body)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Text(
                text = stringResource(R.string.messages_compose),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it
                    validationError = null
                },
                label = { Text(stringResource(R.string.messages_recipient_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                shape = AirtelTheme.shapes.input
            )

            OutlinedTextField(
                value = body,
                onValueChange = {
                    body = it
                    validationError = null
                },
                label = { Text(stringResource(R.string.messages_body_label)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                shape = AirtelTheme.shapes.input
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = stringResource(R.string.messages_length_fmt, weightedCount, effectiveLimit),
                    style = MaterialTheme.typography.labelSmall.merge(TabularMonospace),
                    color = if (weightedCount > effectiveLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            val err = validationError ?: serverMessage?.takeIf { it.startsWith("Error:") }
            if (err != null) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isBusy,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(spacing.sm))
                Button(
                    onClick = {
                        val phoneErr = SmsValidators.validatePhoneNumber(phone)
                        if (phoneErr != null) {
                            validationError = phoneErr
                            return@Button
                        }
                        val bodyErr = SmsValidators.validateSmsBody(body, effectiveLimit)
                        if (bodyErr != null) {
                            validationError = bodyErr
                            return@Button
                        }
                        onRequestSend(phone.trim(), body)
                    },
                    enabled = !isBusy,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.messages_send_btn))
                }
            }
        }
    }
}

@Composable
internal fun SmsSettingsSheet(
    settings: SmsSettingsConfig,
    isBusy: Boolean,
    serverMessage: String?,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, smscNumber: String, maxSize: Int) -> Unit
) {
    val spacing = AirtelTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var smsEnabled by remember(settings) { mutableStateOf(settings.smsEnabled) }
    var smscNumber by remember(settings) { mutableStateOf(settings.smscNumber) }
    var maxSizeText by remember(settings) { mutableStateOf(settings.maxSize.toString()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AirtelTheme.shapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Text(
                text = stringResource(R.string.messages_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            SettingSwitchRow(
                title = stringResource(R.string.messages_settings_enable),
                subtitle = stringResource(R.string.messages_settings_enable_desc),
                checked = smsEnabled,
                enabled = !isBusy,
                onCheckedChange = { smsEnabled = it }
            )

            OutlinedTextField(
                value = maxSizeText,
                onValueChange = {
                    maxSizeText = it
                    validationError = null
                },
                label = { Text(stringResource(R.string.messages_settings_max_size)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = AirtelTheme.shapes.input
            )

            OutlinedTextField(
                value = smscNumber,
                onValueChange = {
                    smscNumber = it
                    validationError = null
                },
                enabled = settings.smscEditable && !isBusy,
                label = { Text(stringResource(R.string.messages_settings_smsc)) },
                supportingText = if (!settings.smscEditable) {
                    { Text(stringResource(R.string.messages_settings_smsc_locked)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                shape = AirtelTheme.shapes.input
            )

            val err = validationError ?: serverMessage?.takeIf { it.startsWith("Error:") }
            if (err != null) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isBusy,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.width(spacing.sm))
                Button(
                    onClick = {
                        val sizeErr = SmsValidators.validateSmsMaxSize(maxSizeText)
                        if (sizeErr != null) {
                            validationError = sizeErr
                            return@Button
                        }
                        if (settings.smscEditable) {
                            val smscErr = SmsValidators.validateSmscNumber(smscNumber, required = false)
                            if (smscErr != null) {
                                validationError = smscErr
                                return@Button
                            }
                        }
                        onSave(smsEnabled, smscNumber.trim(), maxSizeText.trim().toInt())
                    },
                    enabled = !isBusy,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
