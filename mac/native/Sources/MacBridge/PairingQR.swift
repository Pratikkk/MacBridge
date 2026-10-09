import AppKit
import CoreImage

struct PairingQR {
    static func image(for code: String) -> NSImage? {
        guard !code.isEmpty, let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(code.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage,
              let raster = CIContext().createCGImage(output.transformed(by: .init(scaleX: 6, y: 6)),
                  from: output.extent.applying(.init(scaleX: 6, y: 6))) else { return nil }
        return NSImage(cgImage: raster, size: NSSize(width: raster.width, height: raster.height))
    }
}
