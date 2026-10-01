import SwiftUI

// Building blocks shared by the dashboard cards, matching the Android app's layout.

/// Card title with an optional subtitle and a trailing accessory (badge, button…).
struct AppCardHeader<Trailing: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                if let subtitle {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            trailing
        }
    }
}

/// Small rounded status label, e.g. "Active", "DHCP Active", a device count.
struct AppBadge: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.15), in: Capsule())
    }
}

/// Round glass icon button used in card headers.
struct GlassIconButton: View {
    let systemImage: String
    var tint: Color = .primary
    var label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 20, height: 20)
        }
        .accessibilityLabel(label)
        .liquidGlassIconButton()
    }
}

/// Coloured circle holding an icon, like the Android cards' leading icons.
struct IconCircle: View {
    let systemImage: String
    var size: CGFloat = 40
    var active: Bool = true

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(active ? Color.accentColor : .secondary)
            .frame(width: size, height: size)
            .background((active ? Color.accentColor : Color.secondary).opacity(active ? 0.18 : 0.10), in: Circle())
    }
}

/// Label-over-value pill (Band, Cell, Temp, CPU).
struct AppTelemetryPill: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.caption.weight(.bold).monospaced()).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
        .padding(.horizontal, 4)
        .liquidGlassPill(cornerRadius: 10)
    }
}

/// Title on the left, monospaced value on the right ("None" when empty).
struct AppDetailRow: View {
    let title: String
    let value: String

    var body: some View {
        HStack {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Spacer(minLength: 8)
            Text(value.isEmpty || value == "-" ? "None" : value)
                .font(.caption.weight(.medium).monospaced())
                .multilineTextAlignment(.trailing)
        }
    }
}

enum StatusColors {
    static let success = Color.green
    static let warning = Color.orange
    static let error = Color.red
}
