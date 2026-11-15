import SwiftUI

public enum ConfirmTier: Sendable {
    /// Tier 2: connectivity change (explains what drops).
    case connectivity
    /// Tier 3: destructive action (requires typing confirmation token).
    case destructive
}

public struct ConfirmPromptRequest: Identifiable {
    public let id = UUID()
    public let title: String
    public let impactMessage: String
    public let confirmLabel: String
    public let tier: ConfirmTier
    public let requiredConfirmationText: String?
    public let onConfirm: () -> Void

    public init(
        title: String,
        impactMessage: String,
        confirmLabel: String,
        tier: ConfirmTier = .connectivity,
        requiredConfirmationText: String? = nil,
        onConfirm: @escaping () -> Void
    ) {
        self.title = title
        self.impactMessage = impactMessage
        self.confirmLabel = confirmLabel
        self.tier = tier
        self.requiredConfirmationText = requiredConfirmationText
        self.onConfirm = onConfirm
    }
}

public struct ConfirmSheet: View {
    public let request: ConfirmPromptRequest
    @Environment(\.dismiss) private var dismiss
    @State private var typedInput: String = ""

    public init(request: ConfirmPromptRequest) {
        self.request = request
    }

    private var expectedText: String? {
        guard request.tier == .destructive else { return nil }
        if let custom = request.requiredConfirmationText?.trimmingCharacters(in: .whitespacesAndNewlines),
           !custom.isEmpty {
            return custom
        }
        return "ZLT X17M"
    }

    private var confirmEnabled: Bool {
        guard let expectedText else { return true }
        return typedInput.trimmingCharacters(in: .whitespacesAndNewlines)
            .caseInsensitiveCompare(expectedText) == .orderedSame
    }

    public var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.title2)
                        .foregroundStyle(request.tier == .destructive ? AirtelTheme.error : AirtelTheme.warning)
                    Text(request.title)
                        .font(.title3.weight(.bold))
                }

                Text(request.impactMessage)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                if let expectedText {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Type \"\(expectedText)\" to confirm:")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        TextField("Type \(expectedText) here", text: $typedInput)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .textFieldStyle(.roundedBorder)
                    }
                }

                Spacer(minLength: 8)

                HStack(spacing: 12) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .liquidGlassSmallButton()
                    .frame(maxWidth: .infinity)

                    Button(request.confirmLabel, role: request.tier == .destructive ? .destructive : nil) {
                        request.onConfirm()
                        dismiss()
                    }
                    .liquidGlassActionButton(tint: request.tier == .destructive ? AirtelTheme.error : AirtelTheme.accent)
                    .disabled(!confirmEnabled)
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(20)
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium])
    }
}
