import SwiftUI

struct LiquidGlassCardModifier: ViewModifier {
    var cornerRadius: CGFloat = 20
    var tintColor: Color? = nil

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            if let tintColor {
                content
                    .glassEffect(.regular.tint(tintColor.opacity(0.14)), in: .rect(cornerRadius: cornerRadius))
            } else {
                content
                    .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
            }
        } else {
            content
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(
                            LinearGradient(
                                colors: [
                                    Color.white.opacity(0.28),
                                    Color.white.opacity(0.08)
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            lineWidth: 0.75
                        )
                )
        }
    }
}

struct LiquidGlassCapsuleModifier: ViewModifier {
    var tintColor: Color? = nil

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            if let tintColor {
                content
                    .glassEffect(.regular.tint(tintColor.opacity(0.18)), in: .capsule)
            } else {
                content
                    .glassEffect(.regular, in: .capsule)
            }
        } else {
            content
                .background(.ultraThinMaterial, in: Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(Color.white.opacity(0.22), lineWidth: 0.6)
                )
        }
    }
}

struct LiquidGlassInteractiveCapsuleModifier: ViewModifier {
    var tintColor: Color? = nil

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            if let tintColor {
                content
                    .glassEffect(.regular.tint(tintColor.opacity(0.2)).interactive(), in: .capsule)
            } else {
                content
                    .glassEffect(.regular.interactive(), in: .capsule)
            }
        } else {
            content
                .background(.thinMaterial, in: Capsule())
                .overlay(
                    Capsule()
                        .strokeBorder(Color.white.opacity(0.25), lineWidth: 0.6)
                )
        }
    }
}

struct LiquidGlassActionButtonModifier: ViewModifier {
    var tint: Color

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .buttonStyle(.glassProminent)
                .tint(tint)
        } else {
            content
                .buttonStyle(.borderedProminent)
                .tint(tint)
        }
    }
}

struct LiquidGlassSmallButtonModifier: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
                .buttonStyle(.glass)
        } else {
            content
                .buttonStyle(.bordered)
        }
    }
}

struct LiquidGlassIconButtonModifier: ViewModifier {
    var prominent: Bool = false

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            if prominent {
                content.buttonStyle(.glassProminent)
            } else {
                content.buttonStyle(.glass)
            }
        } else {
            content
                .buttonStyle(.borderless)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(
                    Circle()
                        .strokeBorder(Color.white.opacity(0.2), lineWidth: 0.6)
                )
        }
    }
}

public struct GlassActionGroup<Content: View>: View {
    private let spacing: CGFloat
    private let content: () -> Content

    public init(spacing: CGFloat = 10, @ViewBuilder content: @escaping () -> Content) {
        self.spacing = spacing
        self.content = content
    }

    public var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) {
                HStack(spacing: spacing) {
                    content()
                }
            }
        } else {
            HStack(spacing: spacing) {
                content()
            }
        }
    }
}

public extension View {
    func liquidGlassCard(cornerRadius: CGFloat = 20, tint: Color? = nil) -> some View {
        modifier(LiquidGlassCardModifier(cornerRadius: cornerRadius, tintColor: tint))
    }

    func liquidGlassCapsule(tint: Color? = nil) -> some View {
        modifier(LiquidGlassCapsuleModifier(tintColor: tint))
    }

    func liquidGlassInteractiveCapsule(tint: Color? = nil) -> some View {
        modifier(LiquidGlassInteractiveCapsuleModifier(tintColor: tint))
    }

    func liquidGlassActionButton(tint: Color = .accentColor) -> some View {
        modifier(LiquidGlassActionButtonModifier(tint: tint))
    }

    func liquidGlassSmallButton() -> some View {
        modifier(LiquidGlassSmallButtonModifier())
    }

    func liquidGlassIconButton(prominent: Bool = false) -> some View {
        modifier(LiquidGlassIconButtonModifier(prominent: prominent))
    }
}
