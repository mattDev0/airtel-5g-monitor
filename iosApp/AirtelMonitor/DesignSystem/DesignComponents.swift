import SwiftUI

public struct SectionCard<Content: View, Trailing: View>: View {
    private let title: String?
    private let subtitle: String?
    private let tint: Color?
    private let trailing: () -> Trailing
    private let content: () -> Content

    public init(
        title: String? = nil,
        subtitle: String? = nil,
        tint: Color? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() },
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.subtitle = subtitle
        self.tint = tint
        self.trailing = trailing
        self.content = content
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if title != nil || subtitle != nil || Trailing.self != EmptyView.self {
                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        if let title {
                            Text(title)
                                .font(.headline)
                                .foregroundStyle(.primary)
                        }
                        if let subtitle {
                            Text(subtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 4)
                    trailing()
                }
            }
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .liquidGlassCard(cornerRadius: 20, tint: tint)
    }
}

public struct StatTile: View {
    private let title: String
    private let value: String
    private let unit: String?
    private let caption: String?
    private let accent: Color

    public init(
        title: String,
        value: String,
        unit: String? = nil,
        caption: String? = nil,
        accent: Color = .primary
    ) {
        self.title = title
        self.value = value
        self.unit = unit
        self.caption = caption
        self.accent = accent
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.title3.weight(.bold).monospacedDigit())
                    .foregroundStyle(accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                if let unit, !unit.isEmpty {
                    Text(unit)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }
            if let caption, !caption.isEmpty {
                Text(caption)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

public struct SettingSwitchRow: View {
    private let title: String
    private let subtitle: String?
    private let icon: String?
    @Binding private var isOn: Bool
    private let isBusy: Bool
    private let onChanged: (Bool) -> Void

    public init(
        title: String,
        subtitle: String? = nil,
        icon: String? = nil,
        isOn: Binding<Bool>,
        isBusy: Bool = false,
        onChanged: @escaping (Bool) -> Void
    ) {
        self.title = title
        self.subtitle = subtitle
        self.icon = icon
        self._isOn = isOn
        self.isBusy = isBusy
        self.onChanged = onChanged
    }

    public var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AirtelTheme.accent)
                    .frame(width: 24)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            if isBusy {
                ProgressView()
                    .controlSize(.small)
            } else {
                Toggle(isOn: Binding(
                    get: { isOn },
                    set: { newVal in
                        isOn = newVal
                        onChanged(newVal)
                    }
                )) {
                    EmptyView()
                }
                .labelsHidden()
                .tint(AirtelTheme.accent)
            }
        }
        .padding(.vertical, 4)
    }
}

public struct SettingValueRow: View {
    private let title: String
    private let subtitle: String?
    private let value: String
    private let icon: String?
    private let action: (() -> Void)?

    public init(
        title: String,
        subtitle: String? = nil,
        value: String = "",
        icon: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.title = title
        self.subtitle = subtitle
        self.value = value
        self.icon = icon
        self.action = action
    }

    public var body: some View {
        Group {
            if let action {
                Button(action: action) {
                    rowContent(showChevron: true)
                }
                .buttonStyle(.plain)
            } else {
                rowContent(showChevron: false)
            }
        }
    }

    private func rowContent(showChevron: Bool) -> some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AirtelTheme.accent)
                    .frame(width: 24)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.primary)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }
            }
            Spacer(minLength: 8)
            if !value.isEmpty {
                Text(value)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            if showChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

public struct BadgePill: View {
    private let text: String
    private let color: Color

    public init(_ text: String, color: Color) {
        self.text = text
        self.color = color
    }

    public var body: some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.14), in: Capsule())
    }
}

public struct SignalBars: View {
    public let level: Int // 0...5
    public let activeColor: Color

    public init(level: Int, activeColor: Color = AirtelTheme.success) {
        self.level = min(5, max(0, level))
        self.activeColor = activeColor
    }

    public var body: some View {
        HStack(alignment: .bottom, spacing: 2.5) {
            ForEach(1...5, id: \.self) { idx in
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(idx <= level ? activeColor : Color.secondary.opacity(0.25))
                    .frame(width: 3.5, height: CGFloat(4 + idx * 3))
            }
        }
    }
}

public struct FlatProgressBar: View {
    public let fraction: Double
    public let tint: Color
    public let height: CGFloat

    public init(fraction: Double, tint: Color = AirtelTheme.accent, height: CGFloat = 8) {
        self.fraction = min(1.0, max(0.0, fraction))
        self.tint = tint
        self.height = height
    }

    public var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: height / 2, style: .continuous)
                    .fill(Color.secondary.opacity(0.18))
                RoundedRectangle(cornerRadius: height / 2, style: .continuous)
                    .fill(tint)
                    .frame(width: max(fraction > 0 ? height : 0, geo.size.width * fraction))
            }
        }
        .frame(height: height)
    }
}

public struct SignalMeter: View {
    public let title: String
    public let valueText: String
    public let fraction: Double
    public let tint: Color
    public let badgeText: String?

    public init(
        title: String,
        valueText: String,
        fraction: Double,
        tint: Color,
        badgeText: String? = nil
    ) {
        self.title = title
        self.valueText = valueText
        self.fraction = min(1.0, max(0.0, fraction))
        self.tint = tint
        self.badgeText = badgeText
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                Spacer()
                if let badgeText {
                    BadgePill(badgeText, color: tint)
                }
                Text(valueText)
                    .font(.subheadline.weight(.bold).monospacedDigit())
                    .foregroundStyle(tint)
            }
            FlatProgressBar(fraction: fraction, tint: tint, height: 8)
        }
    }
}

public struct CarrierRow: View {
    public let carrier: CarrierComponent

    public init(carrier: CarrierComponent) {
        self.carrier = carrier
    }

    public var body: some View {
        HStack(spacing: 10) {
            BadgePill(
                carrier.tech == .nr ? "NR5G" : "LTE",
                color: carrier.tech == .nr ? AirtelTheme.nr5g : AirtelTheme.lte4g
            )
            Text(carrier.band)
                .font(.subheadline.weight(.bold))
            if carrier.isPrimary {
                BadgePill("PCC", color: .secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 1) {
                Text("PCI \(carrier.pci)")
                    .font(.caption.monospacedDigit().weight(.medium))
                HStack(spacing: 6) {
                    Text(carrier.earfcn)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                    if let bw = carrier.bandwidthMHz {
                        Text("\(bw) MHz")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

public struct EmptyStateView: View {
    private let icon: String
    private let title: String
    private let message: String
    private let actionTitle: String?
    private let action: (() -> Void)?

    public init(
        icon: String = "tray",
        title: String,
        message: String,
        actionTitle: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.icon = icon
        self.title = title
        self.message = message
        self.actionTitle = actionTitle
        self.action = action
    }

    public var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 32, weight: .semibold))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let actionTitle, let action {
                Button(actionTitle, action: action)
                    .liquidGlassSmallButton()
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }
}

public struct ErrorStateView: View {
    private let title: String
    private let message: String
    private let retryTitle: String
    private let onRetry: () -> Void

    public init(
        title: String = "Unable to Load",
        message: String,
        retryTitle: String = "Retry",
        onRetry: @escaping () -> Void
    ) {
        self.title = title
        self.message = message
        self.retryTitle = retryTitle
        self.onRetry = onRetry
    }

    public var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 28))
                .foregroundStyle(AirtelTheme.warning)
            Text(title)
                .font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button(retryTitle, action: onRetry)
                .liquidGlassActionButton(tint: AirtelTheme.accent)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }
}

public struct SkeletonBox: View {
    private let height: CGFloat

    public init(height: CGFloat = 96) {
        self.height = height
    }

    public var body: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(Color.secondary.opacity(0.14))
            .frame(maxWidth: .infinity)
            .frame(height: height)
    }
}
