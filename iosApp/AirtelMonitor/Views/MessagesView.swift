import SwiftUI

public struct MessagesView: View {
    @ObservedObject public var container: AppContainer
    @StateObject private var viewModel: MessagesViewModel
    @State private var confirmDeleteAll = false

    public init(container: AppContainer) {
        self.container = container
        _viewModel = StateObject(wrappedValue: MessagesViewModel(container: container))
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Picker("Folder", selection: Binding(
                    get: { viewModel.activeFolder },
                    set: { viewModel.selectFolder($0) }
                )) {
                    ForEach(SmsFolder.allCases) { folder in
                        Text(folder.label).tag(folder)
                    }
                }
                .pickerStyle(.segmented)

                if !viewModel.settings.smsEnabled {
                    SectionCard(tint: AirtelTheme.warning) {
                        HStack {
                            Image(systemName: "envelope.badge.shield.half.filled")
                                .foregroundStyle(AirtelTheme.warning)
                            Text("SMS service is disabled on the router. Enable it in SMS Settings to receive or send messages.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if viewModel.visiblePage.isReceiveFull && viewModel.activeFolder == .inbox {
                    SectionCard(tint: AirtelTheme.error) {
                        Text("Router SMS inbox is full. Delete older messages to receive new ones.")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AirtelTheme.error)
                    }
                }

                if let err = viewModel.error, !err.isEmpty {
                    ErrorStateView(title: "Messages Error", message: err) {
                        viewModel.refresh()
                    }
                } else if viewModel.isLoading && viewModel.visiblePage.messages.isEmpty {
                    SkeletonBox(height: 90)
                    SkeletonBox(height: 90)
                } else if viewModel.visiblePage.messages.isEmpty {
                    EmptyStateView(
                        icon: "tray",
                        title: "No Messages",
                        message: "No SMS messages in \(viewModel.activeFolder.label).",
                        actionTitle: "Refresh"
                    ) {
                        viewModel.refresh()
                    }
                } else {
                    if viewModel.activeFolder == .inbox && viewModel.unreadCount > 0 {
                        HStack {
                            Text("\(viewModel.unreadCount) unread")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(AirtelTheme.accent)
                            Spacer()
                            Button("Mark All Read") {
                                viewModel.markAllRead()
                            }
                            .font(.caption.weight(.semibold))
                            .disabled(viewModel.isBusy)
                        }
                    }

                    LazyVStack(spacing: 10) {
                        ForEach(viewModel.visiblePage.messages) { msg in
                            Button {
                                viewModel.openMessage(msg)
                            } label: {
                                messageCard(msg)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    paginationBar
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground).ignoresSafeArea())
        .navigationTitle("Messages & USSD")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 12) {
                    if viewModel.ussdAvailable {
                        Button {
                            viewModel.showUssdSheet = true
                        } label: {
                            Image(systemName: "number.square")
                        }
                    }
                    Button {
                        viewModel.composeInitialPhone = ""
                        viewModel.showComposeSheet = true
                    } label: {
                        Image(systemName: "square.and.pencil")
                    }
                    .disabled(!viewModel.settings.smsEnabled)

                    Menu {
                        Button {
                            viewModel.refresh()
                        } label: {
                            Label("Refresh", systemImage: "arrow.clockwise")
                        }
                        Button {
                            viewModel.showSettingsSheet = true
                        } label: {
                            Label("SMS Settings", systemImage: "slider.horizontal.3")
                        }
                        if !viewModel.visiblePage.messages.isEmpty {
                            Divider()
                            Button(role: .destructive) {
                                confirmDeleteAll = true
                            } label: {
                                Label("Clear \(viewModel.activeFolder.label)", systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .onAppear {
            viewModel.ensureLoaded()
        }
        .sheet(item: $viewModel.selectedMessage) { msg in
            SmsDetailSheet(msg: msg, viewModel: viewModel)
        }
        .sheet(isPresented: $viewModel.showComposeSheet) {
            SmsComposeSheet(viewModel: viewModel)
        }
        .sheet(isPresented: $viewModel.showSettingsSheet) {
            SmsSettingsSheet(viewModel: viewModel)
        }
        .sheet(isPresented: $viewModel.showUssdSheet) {
            UssdDialerSheet(viewModel: viewModel)
        }
        .sheet(isPresented: $confirmDeleteAll) {
            ConfirmSheet(
                request: ConfirmPromptRequest(
                    title: "Clear \(viewModel.activeFolder.label)?",
                    impactMessage: "Permanently deletes all SMS messages in \(viewModel.activeFolder.label) from the router.",
                    confirmLabel: "Delete All",
                    tier: .connectivity,
                    onConfirm: { viewModel.deleteAllInCurrentFolder() }
                )
            )
            .presentationDetents([.medium])
        }
    }

    private func messageCard(_ msg: SmsMessage) -> some View {
        SectionCard {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    if msg.folder == .inbox && !msg.isRead {
                        Circle().fill(AirtelTheme.accent).frame(width: 8, height: 8)
                    }
                    Text(msg.phoneNumber)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.primary)
                    Spacer()
                    Text(msg.date)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                Text(msg.text)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
            }
        }
    }

    @ViewBuilder
    private var paginationBar: some View {
        let p = viewModel.visiblePage
        let totalPages = max(1, (p.totalCount + 9) / 10)
        if totalPages > 1 {
            HStack {
                Button("Previous") {
                    viewModel.refresh(folder: viewModel.activeFolder, page: max(1, p.page - 1))
                }
                .disabled(p.page <= 1 || viewModel.isLoading)
                Spacer()
                Text("Page \(p.page) of \(totalPages)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Next") {
                    viewModel.refresh(folder: viewModel.activeFolder, page: min(totalPages, p.page + 1))
                }
                .disabled(p.page >= totalPages || viewModel.isLoading)
            }
            .padding(.top, 4)
        }
    }
}

private struct SmsDetailSheet: View {
    let msg: SmsMessage
    @ObservedObject var viewModel: MessagesViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    SectionCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(msg.phoneNumber)
                                .font(.headline)
                                .textSelection(.enabled)
                            Text(msg.date)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Divider()
                            Text(msg.text)
                                .font(.body)
                                .textSelection(.enabled)
                        }
                    }

                    HStack(spacing: 12) {
                        Button {
                            let target = msg.phoneNumber
                            dismiss()
                            viewModel.composeInitialPhone = target
                            viewModel.showComposeSheet = true
                        } label: {
                            Label("Reply", systemImage: "arrowshape.turn.up.left.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .liquidGlassActionButton(tint: AirtelTheme.accent)

                        Button(role: .destructive) {
                            viewModel.deleteMessage(msg)
                            dismiss()
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                        .liquidGlassSmallButton()
                    }
                }
                .padding(16)
            }
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("Message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }
}

private struct SmsComposeSheet: View {
    @ObservedObject var viewModel: MessagesViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var phone = ""
    @State private var bodyText = ""
    @State private var localError: String?

    var body: some View {
        let weight = SmsValidators.weightedSmsLength(bodyText)
        let maxWeight = viewModel.settings.maxWeightedLength
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.error {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("Recipient") {
                    TextField("Phone Number", text: $phone)
                        .keyboardType(.phonePad)
                }
                Section("Message (\(weight)/\(maxWeight) units)") {
                    TextEditor(text: $bodyText)
                        .frame(minHeight: 120)
                }
            }
            .navigationTitle("New SMS")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Send") {
                        localError = SmsValidators.validatePhoneNumber(phone)
                            ?? SmsValidators.validateSmsBody(bodyText, maxWeightedLength: maxWeight)
                        if localError == nil {
                            viewModel.sendSms(to: phone, body: bodyText)
                        }
                    }
                    .disabled(viewModel.isBusy)
                }
            }
            .onAppear {
                phone = viewModel.composeInitialPhone
            }
        }
    }
}

private struct SmsSettingsSheet: View {
    @ObservedObject var viewModel: MessagesViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var smsEnabled = true
    @State private var smscNumber = ""
    @State private var maxSize = "100"
    @State private var localError: String?

    var body: some View {
        NavigationStack {
            Form {
                if let err = localError ?? viewModel.error {
                    Section { Text(err).font(.caption).foregroundStyle(AirtelTheme.error) }
                }
                Section("SMS Service") {
                    Toggle("Enable SMS", isOn: $smsEnabled).tint(AirtelTheme.accent)
                    if viewModel.settings.smscEditable {
                        TextField("SMSC Center Number", text: $smscNumber)
                            .keyboardType(.phonePad)
                    } else if !viewModel.settings.smscNumber.isEmpty {
                        HStack {
                            Text("SMSC Number")
                            Spacer()
                            Text(viewModel.settings.smscNumber).foregroundStyle(.secondary)
                        }
                    }
                    TextField("Storage Limit (50–1000)", text: $maxSize)
                        .keyboardType(.numberPad)
                }
            }
            .navigationTitle("SMS Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        localError = SmsValidators.validateSmsMaxSize(maxSize)
                        guard localError == nil, let sizeInt = Int(maxSize) else { return }
                        viewModel.saveSmsSettings(enabled: smsEnabled, smsc: smscNumber, maxSize: sizeInt)
                    }
                    .disabled(viewModel.isBusy)
                }
            }
            .onAppear {
                smsEnabled = viewModel.settings.smsEnabled
                smscNumber = viewModel.settings.smscNumber
                maxSize = "\(viewModel.settings.maxSize)"
            }
        }
    }
}

private struct UssdDialerSheet: View {
    @ObservedObject var viewModel: MessagesViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var codeInput = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                SectionCard {
                    VStack(alignment: .leading, spacing: 10) {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(viewModel.ussdShortcuts, id: \.self) { code in
                                    Button(code) {
                                        codeInput = code
                                    }
                                    .font(.caption.weight(.semibold).monospacedDigit())
                                    .liquidGlassSmallButton()
                                }
                            }
                        }

                        HStack(spacing: 8) {
                            TextField(
                                viewModel.ussdState.needReply ? "Enter menu reply..." : "Enter USSD code (e.g. *123#)",
                                text: $codeInput
                            )
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)

                            Button("Send") {
                                let payload = codeInput
                                codeInput = ""
                                viewModel.sendUssd(payload)
                            }
                            .disabled(viewModel.ussdState.isBusy)
                            .liquidGlassActionButton(tint: AirtelTheme.accent)
                        }

                        if viewModel.ussdState.needReply {
                            Button("Cancel USSD Session") {
                                viewModel.cancelUssdSession()
                            }
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AirtelTheme.warning)
                        }

                        if let err = viewModel.ussdState.lastError {
                            Text(err)
                                .font(.caption)
                                .foregroundStyle(AirtelTheme.error)
                        }
                    }
                }

                ScrollView {
                    VStack(spacing: 10) {
                        if viewModel.ussdState.isBusy {
                            ProgressView("Waiting for carrier USSD response...")
                                .padding(.vertical, 16)
                        }
                        ForEach(Array(viewModel.ussdState.history.enumerated().reversed()), id: \.offset) { _, item in
                            SectionCard {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("> \(item.outgoing)")
                                        .font(.caption.weight(.bold).monospacedDigit())
                                        .foregroundStyle(AirtelTheme.accent)
                                    Text(item.response)
                                        .font(.subheadline)
                                        .textSelection(.enabled)
                                }
                            }
                        }
                    }
                }

                Spacer()
            }
            .padding(16)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .navigationTitle("USSD Dialer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !viewModel.ussdState.history.isEmpty {
                        Button("Clear") { viewModel.clearUssdHistory() }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
