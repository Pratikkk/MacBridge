import AppKit
import Foundation

// Render the shared vector geometry with native antialiasing; no external packages.
let input = URL(fileURLWithPath: CommandLine.arguments[1])
let destination = URL(fileURLWithPath: CommandLine.arguments[2])
let size = Int(CommandLine.arguments[3])!
let shape = CommandLine.arguments[4]
let design = try JSONSerialization.jsonObject(with: Data(contentsOf: input)) as! [String: Any]
func color(_ hex: String) -> NSColor {
    let value = UInt32(hex.dropFirst(), radix: 16)!
    return NSColor(srgbRed: CGFloat((value >> 16) & 255)/255, green: CGFloat((value >> 8) & 255)/255,
        blue: CGFloat(value & 255)/255, alpha: 1)
}
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
let context = NSGraphicsContext(bitmapImageRep: bitmap)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = context
context.shouldAntialias = true
context.cgContext.scaleBy(x: CGFloat(size)/100, y: CGFloat(size)/100)
context.cgContext.clear(CGRect(x: 0, y: 0, width: 100, height: 100))
color(design["background"] as! String).setFill()
let rect = shape == "mac" ? NSRect(x: 5, y: 5, width: 90, height: 90) : NSRect(x: 0, y: 0, width: 100, height: 100)
if shape == "round" { NSBezierPath(ovalIn: rect).fill() }
else { NSBezierPath(roundedRect: rect, xRadius: shape == "mac" ? 20 : 0, yRadius: shape == "mac" ? 20 : 0).fill() }
color(design["foreground"] as! String).setStroke()
for spec in design["paths"] as! [[String: Any]] {
    let path = NSBezierPath()
    path.lineWidth = CGFloat((spec["width"] as! NSNumber).doubleValue)
    path.lineCapStyle = .round
    path.lineJoinStyle = .round
    for command in spec["commands"] as! [[Any]] {
        let values = command.dropFirst().map { ($0 as! NSNumber).doubleValue }
        func point(_ index: Int) -> NSPoint { NSPoint(x: values[index], y: 100-values[index+1]) }
        switch command[0] as! String {
        case "M": path.move(to: point(0))
        case "L": path.line(to: point(0))
        case "C": path.curve(to: point(4), controlPoint1: point(0), controlPoint2: point(2))
        default: fatalError("Unsupported icon path")
        }
    }
    path.stroke()
}
NSGraphicsContext.restoreGraphicsState()
try bitmap.representation(using: .png, properties: [:])!.write(to: destination)
