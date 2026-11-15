import SwiftUI

public struct LineChartSeries: Identifiable {
    public let id = UUID()
    public let values: [Double]
    public let color: Color
    public let fillArea: Bool

    public init(values: [Double], color: Color, fillArea: Bool = false) {
        self.values = values
        self.color = color
        self.fillArea = fillArea
    }
}

public struct LineChart: View {
    public let series: [LineChartSeries]
    public let showGrid: Bool
    public let fixedMin: Double?
    public let fixedMax: Double?

    public init(
        series: [LineChartSeries],
        showGrid: Bool = true,
        fixedMin: Double? = nil,
        fixedMax: Double? = nil
    ) {
        self.series = series
        self.showGrid = showGrid
        self.fixedMin = fixedMin
        self.fixedMax = fixedMax
    }

    public var body: some View {
        Canvas { context, size in
            let allValues = series.flatMap(\.values)
            guard allValues.count >= 2, size.width > 1, size.height > 1 else { return }

            let minVal = fixedMin ?? (allValues.min() ?? 0)
            let rawMax = fixedMax ?? (allValues.max() ?? 1)
            let maxVal = max(rawMax, minVal + 0.01)
            let range = maxVal - minVal

            if showGrid {
                let gridColor = Color.secondary.opacity(0.25)
                for step in 1...3 {
                    let y = size.height * (CGFloat(step) / 4.0)
                    var gridPath = Path()
                    gridPath.move(to: CGPoint(x: 0, y: y))
                    gridPath.addLine(to: CGPoint(x: size.width, y: y))
                    context.stroke(
                        gridPath,
                        with: .color(gridColor),
                        style: StrokeStyle(lineWidth: 1, dash: [4, 4])
                    )
                }
            }

            for item in series where item.values.count >= 2 {
                var linePath = Path()
                var fillPath = Path()
                let stepX = size.width / CGFloat(max(1, item.values.count - 1))

                for (idx, val) in item.values.enumerated() {
                    let x = CGFloat(idx) * stepX
                    let norm = min(1.0, max(0.0, (val - minVal) / range))
                    let y = size.height - CGFloat(norm) * size.height
                    if idx == 0 {
                        linePath.move(to: CGPoint(x: x, y: y))
                        fillPath.move(to: CGPoint(x: x, y: size.height))
                        fillPath.addLine(to: CGPoint(x: x, y: y))
                    } else {
                        linePath.addLine(to: CGPoint(x: x, y: y))
                        fillPath.addLine(to: CGPoint(x: x, y: y))
                    }
                }
                let lastX = CGFloat(item.values.count - 1) * stepX
                fillPath.addLine(to: CGPoint(x: lastX, y: size.height))
                fillPath.closeSubpath()

                if item.fillArea {
                    context.fill(
                        fillPath,
                        with: .linearGradient(
                            Gradient(colors: [item.color.opacity(0.26), item.color.opacity(0.02)]),
                            startPoint: CGPoint(x: 0, y: 0),
                            endPoint: CGPoint(x: 0, y: size.height)
                        )
                    )
                }

                context.stroke(
                    linePath,
                    with: .color(item.color),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                )
            }
        }
    }
}

public struct Sparkline: View {
    public let values: [Double]
    public let lineColor: Color
    public let secondaryValues: [Double]
    public let secondaryColor: Color

    public init(
        values: [Double],
        lineColor: Color,
        secondaryValues: [Double] = [],
        secondaryColor: Color = AirtelTheme.upload
    ) {
        self.values = values
        self.lineColor = lineColor
        self.secondaryValues = secondaryValues
        self.secondaryColor = secondaryColor
    }

    public var body: some View {
        let built: [LineChartSeries] = {
            var list: [LineChartSeries] = []
            if !values.isEmpty {
                list.append(LineChartSeries(values: values, color: lineColor, fillArea: true))
            }
            if !secondaryValues.isEmpty {
                list.append(LineChartSeries(values: secondaryValues, color: secondaryColor, fillArea: false))
            }
            return list
        }()
        LineChart(series: built, showGrid: false)
            .frame(height: 44)
    }
}

public struct DailyUsageBarChart: View {
    public let entries: [DailyUsageEntry]
    public let tint: Color

    public init(entries: [DailyUsageEntry], tint: Color = AirtelTheme.accent) {
        self.entries = entries
        self.tint = tint
    }

    public var body: some View {
        let visible = Array(entries.suffix(31))
        let maxVal = max(1.0, visible.map(\.dailyMb).max() ?? 1.0)
        Canvas { context, size in
            guard !visible.isEmpty, size.width > 1, size.height > 1 else { return }
            let count = CGFloat(visible.count)
            let slotWidth = size.width / count
            let barWidth = max(3.0, min(14.0, slotWidth * 0.65))
            let xOffset = (slotWidth - barWidth) / 2.0

            for (idx, entry) in visible.enumerated() {
                let norm = min(1.0, max(0.03, entry.dailyMb / maxVal))
                let barHeight = size.height * CGFloat(norm)
                let rect = CGRect(
                    x: CGFloat(idx) * slotWidth + xOffset,
                    y: size.height - barHeight,
                    width: barWidth,
                    height: barHeight
                )
                context.fill(
                    Path(roundedRect: rect, cornerRadius: barWidth / 3.0),
                    with: .color(entry.dailyMb > 0 ? tint : Color.secondary.opacity(0.2))
                )
            }
        }
        .frame(height: 110)
    }
}
