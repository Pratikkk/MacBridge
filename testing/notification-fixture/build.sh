#!/bin/bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
fixture="$root/testing/notification-fixture"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then echo "Set ANDROID_SDK_ROOT to your Android SDK" >&2; exit 1; fi
platform="${FIXTURE_PLATFORM:-android-36.1}"
version="${FIXTURE_BUILD_TOOLS:-36.0.0}"
tools="$sdk/build-tools/$version"
jar="$sdk/platforms/$platform/android.jar"
out="$root/build/notification-fixture"
mkdir -p "$out/classes"
javac -source 11 -target 11 -classpath "$jar" -d "$out/classes" "$fixture"/src/com/macbridge/notificationfixture/*.java
"$tools/d8" --lib "$jar" --min-api 31 --output "$out" "$out"/classes/com/macbridge/notificationfixture/*.class
"$tools/aapt2" link -I "$jar" --manifest "$fixture/AndroidManifest.xml" -o "$out/unsigned.apk"
(cd "$out" && zip -q unsigned.apk classes.dex)
"$tools/apksigner" sign --ks "$root/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$out/fixture.apk" "$out/unsigned.apk"
"$tools/apksigner" verify "$out/fixture.apk"
echo "Built $out/fixture.apk"
