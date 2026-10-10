import AppKit
import CoreImage

struct PairingQR {
    private static let context = CIContext()
    static func image(for code: String) -> NSImage? {
        guard !code.isEmpty, let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(code.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage,
              let raster = context.createCGImage(output.transformed(by: .init(scaleX: 6, y: 6)),
                  from: output.extent.applying(.init(scaleX: 6, y: 6))) else { return nil }
        return NSImage(cgImage: raster, size: NSSize(width: raster.width, height: raster.height))
    }
}

/// One live code per settings view; countdown redraws reuse its raster.
final class PairingQRCache {
    private var code = ""
    private var rendered: NSImage?
    private let render: (String) -> NSImage?
    init(render: @escaping (String) -> NSImage? = PairingQR.image) { self.render = render }
    func image(for newCode: String) -> NSImage? {
        if newCode != code {
            code = newCode
            rendered = newCode.isEmpty ? nil : render(newCode)
        }
        return rendered
    }
}
