# MacBridge

An Android-to-Mac local-network bridge. The current milestone supports authenticated QR or pasted-code pairing, clipboard exchange, verified file sharing and interrupted-transfer resume in both directions, reconnecting, and a native Mac menu-bar UI.

The Android app pins the Mac TLS public key and proves its own Keystore identity with a signed challenge. Pairing codes expire after five minutes and work once. New Android peers start with sharing permissions disabled.

## Milestones and progress

Follow [MacBridge — Milestones and progress](https://github.com/users/Pratikkk/projects/2) for delivered features and upcoming work. [Repository milestones](https://github.com/Pratikkk/MacBridge/milestones) group the feature issues. Todo means planned, In Progress means implementation has started, and Done means the feature passed applicable validation and its commit was pushed and verified. Issues record acceptance checks, delivery evidence and remaining limits; planned items have no implied deadline or implementation order.

Feature work updates the project as part of the [project workflow](AGENTS.md): start the matching issue, record scope changes or blockers, then attach the verified commit, validation results and applicable Android installation outcome before closing it and marking Done.

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

The native UI still uses the tested Python TLS engine. Native Swift transport, Mac Keychain storage, notification actions, and a full security review remain planned. The app is a locally signed development build, not a notarized release. The Security screen reports implementation status rather than claiming full compliance.

## Pair by QR

On Mac, click the menu-bar link icon and choose **Pair a Phone…**. On Android, open **Devices → Pair a Mac → Scan QR**, point the camera at the Mac code, review the Mac name, address and public-key fingerprint, then tap **Verify Identity & Pair**. Scanning stages the code; pairing happens only after your tap.

The scanner uses [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) through Google Play services, without MacBridge requesting camera permission. First use may need internet to download the scanner module. If services or camera access are unavailable, paste the Mac code instead. Cancelling or scanning another type of QR leaves any previously entered code unchanged. Generate a fresh Mac code if the five-minute window expired or the code was already used.

Automated tests use injected scanner results and verify the real pairing protocol separately. A physical-device camera scan remains a manual check: scan the Mac QR, cancel and retry, try a non-MacBridge QR, pair, then confirm clipboard exchange.

## Android interface

The Android app uses a black-and-white palette with soft decorative blur behind the Home connection card. Text and controls are never blurred; older Android versions retain the gradient background.

- **Home**: connection status and pair/connect/cancel, with direct Clipboard and Files shortcuts once a Mac is paired. If several Macs are saved, choose one in Devices.
- **Share**: Clipboard and Files tabs. Send documents in either direction with verified progress, cancellation and Save As for received files, or send current phone text, instructions for receiving Mac text, and local history with copy, expand and confirmed clearing. Send actions are disabled when disconnected, blocked, in demo mode or when the latest stored sharing permission is off.
- **Devices**: paired Macs first, clipboard and file sharing permissions, connect/disconnect, identity disclosure and confirmed removal. Scan or paste to add another Mac.
- **Settings**: sharing permissions, Android battery settings, security diagnostics with unfinished notification features kept outside the everyday flow. Prototype simulator and benchmark actions are outside the everyday navigation.

The selected tab and scroll positions survive recreation; pairing secrets stay in memory only. Navigation disposes pending scanner callbacks. Automated UI checks cover offline/reconnecting states, revoked permissions, multiple Macs, confirmations, navigation restoration, blocked peers and narrow screens with enlarged text.

## Send a file to your Mac

Enable **Devices → File sharing** for the chosen Mac on Android and **Allow file receiving** in the Mac menu-bar companion. Receiving starts off on the Mac and its toggle is saved across restarts. Open **Share → Files → Choose a file**, then select a document with the system picker. Files are limited to 100 MB; only one outgoing transfer runs at a time.

The app takes a bounded private snapshot, sends 64 KB chunks over the authenticated TLS session and waits for each acknowledgement. It marks a transfer complete only when the Mac confirms the exact byte count and SHA-256 checksum. Zero-byte files are supported. The Mac saves verified files in `~/Library/Application Support/MacBridgeDev/ReceivedFiles`; **Show Received Files** opens that folder. Files are never opened automatically.

Disconnects and acknowledgement timeouts pause a prepared transfer. Reconnect the same paired Mac, then tap **Resume transfer** in Android file history within 10 minutes. Resume uses the original private snapshot, even if the selected document changed. Cancellation and permission revocation discard partials. After either app reopens, reconnect the original peer and resume from its saved checkpoint within the same ten-minute recovery window. Completed files remain. This milestone uses the in-app picker; Android file share-sheet intents direct you to Share → Files.

File tests cover normal multi-chunk and empty transfers, Unicode names, checksum rejection, ordering and malformed chunks, oversized documents (including unknown sizes), unrelated-peer acknowledgements, revoked document access, disabled sharing, timeouts, disconnects, cancellation, duplicate names, disk-space rejection and shutdown/restart cleanup. The Android-to-Python integration test verifies actual TLS delivery and cancellation against an isolated receive folder.

The Android interface uses monochrome panels and a two-option Clipboard/Files selector. The Mac companion follows the native menu-bar pattern: a compact system menu with connection status, clipboard sending, the received-files folder and sharing checkmarks. Pairing, device management and detailed status open in a separate settings window. The menu follows macOS appearance and keyboard navigation, and daily actions require no scrolling. Android recovery actions remain reachable with enlarged text.

## Send a file from Mac to phone

Connect the phone and enable **Devices → File sharing** for this Mac on Android. Choose **Send File to Phone…** in the Mac menu, then select one regular file up to 100 MB. The Mac snapshots the selected file privately, sends bounded chunks, and reports delivery only after Android confirms the exact size and SHA-256 checksum. The send is tied to the connection that was active when the picker opened; a connection change before the picker finishes requires choosing the file again. Once prepared, an interrupted send can resume to the same paired phone. **Cancel File Sending** discards an active or paused send. The menu shows preparation, progress and the final result without adding a scrolling dashboard.

On Android open **Share → Files**. Incoming transfers have a **Mac → Phone** label. Verified files stay in app-private storage until you tap **Save As…** and choose a destination in the system document picker. Cancelling that picker leaves the private copy intact. Save As rechecks the source checksum; access or provider failures keep the verified copy available for retry and attempt to remove any failed export if the provider supports deletion. Files are never opened automatically, and no broad storage permission is requested. Private received copies remain saved after export.

One outgoing transfer, including a paused transfer, is allowed at a time. After interruption, reconnect the original phone and choose **Resume File Sending** in the Mac menu within 10 minutes. The menu disables Resume for another paired phone. Android resumes only the same Mac identity with File sharing still allowed. Cancel is available while paused. Partial files remain private and cannot use Save As. A receiver without a retained partial or receipt rejects resume, so an expired, corrupt or forgotten receiver cannot silently start a duplicate transfer. Both receivers report their confirmed offset; senders validate chunk alignment and bounds before seeking the original snapshot. A lost final acknowledgement can be recovered from a bounded persisted verified receipt without publishing a duplicate file.

Resume survives app/process restarts using private atomic checkpoints and immutable source snapshots. Permission revocation, cancellation or expiry discard partials; completed copies survive. The Mac retains up to four peer-scoped receivers, each bounded to 100 MB, and 64 recent receipts per receiver. Android retains one incoming partial and 64 recent receipts. Resuming rehashes private snapshots and received prefixes; final size and SHA-256 verification still gate publication. Android file share-sheet integration remains future work.

Resume checks exercise real TLS reconnects in both directions, confirmed-offset continuation, lost-final-ack receipts, changed source documents, corrupt snapshots, original peer identity, expiry, permission revocation and paused cancellation. Reverse-transfer checks also cover real Python-to-Android TLS delivery, empty and multi-chunk files, Unicode and safe names, malformed metadata/chunks, duplicate identities, checksum rejection, permission changes, unrelated acknowledgements, cancel/disconnect/timeout/shutdown cleanup, restart recovery and Save As permission or source failures. System picker interaction on a physical phone remains a manual check.

## Restart-safe transfer recovery

After an interruption or app restart, reconnect the same paired device and choose **Resume transfer** on Android or **Resume File Sending** on Mac. Recovery remains available for ten minutes, including time while an app is closed; restarting alone does not extend a saved deadline. Sending is user-initiated. Changing or forgetting a paired identity, disabling its file permission, cancelling, or expiry invalidates its partial recovery data. Significant wall-clock changes invalidate saved deadlines rather than retaining files indefinitely.

Checkpoints use atomic metadata replacement in private storage, with bounded relative filenames and the original pinned peer identity. Source snapshots and received prefixes are rehashed before continuation. Receivers checkpoint data at most every 250 ms during progress, then force a checkpoint when paused or stopped gracefully. After an abrupt exit they may return an earlier durable chunk offset and safely request those chunks again; acknowledgement counts remain exact during a running session. Uncheckpointed trailing bytes are truncated before resuming. A receiver never creates a fresh file in response to an unknown resume request.

Verified publication is journaled before the final rename. Restart can finish a verified publication interrupted between rename and history/receipt storage, and persisted receipts recover a lost final acknowledgement without another saved copy. Receipts are bounded to 64 per receiver and ten minutes; their referenced file is reverified when a completion receipt is requested, keeping startup from hashing every historical file. Invalid or missing recovery state is discarded, while previously completed files remain available. Process restart checks recreate Android managers and exercise a forcibly killed Mac server over real authenticated TLS; manual whole-device reboot and sudden power-loss testing remain separate checks.

## Flow and presentation performance

Home leads directly to Clipboard or Files; the Share selector remembers each page’s scroll and help state. Sharing instructions are expandable, and active/paused transfers appear before completed history. Settings focuses on sharing permissions, battery access and diagnostics. File permission descriptions cover both directions, and Clipboard/Files use the same current-identity and permission checks. Save As disables duplicate picker launches and shows Saving only on the file being exported. The native Mac menu shows Resume/Cancel only when relevant; its controls still use system appearance and keyboard navigation.

Transfer progress database writes and Mac progress reports are limited to one every 250 ms. Every chunk still gets its protocol acknowledgement, and pause/failure/completion records update immediately with exact counts. Transfer watchdogs wait for activity instead of waking on idle 250 ms timers. The native controller avoids publishing unchanged fields; the pairing countdown caches one QR raster and reuses its Core Image context, replacing/clearing the raster when the code rotates or expires.

Deterministic 128-chunk (8 MB) checks preserve all 129 acknowledgements: Android uses at most three outgoing history writes and two incoming writes when the presentation clock does not advance; the Mac uses 24 reports with simulated 50 ms chunk intervals, compared with the previous 130 reports. A 300-request QR countdown check uses one raster. These are work-count regressions, not measurements of physical-device throughput, frame rate or battery life. UI checks cover direct routes, page restoration, collapsed help, paused recovery, duplicate Save As prevention, current permissions/identity, and narrow screens with enlarged text.
