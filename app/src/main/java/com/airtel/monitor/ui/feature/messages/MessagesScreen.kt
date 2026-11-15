@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.airtel.monitor.ui.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airtel.monitor.R
import com.airtel.monitor.data.model.SmsFolder
import com.airtel.monitor.data.model.SmsMessage
import com.airtel.monitor.ui.designsystem.ConfirmSheet
import com.airtel.monitor.ui.designsystem.ConfirmTier
import com.airtel.monitor.ui.designsystem.EmptyState
import com.airtel.monitor.ui.designsystem.ErrorState
import com.airtel.monitor.ui.designsystem.SkeletonBox
import com.airtel.monitor.ui.theme.AirtelTheme
import com.airtel.monitor.ui.theme.TabularMonospace

private sealed interface PendingSmsConfirm {
    data class Send(val phone: String, val body: String) : PendingSmsConfirm
    data class DeleteOne(val message: SmsMessage) : PendingSmsConfirm
    data object DeleteAll : PendingSmsConfirm
}

@Composable
fun MessagesScreen(
    viewModel: MessagesViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = AirtelTheme.spacing
    val colors = AirtelTheme.colors
    var pendingConfirm by remember { mutableStateOf<PendingSmsConfirm?>(null) }

    LaunchedEffect(Unit) {
        viewModel.ensureLoadedOnOpen()
    }

    val page = uiState.visiblePage
    val isStorageFull = (uiState.activeFolder == SmsFolder.INBOX && page.isReceiveFull) ||
        (uiState.activeFolder == SmsFolder.OUTBOX && page.isSendFull)

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.messages_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.unreadCount > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.tile_messages_unread_fmt, uiState.unreadCount, uiState.unreadCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (uiState.isLoading || uiState.isBusy) {
                LoadingIndicator(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(spacing.xs))
            }
            if (uiState.ussdAvailable) {
                IconButton(
                    onClick = viewModel::openUssdSheet,
                    enabled = !uiState.isBusy
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Dialpad,
                        contentDescription = stringResource(R.string.messages_ussd_btn)
                    )
                }
            }
            IconButton(
                onClick = { viewModel.openCompose() },
                enabled = uiState.settings.smsEnabled && !uiState.isBusy
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddComment,
                    contentDescription = stringResource(R.string.messages_compose)
                )
            }
            IconButton(
                onClick = viewModel::openSettingsSheet,
                enabled = !uiState.isBusy
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.cd_sms_settings)
                )
            }
            IconButton(
                onClick = viewModel::refresh,
                enabled = !uiState.isLoading && !uiState.isBusy
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh_settings)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            item(key = "folder_tabs") {
                val folders = SmsFolder.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    folders.forEachIndexed { idx, folder ->
                        SegmentedButton(
                            selected = uiState.activeFolder == folder,
                            onClick = { viewModel.selectFolder(folder) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = folders.size)
                        ) {
                            Text(
                                text = when (folder) {
                                    SmsFolder.INBOX -> stringResource(R.string.messages_inbox_tab)
                                    SmsFolder.OUTBOX -> stringResource(R.string.messages_outbox_tab)
                                }
                            )
                        }
                    }
                }
            }

            if (!uiState.settings.smsEnabled) {
                item(key = "sms_disabled") {
                    Surface(
                        shape = AirtelTheme.shapes.tile,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.messages_disabled_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(spacing.md)
                        )
                    }
                }
            }

            if (isStorageFull) {
                item(key = "storage_full") {
                    Surface(
                        shape = AirtelTheme.shapes.tile,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = stringResource(R.string.messages_storage_full_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            if (page.messages.isNotEmpty()) {
                item(key = "folder_actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        if (uiState.activeFolder == SmsFolder.INBOX &&
                            (uiState.unreadCount > 0 || page.messages.any { !it.isRead })
                        ) {
                            FilledTonalButton(
                                onClick = viewModel::markAllRead,
                                enabled = !uiState.isBusy,
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(spacing.xs))
                                Text(stringResource(R.string.messages_mark_all_read))
                            }
                        }
                        OutlinedButton(
                            onClick = { pendingConfirm = PendingSmsConfirm.DeleteAll },
                            enabled = !uiState.isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(spacing.xs))
                            Text(stringResource(R.string.messages_delete_all))
                        }
                    }
                }
            }

            if (uiState.isLoading && !uiState.loadedOnce) {
                items(3, key = { "skeleton_$it" }) {
                    SkeletonBox(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs))
                }
            } else if (uiState.error != null && page.messages.isEmpty()) {
                item(key = "error") {
                    ErrorState(
                        title = stringResource(R.string.messages_title),
                        message = uiState.error!!,
                        onRetry = viewModel::refresh
                    )
                }
            } else if (page.messages.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.MailOutline,
                        title = stringResource(
                            if (uiState.activeFolder == SmsFolder.INBOX) {
                                R.string.messages_empty_inbox_title
                            } else {
                                R.string.messages_empty_outbox_title
                            }
                        ),
                        message = stringResource(R.string.messages_empty_desc),
                        actionLabel = stringResource(R.string.messages_compose),
                        onAction = { viewModel.openCompose() }
                    )
                }
            } else {
                items(page.messages, key = { "${it.folder.name}_${it.index}" }) { msg ->
                    SmsMessageCard(
                        message = msg,
                        onClick = { viewModel.openMessage(msg) }
                    )
                }

                item(key = "pagination") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { viewModel.goToPage(page.pageNumber - 1) },
                            enabled = page.pageNumber > 1 && !uiState.isLoading && !uiState.isBusy,
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text(stringResource(R.string.messages_prev_page))
                        }
                        Text(
                            text = stringResource(R.string.messages_page_fmt, page.pageNumber, page.totalCount),
                            style = MaterialTheme.typography.labelMedium.merge(TabularMonospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val loadedUpTo = page.pageNumber * page.messages.size.coerceAtLeast(1)
                        val hasNext = page.messages.isNotEmpty() && loadedUpTo < page.totalCount
                        TextButton(
                            onClick = { viewModel.goToPage(page.pageNumber + 1) },
                            enabled = hasNext && !uiState.isLoading && !uiState.isBusy,
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text(stringResource(R.string.messages_next_page))
                        }
                    }
                }
            }

            if (!uiState.statusNote.isNullOrBlank()) {
                item(key = "status_note") {
                    val isErr = uiState.statusNote!!.startsWith("Error:")
                    Text(
                        text = uiState.statusNote!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isErr) colors.error else colors.success
                    )
                }
            }

            if (uiState.flagsLoaded && !uiState.ussdAvailable) {
                item(key = "ussd_disabled_note") {
                    Text(
                        text = stringResource(R.string.messages_ussd_disabled_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = spacing.xs)
                    )
                }
            }
        }
    }

    uiState.selectedMessage?.let { msg ->
        MessageDetailSheet(
            message = msg,
            isBusy = uiState.isBusy,
            onDismiss = viewModel::dismissMessage,
            onReply = { phone -> viewModel.openCompose(initialPhone = phone) },
            onForward = { body -> viewModel.openCompose(initialBody = body) },
            onDeleteRequest = { target ->
                viewModel.dismissMessage()
                pendingConfirm = PendingSmsConfirm.DeleteOne(target)
            }
        )
    }

    if (uiState.showComposeSheet) {
        ComposeSmsSheet(
            initialPhone = uiState.composeInitialPhone,
            initialBody = uiState.composeInitialBody,
            maxWeightedLength = uiState.settings.maxLength,
            isBusy = uiState.isBusy,
            serverMessage = uiState.statusNote,
            onDismiss = viewModel::dismissCompose,
            onRequestSend = { phone, body ->
                pendingConfirm = PendingSmsConfirm.Send(phone, body)
            }
        )
    }

    if (uiState.showSettingsSheet) {
        SmsSettingsSheet(
            settings = uiState.settings,
            isBusy = uiState.isBusy,
            serverMessage = uiState.statusNote,
            onDismiss = viewModel::dismissSettingsSheet,
            onSave = viewModel::saveSmsSettings
        )
    }

    if (uiState.showUssdSheet) {
        UssdDialerSheet(
            isBusy = uiState.ussdBusy,
            responseText = uiState.ussdResponseText,
            needReply = uiState.ussdNeedReply,
            errorMessage = uiState.ussdError,
            shortcuts = uiState.ussdShortcuts,
            onDismiss = viewModel::dismissUssdSheet,
            onSendUssd = viewModel::sendUssd,
            onSendPreset = viewModel::sendUssdPreset,
            onCancelSession = viewModel::cancelUssdSession,
            onAddShortcut = viewModel::addUssdShortcut,
            onRemoveShortcut = viewModel::removeUssdShortcut
        )
    }

    pendingConfirm?.let { confirm ->
        when (confirm) {
            is PendingSmsConfirm.Send -> ConfirmSheet(
                title = stringResource(R.string.messages_send_confirm_title),
                impactMessage = stringResource(R.string.messages_send_confirm_impact, confirm.phone),
                confirmLabel = stringResource(R.string.messages_send_btn),
                onConfirm = {
                    pendingConfirm = null
                    viewModel.sendSms(confirm.phone, confirm.body)
                },
                onDismiss = { pendingConfirm = null },
                tier = ConfirmTier.CONNECTIVITY
            )
            is PendingSmsConfirm.DeleteOne -> ConfirmSheet(
                title = stringResource(R.string.messages_delete_confirm_title),
                impactMessage = stringResource(R.string.messages_delete_confirm_impact),
                confirmLabel = stringResource(R.string.messages_delete),
                onConfirm = {
                    pendingConfirm = null
                    viewModel.deleteMessage(confirm.message)
                },
                onDismiss = { pendingConfirm = null },
                tier = ConfirmTier.CONNECTIVITY
            )
            PendingSmsConfirm.DeleteAll -> ConfirmSheet(
                title = stringResource(R.string.messages_delete_all_confirm_title),
                impactMessage = stringResource(R.string.messages_delete_all_confirm_impact),
                confirmLabel = stringResource(R.string.messages_delete_all),
                onConfirm = {
                    pendingConfirm = null
                    viewModel.deleteAllInCurrentFolder()
                },
                onDismiss = { pendingConfirm = null },
                tier = ConfirmTier.CONNECTIVITY
            )
        }
    }
}

@Composable
private fun SmsMessageCard(
    message: SmsMessage,
    onClick: () -> Unit
) {
    val spacing = AirtelTheme.spacing
    val unread = message.folder == SmsFolder.INBOX && !message.isRead

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AirtelTheme.shapes.card)
            .clickable(onClick = onClick),
        shape = AirtelTheme.shapes.card,
        color = if (unread) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                if (unread) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Text(
                    text = message.phoneNumber,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (message.timestamp.isNotBlank()) {
                    Text(
                        text = message.timestamp,
                        style = MaterialTheme.typography.labelSmall.merge(TabularMonospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = message.text.ifBlank { "-" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
