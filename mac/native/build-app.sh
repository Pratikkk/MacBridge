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
# Remove only obsolete engine resources from an earlier build of this bundle.
for resource in file_sender file_receiver transfer_state bounded_json notification_mirror certificate_store resource_limits identity_store macbridge; do
  if [ -f "$app_dir/Contents/Resources/$resource.py" ]; then
    unlink "$app_dir/Contents/Resources/$resource.py"
  fi
done
cp "$output_dir/MacBridgeEngine" "$app_dir/Contents/MacOS/MacBridgeEngine.new"
mv -f "$app_dir/Contents/MacOS/MacBridgeEngine.new" "$app_dir/Contents/MacOS/MacBridgeEngine"
cp ../../assets/branding/MacBridge.icns "$app_dir/Contents/Resources/MacBridge.icns"
cat > "$app_dir/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>MacBridge</string>
<key>CFBundleIdentifier</key><string>com.macbridge.companion</string>
<key>CFBundleName</key><string>MacBridge</string>
<key>CFBundleDisplayName</key><string>MacBridge</string>
<key>CFBundleIconFile</key><string>MacBridge.icns</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.3.0</string>
<key>CFBundleVersion</key><string>3</string>
<key>LSMinimumSystemVersion</key><string>15.0</string>
<key>LSUIElement</key><true/>
<key>NSLocalNetworkUsageDescription</key><string>MacBridge connects to your paired Android phone on your local network to share clipboard text and files.</string>
<key>NSBonjourServices</key><array><string>_macbridge._tcp</string></array>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
cp "$output_dir/MacBridgeIdentity" "$app_dir/Contents/MacOS/MacBridgeIdentity.new"
mv -f "$app_dir/Contents/MacOS/MacBridgeIdentity.new" "$app_dir/Contents/MacOS/MacBridgeIdentity"
codesign --force --identifier com.macbridge.companion.identity-helper --sign - "$app_dir/Contents/MacOS/MacBridgeIdentity"
codesign --force --identifier com.macbridge.companion.engine --sign - "$app_dir/Contents/MacOS/MacBridgeEngine"
codesign --force --sign - "$app_dir"
printf 'Built %s\n' "$app_dir"
