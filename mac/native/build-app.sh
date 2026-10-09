#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
swift build -c release
output_dir="$(swift build -c release --show-bin-path)"
app_dir="$PWD/dist/MacBridge.app"
mkdir -p "$app_dir/Contents/MacOS" "$app_dir/Contents/Resources"
# Replace the inode rather than truncating an executable that may still be running.
cp "$output_dir/MacBridge" "$app_dir/Contents/MacOS/MacBridge.new"
mv -f "$app_dir/Contents/MacOS/MacBridge.new" "$app_dir/Contents/MacOS/MacBridge"
cp ../file_receiver.py "$app_dir/Contents/Resources/file_receiver.py"
cp ../macbridge.py "$app_dir/Contents/Resources/macbridge.py"
cat > "$app_dir/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>MacBridge</string>
<key>CFBundleIdentifier</key><string>com.macbridge.companion</string>
<key>CFBundleName</key><string>MacBridge</string>
<key>CFBundleDisplayName</key><string>MacBridge</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.2.0</string>
<key>CFBundleVersion</key><string>2</string>
<key>LSMinimumSystemVersion</key><string>13.0</string>
<key>LSUIElement</key><true/>
<key>NSLocalNetworkUsageDescription</key><string>MacBridge connects to your paired Android phone on your local network to share clipboard text.</string>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
codesign --force --sign - "$app_dir"
printf 'Built %s\n' "$app_dir"
