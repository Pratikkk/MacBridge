# MacBridge

An Android-to-Mac local-network bridge. The current milestone supports authenticated QR or pasted-code pairing, clipboard exchange, reconnecting, and a native Mac menu-bar UI.

The Android app pins the Mac TLS public key and proves its own Keystore identity with a signed challenge. Pairing codes expire after five minutes and work once. New Android peers start with sharing permissions disabled.

## Android development

Open the project in Android Studio with the Android SDK required by `app/build.gradle.kts`, or configure `local.properties` with your SDK path. Use a JDK supported by the checked-in Gradle version. This checkout has been tested with Android Studio's bundled JDK and Gradle 9.8.0.

Generate the local development signing key once, from the project root:

```sh
keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
  -keyalg RSA -keysize 2048 -validity 10000
```

`debug.keystore` and `local.properties` are local files and are excluded from Git. The debug key signs development APKs; it is separate from the phone's runtime Keystore identity.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The real TLS integration tests require Python 3 and OpenSSL on PATH and permission to bind loopback sockets. They start an isolated Mac test peer and never modify the real Mac clipboard.

## Mac companion

See [Mac setup and usage](mac/README.md). Build and launch the native menu-bar app:

```sh
mac/native/build-app.sh
open mac/native/dist/MacBridge.app
```

Click the link icon in the menu bar. Existing CLI pairing records are reused. Do not run the CLI and native app simultaneously on port 8990.

## Feature validation

```sh
python3 -m unittest discover -s mac -v
mac/native/test-app.sh
```

These checks cover identity and code rejection, Unicode, bounded and malformed frames, reconnect, clipboard permission and disconnect cases, legacy phone records, desktop control recovery and shutdown, prompt status delivery, exact QR decoding, Android scan cancellation and failure recovery, duplicate-scan prevention, late-result cancellation, and identical scanned/pasted identities. After each feature, add relevant regression and edge-case checks, build, then commit and push to the configured GitHub remote as documented in [AGENTS.md](AGENTS.md).

## Remaining work

The native UI still uses the tested Python TLS engine. Native Swift transport, Mac Keychain storage, file transfers, notification actions, and a full security review remain planned. The app is a locally signed development build, not a notarized release. The Security screen reports implementation status rather than claiming full compliance.

## Pair by QR

On Mac, click the menu-bar link icon and expand **Pair a phone**. On Android, open **Devices → Pair Mac → Scan QR**, point the camera at the Mac code, review the Mac name, address and public-key fingerprint, then tap **Verify Identity & Pair**. Scanning stages the code; pairing happens only after your tap.

The scanner uses [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) through Google Play services, without MacBridge requesting camera permission. First use may need internet to download the scanner module. If services or camera access are unavailable, paste the Mac code instead. Cancelling or scanning another type of QR leaves any previously entered code unchanged. Generate a fresh Mac code if the five-minute window expired or the code was already used.

Automated tests use injected scanner results and verify the real pairing protocol separately. A physical-device camera scan remains a manual check: scan the Mac QR, cancel and retry, try a non-MacBridge QR, pair, then confirm clipboard exchange.
