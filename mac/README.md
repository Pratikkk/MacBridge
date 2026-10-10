# MacBridge development companion

MacBridge now has a native SwiftUI menu-bar app with phone status, clipboard and file receiving controls, pairing codes, a real QR code, phone revocation, and Bonjour discovery. It manages the existing Python TLS engine as a child process and preserves the identity and paired phones from the CLI. Keychain storage and a fully Swift transport remain future steps.

## Native Mac app

Build and launch from the repository root:

```sh
mac/native/build-app.sh
open mac/native/dist/MacBridge.app
```

Requires macOS 13+, the Swift command-line tools to build, and Python 3/OpenSSL on the Mac to run this development version. The build uses no downloaded Swift packages. The app bundle lives at `mac/native/dist/MacBridge.app`; it is signed locally for development and is not notarized for distribution.

### Install for daily testing

Quit MacBridge from its menu, then run from the repository root:

```sh
mac/native/install-app.sh
open ~/Applications/MacBridge.app
```

This builds, verifies and installs the app in your user **Applications** folder. It stays there independently of repository build outputs and can be opened from Finder or Launchpad without a terminal. Future updates use the same command. `--destination /Applications` selects the shared Applications folder if you have write access. The installer refuses unrelated apps, symlink targets and invalid signatures; it stages a verified replacement before changing the existing app and restores the previous app if replacement fails. It requires Python 3 (3.9+) with SSL and OpenSSL, matching the companion's runtime lookup. Keep those dependencies installed for this testing version.

The installation preserves the existing `MacBridgeDev` identity, pairing, received files and app preferences. Settings windows can be closed while the menu-bar companion continues running. **Quit MacBridge** stops sharing until you reopen it.

In **Settings → General**, optionally enable **Open at Login**. It starts off for a new installation, uses Apple's [SMAppService](https://developer.apple.com/documentation/servicemanagement/smappservice) and reflects the actual macOS setting. If approval is needed, use **Allow in Login Items…**; changes made in System Settings refresh when the app becomes active. Login registration is offered only from an Applications folder so it does not point at a temporary repository build. Installing or updating does not enable it automatically. Turn it off before removing or moving the installed app.

This is a local development-signed testing build with a native interface and the existing Python engine. Public Developer ID signing/notarization, fully Swift transport and Keychain identity storage remain separate release work.

Click the **link icon in the menu bar**. The app has no Dock icon. Its native system menu shows connection status, **Send Clipboard to Phone**, **Send File to Phone…**, **Show Received Files**, and checkmarked **Allow Clipboard Sharing** / **Allow File Receiving** controls. All daily actions fit in the menu without scrolling. Choose **Pair a Phone…** to open the QR code in a separate settings window, **Manage Devices…** to manage paired phones, or **Settings…** for the local address, detailed status and **Restart Companion**. The menu and settings window follow the system appearance; the Mac no longer uses the Android-style dark dashboard. On Android open **Devices → Pair a Mac → Scan QR** and scan this code, or paste the pairing code. Review the displayed Mac identity and tap **Verify Identity & Pair**. Scanning requires Google Play services; first use may need internet to download the scanner module. Paste remains available if scanning fails. Codes expire after five minutes and are invalidated after successful pairing. **Generate New Code** refreshes the code and local address.

Clipboard sharing starts enabled for compatibility with the working CLI and can be paused in the app. The sharing choices are saved across app restarts. Sending requires an authenticated connected phone. **Manage Devices… → Forget…** revokes the phone on the Mac and requires confirmation. **Quit MacBridge** stops the app's child server; **Restart Companion** restarts it without changing the identity.

Only one companion may listen on port 8990. Stop a manually running CLI with `/quit` before opening the app. The native app uses the same `~/Library/Application Support/MacBridgeDev` identity and phone pins, so existing Android pairing survives the switch. If the phone does not reconnect automatically, tap **Connect** under the paired Mac on Android.

The UI receives status events through local process pipes. Clipboard text stays in the TLS engine and macOS pasteboard rather than passing through the status channel. The native app is not sandboxed for this development build; shipping and signing are separate milestones.

The native app publishes `_macbridge._tcp` on the local network while its engine is running, and withdraws it on engine shutdown or app quit. **Settings → Status → Nearby discovery** reports availability. Android **Devices** shows nearby Macs; saved Macs use matching discovered addresses when you tap Connect. New Macs still require QR or pasted-code pairing. Bonjour publishes only public ID, name, fingerprint and IPv6 capability; every connection retains pinned-key verification. If macOS denies local-network access or the router blocks multicast, use the existing pairing flow. **Restart Companion** retries failed publication. The standalone CLI does not advertise Bonjour.

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

Guest networks or client isolation can prevent communication. Allow the development peer through the Mac firewall if macOS asks. Native Bonjour discovery can locate a saved Mac after its IP changes; if discovery is unavailable, issue a fresh pairing code. The listener supports IPv4 and IPv6 where dual-stack sockets are available; automatic pairing-address selection remains IPv4, with an explicit IPv6 `--address` supported by the CLI. The Android Quick Settings tile and background action are subject to Android's clipboard access restrictions; foreground sharing is the reliable first path.

## Security and storage

Protocol v2 is newline-delimited UTF-8 JSON inside TLS 1.2 or 1.3. Frames are capped at 1 MiB before JSON parsing. Android pins the SHA-256 fingerprint of the actual Mac certificate's SubjectPublicKeyInfo and checks certificate validity before sending a pairing secret.

The Mac issues a random challenge. Android signs a domain-separated transcript binding the challenge, both endpoint IDs and both public-key fingerprints with its P-256 Keystore identity. The Mac verifies the signature with OpenSSL and checks its expiring 256-bit one-use pairing secret before registering a new phone. Subsequent sessions require a fresh signed challenge from the pinned phone key. This is server TLS plus phone authentication inside TLS, not mutual TLS with client certificates.

Android production identity fails closed when Keystore is unavailable. An explicit software fallback exists for JVM tests. New Android peer records start with all sharing features disabled. Legacy prototype records must be paired again because their placeholder keys are invalid.

The development Mac identity and phone pins live in `~/Library/Application Support/MacBridgeDev`, using owner-only directory and file permissions. The Mac private key is currently an unencrypted file, not a Keychain key. Back up these files securely if you need to preserve the development identity. The self-signed certificate expires after one year; there is no automatic certificate renewal yet. Do not delete or rotate an identity casually: existing pins will no longer match.

The Security screen lists implementation status and pending controls; it is not an independent security audit. Inherited Firebase initialization and broader abuse resistance remain unfinished.

## Validate

```sh
python3 -m unittest discover -s mac -v
```

Native UI protocol and QR decoding tests:

```sh
mac/native/test-app.sh
python3 -m unittest discover -s mac/native -p 'test_install_app.py' -v
```

In Android Studio run the `testDebugUnitTest` Gradle task and `assembleDebug`. `SecureTransportIntegrationTest` starts a temporary loopback Mac peer and tests Android-to-Mac TLS pairing, Unicode text roundtrip, reconnect, wrong-pin rejection, consumed-code rejection, verified file delivery and cancellation. It requires Python 3 and OpenSSL on the test host; it does not read or write the real Mac clipboard.

Authentication tests additionally cover unknown phones, invalid signatures, expired secrets, signature replay, changed phone keys, revocation, and malformed or oversized frames. Android background behavior and real Mac clipboard permissions still need a manual smoke test. The redesigned Android APK has been installed on a physical phone with its existing pairing preserved.

Implementation references: [Android custom TLS trust managers](https://developer.android.com/privacy-and-security/security-ssl), [Python TLS contexts](https://docs.python.org/3/library/ssl.html), [OpenSSL signature verification](https://docs.openssl.org/master/man1/openssl-dgst/).

Native UI references: [Apple MenuBarExtra](https://developer.apple.com/documentation/swiftui/menubarextra), [Core Image QR generation](https://developer.apple.com/documentation/coreimage/ciqrcodegenerator).

## Receive files

Enable **Allow File Receiving** in the native menu-bar app; it defaults off and is saved across restarts. For the CLI, start with `python3 mac/macbridge.py --clipboard --files`. On Android enable **Devices → File sharing** for this Mac, then open **Share → Files** and choose a document. The limit is 100 MB per file.

Verified files are saved under the companion state directory in `ReceivedFiles` (normally `~/Library/Application Support/MacBridgeDev/ReceivedFiles`). **Show Received Files** opens the folder. The directory is owner-only and files have mode 0600. Names are sanitized and receive a unique prefix to avoid overwriting an existing file. Files are never opened automatically.

Each authenticated connection accepts one ordered transfer at a time, bounded to 64 KB chunks. Completion requires the announced size and SHA-256 checksum to match; the phone receives the checksum confirmation. Disconnects and app/process restarts retain a peer-scoped partial checkpoint for up to 10 minutes; Android can reconnect and choose Resume transfer. Permission revocation, cancellation and expiry discard partial files. Startup validates known peer checkpoints, truncates uncheckpointed trailing bytes, and removes orphan `.incoming-*` files while preserving verified files. A receive attempt requires the file size plus 8 MB free space. Resume negotiates the receiver’s confirmed chunk offset and still requires the full checksum before publication. App restart preserves recovery only within the saved deadline and with the same paired identity and receiving permission.

Closing the settings window leaves the companion and its menu running. Reopen the app from Finder to return to settings. Paired-phone lists scroll only within the Devices settings tab when many phones are saved; the menu contains a fixed number of actions regardless of phone count. Long and Unicode phone names are bounded in the menu while device management keeps the full name. Stopped or disconnected companions keep unavailable actions visible and disabled.

The menu design follows [Apple’s menu-bar Human Interface Guidelines](https://developer.apple.com/design/human-interface-guidelines/the-menu-bar), using the native [MenuBarExtra menu style](https://developer.apple.com/documentation/swiftui/menubarextrastyle/menu). QR codes and longer setup instructions belong in the settings window rather than the menu.

## Send files to Android

Enable **File sharing** for this Mac in Android Devices, connect, then choose **Send File to Phone…** from the Mac menu. Choose one regular file up to 100 MB. Mac receiving permission controls the opposite direction; it is not required for sending a deliberately selected file. The picker is tied to the current connection, so a changed phone or reconnected session requires selecting the file again. The CLI also supports `/send /absolute/path/to/file`; paths with spaces are accepted without shell quoting.

The menu shows preparation/progress and delivery status, plus **Resume File Sending** and **Cancel File Sending**. Reconnect the original paired phone to resume within 10 minutes; another identity cannot resume the transfer. The CLI supports `/resume` and `/cancel`. Android confirms size and checksum before the Mac reports success. Open **Share → Files** on Android, then **Save As…** on the incoming verified file to choose a document destination. Cancelling Save As leaves the private verified copy in the app. A failure never automatically opens a document or retries to a different peer.

Mac snapshots use an owner-only `OutgoingFiles` directory under the state directory. Disconnects and acknowledgement timeouts retain the immutable snapshot for up to 10 minutes. Resume rehashes it and seeks only to a bounded, chunk-aligned receiver offset. Lost final acknowledgements recover from persisted verified receipts without duplicate publication. Publication is journaled before rename so a process exit during completion can be recovered. Completed receipt files are rehashed on demand; saved prefixes and outgoing snapshots are rehashed before continuation. Atomic metadata in the owner-only `TransferState` directory contains relative private filenames and public peer identifiers, with no pairing codes or private keys. Progress checkpoints are coalesced to 250 ms; an abrupt exit can rewind to the last durable chunk offset. Cancellation, expiry and rejection remove snapshots; startup restores a valid peer-bound snapshot and clears stale `.outgoing-*` files. Resume survives app/process restarts within the saved ten-minute deadline. Selected paths and contents never appear in status messages. Sending and receiving can run independently; cancel or resume a paused outgoing transfer before selecting another file. Receiver retention is bounded to four paired identities, one 100 MB partial and 64 recent receipts each; disabling receiving or forgetting a phone removes its unverified partials.

## Compact flow and progress updates

Resume and Cancel appear in the menu when a transfer is paused or active. Progress reports are coalesced to 250 ms intervals; final delivery, failure and paused state report immediately. Chunk acknowledgements and full-file verification remain unchanged. The native controller skips unchanged property assignments, and the pairing countdown reuses one QR raster until its code changes or expires. Validation includes a 128-chunk transfer with all acknowledgements preserved and 24 progress/status reports, plus 300 countdown requests using one QR render.

## Live file progress

The native menu shows one concise progress row per direction (**To Phone**, **From Phone**), including percentage, transferred/total bytes and paused or verified outcomes. Open **Settings → General** for native progress bars. Preparing, verification, cancellation and failure stay distinct, including empty files. Resume and Cancel remain tied to the outgoing transfer; cancel incoming files from Android. Receiving status updates are coalesced to 250 ms without dropping chunk acknowledgements, and terminal states update immediately. Stopping the engine clears stale progress. Duplicate file-picker panels are prevented, and diagnostics are drained without accumulating their contents in memory.

## Phone notification alerts

Choose **Settings… → Notifications → Allow phone notifications** and approve the macOS alert request. In Android **Devices**, enable notification sharing for this Mac; in **Settings → Notifications**, grant Android access and choose apps. App filters and message previews start off. The compact menu stays unchanged; setup lives in the separate settings window.

The native app uses Apple’s [UserNotifications framework](https://developer.apple.com/documentation/usernotifications/unusernotificationcenter) for local banners and Notification Center entries. It does not use Apple Push Notification service or a cloud relay for this feature. Alerts have opaque session-scoped identifiers, bounded text and catalogs, and an explicit Dismiss on Phone action for current clearable phone alerts; supported alerts also offer Reply. Android removal updates remove the corresponding Mac entry; disconnect, engine shutdown or disabling reception clears the app’s pending and displayed entries. macOS system notification settings can suppress banners independently.

Notification content is sent through the private engine status pipe only for validated notification events, never ordinary status snapshots. It remains in bounded memory rather than bridge files or diagnostic logs. macOS manages its own delivered notification storage. Existing alerts are not restored after restart or reconnect, and the CLI does not present alerts. See the root README for consent, preview behavior, delivery limits and manual verification.

Choose **Dismiss on Phone** on an eligible alert to request Android removal. Native action categories use Apple’s [notification action API](https://developer.apple.com/documentation/UserNotifications/UNNotificationAction). Normal Mac close gestures remain local. Opaque handles and session IDs in action metadata are checked against the current in-memory catalog and engine; the engine sends only to the original live authenticated phone. Old banners after updates, disconnect or restart cannot authorize a new generation. The phone confirms API acceptance separately from its removal event, and no pending action is retried after reconnect. See the root README for platform race limitations and manual verification.

Supported alerts use Apple's [text input notification action](https://developer.apple.com/documentation/usernotifications/untextinputnotificationaction): choose Reply, enter text and Send. Android previews must be enabled and an Android 12+ phone unlocked. The native action requires macOS authentication when locked. One-use reply handles are separate from dismiss handles; original session/generation checks and bounded Unicode validation run in both apps. The phone sends through Android's [RemoteInput API](https://developer.android.com/reference/android/app/RemoteInput) and the original app PendingIntent; acceptance does not prove recipient delivery. See the root README for unsupported action types, privacy and platform limitations.
