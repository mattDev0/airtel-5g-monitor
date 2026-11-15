import SwiftUI

public enum AirtelTheme {
    public static let accent = Color(red: 0.894, green: 0.0, blue: 0.0) // Airtel Red #E40000
    public static let nr5g = Color.green
    public static let lte4g = Color.blue
    public static let upload = Color.purple

    public static let success = Color.green
    public static let warning = Color.orange
    public static let error = Color.red
    public static let info = Color.blue

    public static func rsrpColor(_ rsrpDbm: Double?) -> Color {
        guard let v = rsrpDbm else { return .secondary }
        if v >= -85 { return success }
        if v >= -100 { return info }
        if v >= -110 { return warning }
        return error
    }

    public static func sinrColor(_ sinrDb: Double?) -> Color {
        guard let v = sinrDb else { return .secondary }
        if v >= 15 { return success }
        if v >= 5 { return info }
        if v >= 0 { return warning }
        return error
    }

    public static func qualityLabel(rsrpDbm: Double?) -> String {
        guard let v = rsrpDbm else { return "--" }
        if v >= -85 { return "Excellent" }
        if v >= -100 { return "Good" }
        if v >= -110 { return "Fair" }
        return "Poor"
    }
}
