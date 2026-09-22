import SwiftUI

/// Connection status line under the large navigation title.
/// The refresh / reboot / settings actions live in the navigation bar toolbar.
public struct HeaderBarView: View {
    @ObservedObject var viewModel: MonitorViewModel

    public init(viewModel: MonitorViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
                .shadow(color: statusColor.opacity(0.8), radius: 4)

            Text(statusText)
                .font(.caption)
                .foregroundColor(statusColor)
                .fontWeight(.bold)

            if viewModel.isConnected {
                Text("• \(viewModel.state.timeFormatted)")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Spacer()
        }
    }

    private var statusColor: Color {
        if viewModel.isConnected {
            return .green
        } else if viewModel.isRefreshing {
            return .orange
        } else {
            return .red
        }
    }

    private var statusText: String {
        if viewModel.isConnected {
            return "LIVE"
        } else if viewModel.isRefreshing {
            return "CONNECTING"
        } else {
            return "OFFLINE"
        }
    }
}
