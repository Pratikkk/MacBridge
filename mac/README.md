# MacBridge development companion

MacBridge now has a native SwiftUI menu-bar app with phone status, clipboard controls, pairing codes, a real QR code, and phone revocation. It manages the existing Python TLS engine as a child process and preserves the identity and paired phones from the CLI. Keychain storage, a fully Swift transport, Bonjour advertising, files, and notification mirroring remain future steps.

## Native Mac app

Build and launch from the repository root:

```sh
mac/native/build-app.sh
open mac/native/dist/MacBridge.app
```

Requires macOS 13+, the Swift command-line tools to build, and Python 3/OpenSSL on the Mac to run this development version. The build uses no downloaded Swift packages. The app bundle lives at `mac/native/dist/MacBridge.app`; it is signed locally for development and is not notarized for distribution.

Click the **link icon in the menu bar**. The app has no Dock icon. It shows the connected phone and local address, **Send Clipboard to Phone**, an **Allow clipboard sharing** toggle, and **Pair a phone** controls. On Android open **Devices → Pair Mac → Scan QR** and scan this code, or paste the pairing code. Review the displayed Mac identity and tap **Verify Identity & Pair**. Scanning requires Google Play services; first use may need internet to download the scanner module. Paste remains available if scanning fails. Codes expire after five minutes and are invalidated after successful pairing. **Generate New Code** refreshes the code and local address.

Clipboard sharing starts enabled for compatibility with the working CLI and can be paused in the app. The toggle is saved across app restarts. Sending requires an authenticated connected phone. **Forget** revokes the phone on the Mac and requires confirmation. **Quit** stops the app's child server; **Restart Companion** restarts it without changing the identity.

Only one companion may listen on port 8990. Stop a manually running CLI with `/quit` before opening the app. The native app uses the same `~/Library/Application Support/MacBridgeDev` identity and phone pins, so existing Android pairing survives the switch. If the phone does not reconnect automatically, tap **Connect** under the paired Mac on Android.

The UI receives status events through local process pipes. Clipboard text stays in the TLS engine and macOS pasteboard rather than passing through the status channel. The native app is not sandboxed for this development build; shipping and signing are separate milestones.

## Try it with an Android phone

1. Connect the Mac and phone to the same trusted local network. The Mac may use Ethernet while the phone uses the same router's Wi-Fi; guest Wi-Fi may isolate devices.
2. From the repository root, run this command:

   ```sh
   python3 mac/macbridge.py --clipboard
   ```

   The companion selects the Mac's current network address and prints its endpoint. Keep the terminal running. If a VPN or multiple interfaces select the wrong address, use `--address` with the Mac's actual LAN IP from System Settings → Network → your active connection → Details → TCP/IP. Requires Python 3 and OpenSSL on PATH. `--clipboard` enables actual Mac clipboard reads and writes. Without it, neither `pbcopy` nor `pbpaste` is called. `--echo` is available for testing the protocol without changing the Mac clipboard.
3. Run the Android app from Android Studio, or install `app/build/outputs/apk/debug/app-debug.apk`.
4. Open **Devices → Pair Mac**. Scan the QR in the native Mac app, or paste the complete `macbridge://pair?...` code displayed after `PAIRING_URI=` in the CLI. Transfer it over a trusted out-of-band channel. The code contains a secret; do not post it publicly. Click **Verify Identity & Pair** within five minutes.
5. Under the paired Mac, enable **Clipboard Sync**. Other features should stay disabled for this milestone.
6. Copy text on Android and use **Clipboard → Push to Mac**, or share text to MacBridge from another app. The Mac clipboard receives that text.
7. Copy text on the Mac and enter `/push` in the companion terminal. While Android is connected, its clipboard receives the Mac text.

Pairing codes work once. Use `/code` to issue a fresh code. Reconnecting a paired phone requires its stored private key, not another code. Use `/peers` to list phone IDs and `/forget ID` to revoke a phone on the Mac. Use **Devices → Unpair** to revoke the Mac on Android. Revocation is local to each endpoint; revoke on both sides to fully reset a pairing.

Guest networks or client isolation can prevent communication. Allow the development peer through the Mac firewall if macOS asks. The first version uses the IP in the code; if it changes, issue a fresh pairing code. The Android Quick Settings tile and background action are subject to Android's clipboard access restrictions; foreground sharing is the reliable first path.

## Security and storage

Protocol v2 is newline-delimited UTF-8 JSON inside TLS 1.2 or 1.3. Frames are capped at 1 MiB before JSON parsing. Android pins the SHA-256 fingerprint of the actual Mac certificate's SubjectPublicKeyInfo and checks certificate validity before sending a pairing secret.

The Mac issues a random challenge. Android signs a domain-separated transcript binding the challenge, both endpoint IDs and both public-key fingerprints with its P-256 Keystore identity. The Mac verifies the signature with OpenSSL and checks its expiring 256-bit one-use pairing secret before registering a new phone. Subsequent sessions require a fresh signed challenge from the pinned phone key. This is server TLS plus phone authentication inside TLS, not mutual TLS with client certificates.

Android production identity fails closed when Keystore is unavailable. An explicit software fallback exists for JVM tests. New Android peer records start with all sharing features disabled. Legacy prototype records must be paired again because their placeholder keys are invalid.

The development Mac identity and phone pins live in `~/Library/Application Support/MacBridgeDev`, using owner-only directory and file permissions. The Mac private key is currently an unencrypted file, not a Keychain key. Back up these files securely if you need to preserve the development identity. The self-signed certificate expires after one year; there is no automatic certificate renewal yet. Do not delete or rotate an identity casually: existing pins will no longer match.

The Security screen lists implementation status and pending controls; it is not an independent security audit. File handling, notification actions, inherited Firebase initialization, and broader abuse resistance remain unfinished.

## Validate

```sh
python3 -m unittest discover -s mac -v
```

Native UI protocol and QR decoding tests:

```sh
mac/native/test-app.sh
```

In Android Studio run the `testDebugUnitTest` Gradle task and `assembleDebug`. `SecureTransportIntegrationTest` starts a temporary loopback Mac peer and tests Android-to-Mac TLS pairing, Unicode text roundtrip, reconnect, wrong-pin rejection and consumed-code rejection. It requires Python 3 and OpenSSL on the test host; it does not read or write the real Mac clipboard.

Authentication tests additionally cover unknown phones, invalid signatures, expired secrets, signature replay, changed phone keys, revocation, and malformed or oversized frames. Physical-device installation, Android background behavior, and real Mac clipboard permissions still need a manual smoke test.

Implementation references: [Android custom TLS trust managers](https://developer.android.com/privacy-and-security/security-ssl), [Python TLS contexts](https://docs.python.org/3/library/ssl.html), [OpenSSL signature verification](https://docs.openssl.org/master/man1/openssl-dgst/).

Native UI references: [Apple MenuBarExtra](https://developer.apple.com/documentation/swiftui/menubarextra), [Core Image QR generation](https://developer.apple.com/documentation/coreimage/ciqrcodegenerator).
