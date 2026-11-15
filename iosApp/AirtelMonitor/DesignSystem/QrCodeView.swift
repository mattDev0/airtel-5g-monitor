import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins

public struct QrCodeView: View {
    public let payload: String
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    public init(payload: String) {
        self.payload = payload
    }

    public var body: some View {
        Group {
            if let uiImage = renderQrImage() {
                Image(uiImage: uiImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .padding(12)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            } else {
                Text("Unable to generate QR code")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func renderQrImage() -> UIImage? {
        let data = Data(payload.utf8)
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let outputImage = filter.outputImage else { return nil }
        let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        guard let cgImage = context.createCGImage(transformed, from: transformed.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
