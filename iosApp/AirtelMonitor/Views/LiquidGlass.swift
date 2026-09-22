import SwiftUI

public struct LiquidGlassCardModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    public init() {}

    public func body(content: Content) -> some View {
        content
            .padding(16)
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(
                        LinearGradient(
                            colors: colorScheme == .dark ? [
                                Color.white.opacity(0.30),
                                Color.white.opacity(0.08),
                                Color.white.opacity(0.12)
                            ] : [
                                Color.white.opacity(0.85),
                                Color.white.opacity(0.35),
                                Color.white.opacity(0.55)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1
                    )
            )
            .shadow(
                color: colorScheme == .dark
                    ? Color.black.opacity(0.35)
                    : Color.black.opacity(0.06),
                radius: 14,
                x: 0,
                y: 6
            )
    }
}

public struct LiquidGlassPillModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    public init() {}

    public func body(content: Content) -> some View {
        content
            .background(.thinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(
                        LinearGradient(
                            colors: colorScheme == .dark ? [
                                Color.white.opacity(0.18),
                                Color.white.opacity(0.04)
                            ] : [
                                Color.white.opacity(0.65),
                                Color.white.opacity(0.2)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 0.8
                    )
            )
    }
}

public struct LiquidGlassBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    public init() {}

    public var body: some View {
        ZStack {
            Color(colorScheme == .dark ? UIColor.systemBackground : UIColor.systemGroupedBackground)
                .ignoresSafeArea()

            GeometryReader { geo in
                ZStack {
                    // Subtle glowing ambient light orbs
                    Circle()
                        .fill(Color(red: 0.90, green: 0.15, blue: 0.20).opacity(colorScheme == .dark ? 0.16 : 0.09))
                        .frame(width: geo.size.width * 0.85)
                        .blur(radius: 70)
                        .offset(x: -geo.size.width * 0.22, y: -geo.size.height * 0.18)

                    Circle()
                        .fill(Color.blue.opacity(colorScheme == .dark ? 0.14 : 0.08))
                        .frame(width: geo.size.width * 0.75)
                        .blur(radius: 80)
                        .offset(x: geo.size.width * 0.32, y: geo.size.height * 0.12)

                    Circle()
                        .fill(Color.purple.opacity(colorScheme == .dark ? 0.12 : 0.06))
                        .frame(width: geo.size.width * 0.7)
                        .blur(radius: 75)
                        .offset(x: -geo.size.width * 0.1, y: geo.size.height * 0.42)
                }
            }
            .ignoresSafeArea()
        }
    }
}

public extension View {
    func liquidGlassCard() -> some View {
        self.modifier(LiquidGlassCardModifier())
    }

    func liquidGlassPill() -> some View {
        self.modifier(LiquidGlassPillModifier())
    }
}
