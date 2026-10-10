# MacBridge development companion

MacBridge now has a native SwiftUI menu-bar app with phone status, clipboard and file receiving controls, pairing codes, a real QR code, and phone revocation. It manages the existing Python TLS engine as a child process and preserves the identity and paired phones from the CLI. Keychain storage, a fully Swift transport, Bonjour advertising, resume across app restarts, and notification mirroring remain future steps.

## Native Mac app

Build and launch from the repository root:

```sh
mac/native/build-app.sh
open mac/native/dist/MacBridge.app
```

Requires macOS 13+, the Swift command-line tools to build, and Python 3/OpenSSL on the Mac to run this development version. The build uses no downloaded Swift packages. The app bundle lives at `mac/native/dist/MacBridge.app`; it is signed locally for development and is not notarized for distribution.

Click the **link icon in the menu bar**. The app has no Dock icon. Its native system menu shows connection status, **Send Clipboard to Phone**, **Send File to Phone…**, **Show Received Files**, and checkmarked **Allow Clipboard Sharing** / **Allow File Receiving** controls. All daily actions fit in the menu without scrolling. Choose **Pair a Phone…** to open the QR code in a separate settings window, **Manage Devices…** to manage paired phones, or **Settings…** for the local address, detailed status and **Restart Companion**. The menu and settings window follow the system appearance; the Mac no longer uses the Android-style dark dashboard. On Android open **Devices → Pair a Mac → Scan QR** and scan this code, or paste the pairing code. Review the displayed Mac identity and tap **Verify Identity & Pair**. Scanning requires Google Play services; first use may need internet to download the scanner module. Paste remains available if scanning fails. Codes expire after five minutes and are invalidated after successful pairing. **Generate New Code** refreshes the code and local address.

Clipboard sharing starts enabled for compatibility with the working CLI and can be paused in the app. The sharing choices are saved across app restarts. Sending requires an authenticated connected phone. **Manage Devices… → Forget…** revokes the phone on the Mac and requires confirmation. **Quit MacBridge** stops the app's child server; **Restart Companion** restarts it without changing the identity.

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
4. Open **Devices → Pair a Mac**. Scan the QR in the native Mac app, or paste the complete `macbridge://pair?...` code displayed after `PAIRING_URI=` in the CLI. Transfer it over a trusted out-of-band channel. The code contains a secret; do not post it publicly. Click **Verify Identity & Pair** within five minutes.
5. Under the paired Mac, enable **Clipboard sharing**. Enable **File sharing** too when you want to send documents.
6. Copy text on Android and use **Share → Clipboard → Send clipboard to Mac**, or share text to MacBridge from another app. The Mac clipboard receives that text.
7. Copy text on the Mac and enter `/push` in the companion terminal. While Android is connected, its clipboard receives the Mac text.

Pairing codes work once. Use `/code` to issue a fresh code. Reconnecting a paired phone requires its stored private key, not another code. Use `/peers` to list phone IDs and `/forget ID` to revoke a phone on the Mac. Use **Devices → Forget this Mac** to revoke the Mac on Android. Revocation is local to each endpoint; revoke on both sides to fully reset a pairing.

Guest networks or client isolation can prevent communication. Allow the development peer through the Mac firewall if macOS asks. The first version uses the IP in the code; if it changes, issue a fresh pairing code. The Android Quick Settings tile and background action are subject to Android's clipboard access restrictions; foreground sharing is the reliable first path.

## Security and storage

Protocol v2 is newline-delimited UTF-8 JSON inside TLS 1.2 or 1.3. Frames are capped at 1 MiB before JSON parsing. Android pins the SHA-256 fingerprint of the actual Mac certificate's SubjectPublicKeyInfo and checks certificate validity before sending a pairing secret.

The Mac issues a random challenge. Android signs a domain-separated transcript binding the challenge, both endpoint IDs and both public-key fingerprints with its P-256 Keystore identity. The Mac verifies the signature with OpenSSL and checks its expiring 256-bit one-use pairing secret before registering a new phone. Subsequent sessions require a fresh signed challenge from the pinned phone key. This is server TLS plus phone authentication inside TLS, not mutual TLS with client certificates.

Android production identity fails closed when Keystore is unavailable. An explicit software fallback exists for JVM tests. New Android peer records start with all sharing features disabled. Legacy prototype records must be paired again because their placeholder keys are invalid.

The development Mac identity and phone pins live in `~/Library/Application Support/MacBridgeDev`, using owner-only directory and file permissions. The Mac private key is currently an unencrypted file, not a Keychain key. Back up these files securely if you need to preserve the development identity. The self-signed certificate expires after one year; there is no automatic certificate renewal yet. Do not delete or rotate an identity casually: existing pins will no longer match.

The Security screen lists implementation status and pending controls; it is not an independent security audit. Notification actions, inherited Firebase initialization, and broader abuse resistance remain unfinished.

## Validate

```sh
python3 -m unittest discover -s mac -v
```

Native UI protocol and QR decoding tests:

```sh
mac/native/test-app.sh
```

In Android Studio run the `testDebugUnitTest` Gradle task and `assembleDebug`. `SecureTransportIntegrationTest` starts a temporary loopback Mac peer and tests Android-to-Mac TLS pairing, Unicode text roundtrip, reconnect, wrong-pin rejection, consumed-code rejection, verified file delivery and cancellation. It requires Python 3 and OpenSSL on the test host; it does not read or write the real Mac clipboard.

Authentication tests additionally cover unknown phones, invalid signatures, expired secrets, signature replay, changed phone keys, revocation, and malformed or oversized frames. Android background behavior and real Mac clipboard permissions still need a manual smoke test. The redesigned Android APK has been installed on a physical phone with its existing pairing preserved.

Implementation references: [Android custom TLS trust managers](https://developer.android.com/privacy-and-security/security-ssl), [Python TLS contexts](https://docs.python.org/3/library/ssl.html), [OpenSSL signature verification](https://docs.openssl.org/master/man1/openssl-dgst/).

Native UI references: [Apple MenuBarExtra](https://developer.apple.com/documentation/swiftui/menubarextra), [Core Image QR generation](https://developer.apple.com/documentation/coreimage/ciqrcodegenerator).

## Receive files

Enable **Allow File Receiving** in the native menu-bar app; it defaults off and is saved across restarts. For the CLI, start with `python3 mac/macbridge.py --clipboard --files`. On Android enable **Devices → File sharing** for this Mac, then open **Share → Files** and choose a document. The limit is 100 MB per file.

Verified files are saved under the companion state directory in `ReceivedFiles` (normally `~/Library/Application Support/MacBridgeDev/ReceivedFiles`). **Show Received Files** opens the folder. The directory is owner-only and files have mode 0600. Names are sanitized and receive a unique prefix to avoid overwriting an existing file. Files are never opened automatically.

Each authenticated connection accepts one ordered transfer at a time, bounded to 64 KB chunks. Completion requires the announced size and SHA-256 checksum to match; the phone receives the checksum confirmation. Disconnects retain a peer-scoped partial for up to 10 minutes while the engine remains running; Android can reconnect and choose Resume transfer. Permission revocation, cancellation and graceful shutdown discard partial files. Startup removes interrupted `.incoming-*` files after a crash while preserving verified files. A receive attempt requires the file size plus 8 MB free space. Resume negotiates the receiver’s confirmed chunk offset and still requires the full checksum before publication. App restart requires choosing the file again.

Closing the settings window leaves the companion and its menu running. Reopen the app from Finder to return to settings. Paired-phone lists scroll only within the Devices settings tab when many phones are saved; the menu contains a fixed number of actions regardless of phone count. Long and Unicode phone names are bounded in the menu while device management keeps the full name. Stopped or disconnected companions keep unavailable actions visible and disabled.

The menu design follows [Apple’s menu-bar Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/the-menu-bar), using the native [MenuBarExtra menu style](https://developer.apple.com/documentation/swiftui/menubarextrastyle/menu). QR codes and longer setup instructions belong in the settings window rather than the menu.

## Send files to Android

Enable **File sharing** for this Mac in Android Devices, connect, then choose **Send File to Phone…** from the Mac menu. Choose one regular file up to 100 MB. Mac receiving permission controls the opposite direction; it is not required for sending a deliberately selected file. The picker is tied to the current connection, so a changed phone or reconnected session requires selecting the file again. The CLI also supports `/send /absolute/path/to/file`; paths with spaces are accepted without shell quoting.

The menu shows preparation/progress and delivery status, plus **Resume File Sending** and **Cancel File Sending**. Reconnect the original paired phone to resume within 10 minutes; another identity cannot resume the transfer. The CLI supports `/resume` and `/cancel`. Android confirms size and checksum before the Mac reports success. Open **Share → Files** on Android, then **Save As…** on the incoming verified file to choose a document destination. Cancelling Save As leaves the private verified copy in the app. A failure never automatically opens a document or retries to a different peer.

Mac snapshots use an owner-only `OutgoingFiles` directory under the state directory. Disconnects and acknowledgement timeouts retain the immutable snapshot for up to 10 minutes. Resume rehashes it and seeks only to a bounded, chunk-aligned receiver offset. Lost final acknowledgements recover from recent verified receipts without duplicate publication. Cancellation, expiry, rejection or shutdown remove snapshots; startup clears stale `.outgoing-*` files. Resume does not survive app restarts. Selected paths and contents never appear in status messages. Sending and receiving can run independently; cancel or resume a paused outgoing transfer before selecting another file. Receiver retention is bounded to four paired identities, one 100 MB partial and 64 recent receipts each; disabling receiving or forgetting a phone removes its unverified partials.
