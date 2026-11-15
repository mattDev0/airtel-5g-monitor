package com.airtel.monitor.ui.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.airtel.monitor.core.di.AppContainer
import com.airtel.monitor.core.network.RouterResult
import com.airtel.monitor.core.network.errorMessage
import com.airtel.monitor.data.model.SmsFolder
import com.airtel.monitor.data.model.SmsMessage
import com.airtel.monitor.data.model.SmsPage
import com.airtel.monitor.data.model.SmsSettingsConfig
import com.airtel.monitor.data.repository.SmsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MessagesUiState(
    val activeFolder: SmsFolder = SmsFolder.INBOX,
    val inboxPage: SmsPage = SmsPage(folder = SmsFolder.INBOX),
    val outboxPage: SmsPage = SmsPage(folder = SmsFolder.OUTBOX),
    val settings: SmsSettingsConfig = SmsSettingsConfig(),
    val unreadCount: Int = 0,
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val loadedOnce: Boolean = false,
    val error: String? = null,
    val statusNote: String? = null,
    val selectedMessage: SmsMessage? = null,
    val showComposeSheet: Boolean = false,
    val composeInitialPhone: String = "",
    val composeInitialBody: String = "",
    val showSettingsSheet: Boolean = false
) {
    val visiblePage: SmsPage
        get() = if (activeFolder == SmsFolder.INBOX) inboxPage else outboxPage
}

class MessagesViewModel internal constructor(
    private val smsRepository: SmsRepository,
    unreadCountFlow: Flow<Int>,
    private val emitStatusMessage: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor(container: AppContainer) : this(
        smsRepository = container.smsRepository,
        unreadCountFlow = container.statusRepository.smsUnreadCount,
        emitStatusMessage = container::emitStatusMessage
    )

    private val local = MutableStateFlow(LocalMessagesState())
    private var initialLoadStarted = false

    private val repoFlow = combine(
        smsRepository.inboxPage,
        smsRepository.outboxPage,
        smsRepository.settings,
        unreadCountFlow
    ) { inbox, outbox, cfg, unread ->
        RepoSlice(inbox, outbox, cfg, unread)
    }

    val uiState: StateFlow<MessagesUiState> = combine(
        repoFlow,
        local
    ) { repo, loc ->
        MessagesUiState(
            activeFolder = loc.activeFolder,
            inboxPage = repo.inbox ?: SmsPage(folder = SmsFolder.INBOX),
            outboxPage = repo.outbox ?: SmsPage(folder = SmsFolder.OUTBOX),
            settings = repo.settings ?: SmsSettingsConfig(),
            unreadCount = repo.unreadCount,
            isLoading = loc.isLoading,
            isBusy = loc.isBusy,
            loadedOnce = loc.loadedOnce || repo.inbox != null || repo.outbox != null,
            error = loc.error,
            statusNote = loc.statusNote,
            selectedMessage = loc.selectedMessage,
            showComposeSheet = loc.showComposeSheet,
            composeInitialPhone = loc.composeInitialPhone,
            composeInitialBody = loc.composeInitialBody,
            showSettingsSheet = loc.showSettingsSheet
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MessagesUiState())

    fun ensureLoadedOnOpen() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        loadSettings()
        loadFolderPage(local.value.activeFolder, page = 1)
    }

    fun selectFolder(folder: SmsFolder) {
        local.update { it.copy(activeFolder = folder, error = null, statusNote = null) }
        val existing = if (folder == SmsFolder.INBOX) smsRepository.inboxPage.value else smsRepository.outboxPage.value
        val page = existing?.pageNumber ?: 1
        loadFolderPage(folder, page)
    }

    fun refresh() {
        val cur = uiState.value
        loadFolderPage(cur.activeFolder, cur.visiblePage.pageNumber)
        loadSettings()
    }

    fun goToPage(page: Int) {
        val safePage = page.coerceAtLeast(1)
        loadFolderPage(local.value.activeFolder, safePage)
    }

    private fun loadSettings() {
        viewModelScope.launch(ioDispatcher) {
            smsRepository.loadSmsSettings()
        }
    }

    private fun loadFolderPage(folder: SmsFolder, page: Int) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isLoading = true, error = null) }
            when (val res = smsRepository.loadMessages(folder, page)) {
                is RouterResult.Ok -> {
                    local.update { it.copy(isLoading = false, loadedOnce = true, error = null) }
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(isLoading = false, error = err) }
                }
            }
        }
    }

    fun openMessage(message: SmsMessage) {
        local.update { it.copy(selectedMessage = message) }
        if (message.folder == SmsFolder.INBOX && !message.isRead) {
            viewModelScope.launch(ioDispatcher) {
                val res = smsRepository.markRead(listOf(message.index))
                if (res is RouterResult.Ok) {
                    local.update { state ->
                        if (state.selectedMessage?.index == message.index) {
                            state.copy(selectedMessage = state.selectedMessage.copy(isRead = true))
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    fun dismissMessage() {
        local.update { it.copy(selectedMessage = null) }
    }

    fun markAllRead() {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isBusy = true, statusNote = null) }
            when (val res = smsRepository.markAllRead()) {
                is RouterResult.Ok -> {
                    smsRepository.loadMessages(SmsFolder.INBOX, uiState.value.inboxPage.pageNumber)
                    val msg = "All inbox messages marked read"
                    local.update { it.copy(isBusy = false, statusNote = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(isBusy = false, statusNote = "Error: $err") }
                }
            }
        }
    }

    fun openCompose(initialPhone: String = "", initialBody: String = "") {
        local.update {
            it.copy(
                selectedMessage = null,
                showComposeSheet = true,
                composeInitialPhone = initialPhone,
                composeInitialBody = initialBody,
                statusNote = null
            )
        }
    }

    fun dismissCompose() {
        local.update { it.copy(showComposeSheet = false) }
    }

    fun sendSms(phoneNumber: String, content: String) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isBusy = true, statusNote = null) }
            when (val res = smsRepository.sendSms(phoneNumber, content)) {
                is RouterResult.Ok -> {
                    smsRepository.loadMessages(SmsFolder.OUTBOX, page = 1)
                    val msg = "SMS sent to ${phoneNumber.trim()}"
                    local.update {
                        it.copy(
                            isBusy = false,
                            showComposeSheet = false,
                            statusNote = msg
                        )
                    }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(isBusy = false, statusNote = "Error: $err") }
                }
            }
        }
    }

    fun deleteMessage(message: SmsMessage) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isBusy = true, selectedMessage = null, statusNote = null) }
            when (val res = smsRepository.deleteMessages(message.folder, listOf(message.index))) {
                is RouterResult.Ok -> {
                    val currentPage = uiState.value.visiblePage.pageNumber
                    val reload = smsRepository.loadMessages(message.folder, currentPage)
                    if (reload is RouterResult.Ok && reload.data.messages.isEmpty() && currentPage > 1) {
                        smsRepository.loadMessages(message.folder, currentPage - 1)
                    }
                    val msg = "Message deleted"
                    local.update { it.copy(isBusy = false, statusNote = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(isBusy = false, statusNote = "Error: $err") }
                }
            }
        }
    }

    fun deleteAllInCurrentFolder() {
        val folder = local.value.activeFolder
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isBusy = true, selectedMessage = null, statusNote = null) }
            when (val res = smsRepository.deleteAll(folder)) {
                is RouterResult.Ok -> {
                    smsRepository.loadMessages(folder, page = 1)
                    val msg = if (folder == SmsFolder.INBOX) "Inbox cleared" else "Outbox cleared"
                    local.update { it.copy(isBusy = false, statusNote = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(isBusy = false, statusNote = "Error: $err") }
                }
            }
        }
    }

    fun openSettingsSheet() {
        local.update { it.copy(showSettingsSheet = true, statusNote = null) }
        loadSettings()
    }

    fun dismissSettingsSheet() {
        local.update { it.copy(showSettingsSheet = false) }
    }

    fun saveSmsSettings(smsEnabled: Boolean, smscNumber: String, maxSize: Int) {
        viewModelScope.launch(ioDispatcher) {
            local.update { it.copy(isBusy = true, statusNote = null) }
            when (val res = smsRepository.saveSmsSettings(smsEnabled, smscNumber, maxSize)) {
                is RouterResult.Ok -> {
                    val msg = "SMS settings saved"
                    local.update { it.copy(isBusy = false, showSettingsSheet = false, statusNote = msg) }
                    emitStatusMessage(msg)
                }
                else -> {
                    val err = (res as? RouterResult.LimitedAccess)?.message ?: res.errorMessage()
                    local.update { it.copy(isBusy = false, statusNote = "Error: $err") }
                }
            }
        }
    }

    private data class RepoSlice(
        val inbox: SmsPage?,
        val outbox: SmsPage?,
        val settings: SmsSettingsConfig?,
        val unreadCount: Int
    )

    private data class LocalMessagesState(
        val activeFolder: SmsFolder = SmsFolder.INBOX,
        val isLoading: Boolean = false,
        val isBusy: Boolean = false,
        val loadedOnce: Boolean = false,
        val error: String? = null,
        val statusNote: String? = null,
        val selectedMessage: SmsMessage? = null,
        val showComposeSheet: Boolean = false,
        val composeInitialPhone: String = "",
        val composeInitialBody: String = "",
        val showSettingsSheet: Boolean = false
    )

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MessagesViewModel(container) }
        }
    }
}
