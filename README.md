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

See [Android performance measurements and profiling](docs/android-performance.md) for the read-only physical-device idle sampler, lifecycle/query behavior, regression checks, and measurement limits.

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

The native UI still uses the tested Python TLS engine. Native Swift transport, Mac Keychain storage and a full security review remain planned. The app is a locally signed development build, not a notarized release. The Security screen reports implementation status rather than claiming full compliance.

## Pair by QR

On Mac, click the menu-bar link icon and choose **Pair a Phone…**. On Android, open **Devices → Pair a Mac → Scan QR**, point the camera at the Mac code, review the Mac name, address and public-key fingerprint, then tap **Verify Identity & Pair**. Scanning stages the code; pairing happens only after your tap.

The scanner uses [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) through Google Play services, without MacBridge requesting camera permission. First use may need internet to download the scanner module. If services or camera access are unavailable, paste the Mac code instead. Cancelling or scanning another type of QR leaves any previously entered code unchanged. Generate a fresh Mac code if the five-minute window expired or the code was already used.

Automated tests use injected scanner results and verify the real pairing protocol separately. A physical-device camera scan remains a manual check: scan the Mac QR, cancel and retry, try a non-MacBridge QR, pair, then confirm clipboard exchange.

## Android interface

### Nearby Mac discovery

The native Mac companion advertises `_macbridge._tcp` with Bonjour while its server runs. Android **Devices** lists nearby Macs and marks saved Macs found on the local network. Tap **Connect** on a saved Mac to use its discovered address when its ID and public-key fingerprint match the saved identity; the TLS connection still verifies the original pinned key. Discovery never pairs a Mac or changes permissions. New Macs use **Pair with QR code**, with pasted codes available as before.

Discovery is callback-driven, caps its catalog at 32 services, resolves one service at a time, and clears stale results on network changes or shutdown. **Refresh** restarts discovery. If multicast or local-network access is unavailable, QR/pasted pairing and saved addresses remain available. Bonjour contains public identity metadata only, never pairing secrets or private keys.

The Mac listener accepts IPv4 and IPv6 where dual-stack sockets are supported. Android prefers IPv4; older Android versions expose only the address selected by their NSD resolver. IPv6-only automatic pairing-address selection is still limited: the CLI supports an explicit IPv6 `--address`, while automatic Mac address selection currently uses IPv4. The standalone CLI does not publish Bonjour.

The Android app uses a black-and-white palette with soft decorative blur behind the Home connection card. Text and controls are never blurred; older Android versions retain the gradient background.

- **Home**: connection status and pair/connect/cancel, with direct Clipboard and Files shortcuts once a Mac is paired. If several Macs are saved, choose one in Devices.
- **Share**: Clipboard and Files tabs. Send documents in either direction with verified progress, cancellation and Save As for received files, or send current phone text, instructions for receiving Mac text, and local history with copy, expand and confirmed clearing. Send actions are disabled when disconnected, blocked, in demo mode or when the latest stored sharing permission is off.
- **Devices**: paired Macs first, clipboard, file and notification sharing permissions, connect/disconnect, identity disclosure and confirmed removal. Scan or paste to add another Mac.
- **Settings**: sharing permissions, notification setup with app filters and hidden previews by default, Android battery settings, and security diagnostics. Prototype simulator and benchmark actions are outside the everyday navigation.

The selected tab and scroll positions survive recreation; pairing secrets stay in memory only. Navigation disposes pending scanner callbacks. Automated UI checks cover offline/reconnecting states, revoked permissions, multiple Macs, confirmations, navigation restoration, blocked peers and narrow screens with enlarged text.

## Send a file to your Mac

Enable **Devices → File sharing** for the chosen Mac on Android and **Allow file receiving** in the Mac menu-bar companion. Receiving starts off on the Mac and its toggle is saved across restarts. Open **Share → Files → Choose a file**, then select a document with the system picker. Files are limited to 100 MB; only one outgoing transfer runs at a time.

The app takes a bounded private snapshot, sends 64 KB chunks over the authenticated TLS session and waits for each acknowledgement. It marks a transfer complete only when the Mac confirms the exact byte count and SHA-256 checksum. Zero-byte files are supported. The Mac saves verified files in `~/Library/Application Support/MacBridgeDev/ReceivedFiles`; **Show Received Files** opens that folder. Files are never opened automatically.

Disconnects and acknowledgement timeouts pause a prepared transfer. Reconnect the same paired Mac, then tap **Resume transfer** in Android file history within 10 minutes. Resume uses the original private snapshot, even if the selected document changed. Cancellation and permission revocation discard partials. After either app reopens, reconnect the original peer and resume from its saved checkpoint within the same ten-minute recovery window. Completed files remain. Send with the in-app picker or share one document directly from another Android app.

File tests cover normal multi-chunk and empty transfers, Unicode names, checksum rejection, ordering and malformed chunks, oversized documents (including unknown sizes), unrelated-peer acknowledgements, revoked document access, disabled sharing, timeouts, disconnects, cancellation, duplicate names, disk-space rejection and shutdown/restart cleanup. The Android-to-Python integration test verifies actual TLS delivery and cancellation against an isolated receive folder.

The Android interface uses monochrome panels and a two-option Clipboard/Files selector. The Mac companion follows the native menu-bar pattern: a compact system menu with connection status, clipboard sending, the received-files folder and sharing checkmarks. Pairing, device management and detailed status open in a separate settings window. The menu follows macOS appearance and keyboard navigation, and daily actions require no scrolling. Android recovery actions remain reachable with enlarged text.

## Send a file from Mac to phone

Connect the phone and enable **Devices → File sharing** for this Mac on Android. Choose **Send File to Phone…** in the Mac menu, then select one regular file up to 100 MB. The Mac snapshots the selected file privately, sends bounded chunks, and reports delivery only after Android confirms the exact size and SHA-256 checksum. The send is tied to the connection that was active when the picker opened; a connection change before the picker finishes requires choosing the file again. Once prepared, an interrupted send can resume to the same paired phone. **Cancel File Sending** discards an active or paused send. The menu shows preparation, progress and the final result without adding a scrolling dashboard.

On Android open **Share → Files**. Incoming transfers have a **Mac → Phone** label. Verified files stay in app-private storage until you tap **Save As…** and choose a destination in the system document picker. Cancelling that picker leaves the private copy intact. Save As rechecks the source checksum; access or provider failures keep the verified copy available for retry and attempt to remove any failed export if the provider supports deletion. Files are never opened automatically, and no broad storage permission is requested. Private received copies remain saved after export.

One outgoing transfer, including a paused transfer, is allowed at a time. After interruption, reconnect the original phone and choose **Resume File Sending** in the Mac menu within 10 minutes. The menu disables Resume for another paired phone. Android resumes only the same Mac identity with File sharing still allowed. Cancel is available while paused. Partial files remain private and cannot use Save As. A receiver without a retained partial or receipt rejects resume, so an expired, corrupt or forgotten receiver cannot silently start a duplicate transfer. Both receivers report their confirmed offset; senders validate chunk alignment and bounds before seeking the original snapshot. A lost final acknowledgement can be recovered from a bounded persisted verified receipt without publishing a duplicate file.

Resume survives app/process restarts using private atomic checkpoints and immutable source snapshots. Permission revocation, cancellation or expiry discard partials; completed copies survive. The Mac retains up to four peer-scoped receivers, each bounded to 100 MB, and 64 recent receipts per receiver. Android retains one incoming partial and 64 recent receipts. Resuming rehashes private snapshots and received prefixes; final size and SHA-256 verification still gate publication.

### Send from another Android app

Select one file in Files, Photos, or another app, choose **Share → MacBridge**, review the filename and destination, then tap **Send file**. If disconnected, connect the selected paired Mac first; connecting does not automatically send. Turn on File sharing for that Mac in Devices and enable receiving in the Mac companion. **Manage devices** preserves the pending file and shows a **File waiting to send · Review** link when you return. After sending starts, Home shows progress and cancellation; completion still requires the Mac's checksum acknowledgement.

MacBridge accepts granted `content://` document streams, including a single ClipData URI. It rejects multi-file shares and ambiguous/unsafe inputs with guidance rather than sending only part of a selection. A file attachment takes precedence over a text caption; text-only sharing retains its clipboard behavior. Metadata runs off the UI thread. Unknown sizes are checked while preparing the private snapshot; files over 100 MB, revoked access, and provider failures cannot produce verified delivery. Rotation restores an unsent review without replaying the launch intent. Pending duplicate deliveries are ignored, and a two-second guard suppresses immediate duplicate delivery after sending; cancelling allows an immediate retry. Starting another send requires finishing, resuming, or cancelling the existing outgoing transfer.

File access is temporary and controlled by the source app. If access is lost after process restart or while preparing the snapshot, share the file again from its source. Once prepared, interrupted transfer recovery uses the private snapshot. The selected Mac's ID and pinned fingerprint are checked again by the send engine so changing the connection after review cannot redirect the file to another peer.

Resume checks exercise real TLS reconnects in both directions, confirmed-offset continuation, lost-final-ack receipts, changed source documents, corrupt snapshots, original peer identity, expiry, permission revocation and paused cancellation. Reverse-transfer checks also cover real Python-to-Android TLS delivery, empty and multi-chunk files, Unicode and safe names, malformed metadata/chunks, duplicate identities, checksum rejection, permission changes, unrelated acknowledgements, cancel/disconnect/timeout/shutdown cleanup, restart recovery and Save As permission or source failures. System picker interaction on a physical phone remains a manual check.

## Restart-safe transfer recovery

After an interruption or app restart, reconnect the same paired device and choose **Resume transfer** on Android or **Resume File Sending** on Mac. Recovery remains available for ten minutes, including time while an app is closed; restarting alone does not extend a saved deadline. Sending is user-initiated. Changing or forgetting a paired identity, disabling its file permission, cancelling, or expiry invalidates its partial recovery data. Significant wall-clock changes invalidate saved deadlines rather than retaining files indefinitely.

Checkpoints use atomic metadata replacement in private storage, with bounded relative filenames and the original pinned peer identity. Source snapshots and received prefixes are rehashed before continuation. Receivers checkpoint data at most every 250 ms during progress, then force a checkpoint when paused or stopped gracefully. After an abrupt exit they may return an earlier durable chunk offset and safely request those chunks again; acknowledgement counts remain exact during a running session. Uncheckpointed trailing bytes are truncated before resuming. A receiver never creates a fresh file in response to an unknown resume request.

Verified publication is journaled before the final rename. Restart can finish a verified publication interrupted between rename and history/receipt storage, and persisted receipts recover a lost final acknowledgement without another saved copy. Receipts are bounded to 64 per receiver and ten minutes; their referenced file is reverified when a completion receipt is requested, keeping startup from hashing every historical file. Invalid or missing recovery state is discarded, while previously completed files remain available. Process restart checks recreate Android managers and exercise a forcibly killed Mac server over real authenticated TLS; manual whole-device reboot and sudden power-loss testing remain separate checks.

## Flow and presentation performance

Home leads directly to Clipboard or Files; the Share selector remembers each page’s scroll and help state. Sharing instructions are expandable, and active/paused transfers appear before completed history. Settings focuses on sharing permissions, battery access and diagnostics. File permission descriptions cover both directions, and Clipboard/Files use the same current-identity and permission checks. Save As disables duplicate picker launches and shows Saving only on the file being exported. The native Mac menu shows Resume/Cancel only when relevant; its controls still use system appearance and keyboard navigation.

Transfer progress database writes and Mac progress reports are limited to one every 250 ms. Every chunk still gets its protocol acknowledgement, and pause/failure/completion records update immediately with exact counts. Transfer watchdogs wait for activity instead of waking on idle 250 ms timers. The native controller avoids publishing unchanged fields; the pairing countdown caches one QR raster and reuses its Core Image context, replacing/clearing the raster when the code rotates or expires.

Deterministic 128-chunk (8 MB) checks preserve all 129 acknowledgements: Android uses at most three outgoing history writes and two incoming writes when the presentation clock does not advance; the Mac uses 24 reports with simulated 50 ms chunk intervals, compared with the previous 130 reports. A 300-request QR countdown check uses one raster. These are work-count regressions, not measurements of physical-device throughput, frame rate or battery life. UI checks cover direct routes, page restoration, collapsed help, paused recovery, duplicate Save As prevention, current permissions/identity, and narrow screens with enlarged text.

## Transfer progress and recovery flow

The Mac menu shows concise **Sending to Phone** and **Receiving from Phone** progress with percentage and transferred/total bytes, plus paused, failed, cancelled and verified outcomes. **Settings → General** shows native progress bars for both directions. Preparing and verification have distinct labels; byte counts alone never claim verified delivery. Stopped engines clear stale progress. Receiving status updates are coalesced to 250 ms while all protocol acknowledgements and terminal states remain immediate. The native controller drains diagnostics in bounded chunks and prevents duplicate file-picker panels.

Android shows current transfers before the send panel and recent files, with matching percentage/byte labels and readable byte-sized files. Resume comes before Cancel on paused sends; cancellation remains a full-width touch target. Existing connection, identity and permission checks remain in force. Automated checks cover bounded/invalid counts, empty files, incoming status decoding, paused/completed presentation, and 128 incoming chunks producing two status reports with a fixed presentation clock while preserving all 129 acknowledgements. These work-count checks do not measure physical throughput or frame rate.

### Cancelling a transfer

Both devices show sending and receiving progress. On Android, use **Cancel sending** or **Cancel receiving** in the current transfer card. On Mac, use **Cancel File Sending** or **Cancel File Receiving** in the menu bar, including for a paused incoming transfer. Cancellation discards the unverified partial or private outgoing snapshot and notifies the original authenticated peer when connected; completed verified files remain available.

A disconnected receiver can discard its partial locally. The sender learns that the transfer cannot continue when it reconnects and attempts to resume; an offline peer cannot receive an immediate cancellation notification. A new transfer must be started after cancellation. Mac receiving actions carry a transfer and peer token so delayed actions cannot cancel a replacement transfer.

### Transfers on Android Home

Home shows live sending and receiving progress above the connection card, including filenames, percentages and byte counts. Cancel either direction directly, or resume a paused send after reconnecting. The latest verified incoming file has **Save As…** on Home, even while an outgoing transfer continues. When transfers finish, their latest outcomes appear below the connection card. Home retains only the latest outcome in each idle direction; the complete history stays in Share → Files. Home and Files use the same save, resume and cancel handlers and verification rules. Save As keeps file/provider copying on IO and returns to the main thread for completion feedback and UI state; failed saves remain retryable. Resume feedback follows the same UI-thread rule.

### App icon

Android and Mac share a white suspension-bridge mark on charcoal, matching the monochrome interface. Android includes adaptive and themed monochrome icons plus legacy density and round variants. The Mac companion bundle includes its native ICNS icon; its menu-bar connection symbol remains the standard compact status glyph.

The editable geometry lives in `assets/branding/icon.json`, with SVG and PNG previews alongside the Mac ICNS. Regenerate all platform assets on macOS with `python3 tools/generate-icons.py` (Python 3 and Command Line Tools; no third-party packages). Generated launcher resources and the ICNS are committed so normal app builds do not require regeneration.

## Mirror phone notifications

On Android, enable **Devices → Notification sharing** for your Mac. Open **Settings → Set up notifications → Open notification access**, grant Android access, then select apps. Apps start off and appear in the list after posting an alert (use **Refresh apps**). Current alerts are used only to identify available apps; their content is never replayed. On Mac, open **Settings → Notifications**, turn on **Allow phone notifications**, and approve the macOS notification request. macOS also controls banner visibility in System Settings → Notifications.

New alerts from selected apps are forwarded only to the connected, authenticated Mac. **Show message previews** starts off: the Mac receives the app name and a generic alert. Enable previews deliberately to include message text. Secret, ongoing and group-summary notifications are excluded. Updates replace the same alert; duplicate content does not create another banner. Removing an alert on Android removes its Mac mirror. Disabling an app, hiding previews, revoking Android access or disconnecting clears the applicable Mac alerts. macOS permission revocation is checked when the companion becomes active and after delivery failure.

Notification content is not saved to Android history, engine files or diagnostic logs. In-memory catalogs cap at 100 alerts and event queues at 64. Delivery is best effort: bursts can be dropped, missed updates are not replayed after reconnect, and there is no delivery receipt. macOS may retain displayed alerts in its own Notification Center until removed. Eligible alerts offer an explicit Dismiss on Phone action; supported alerts also offer Reply. The standalone CLI does not display notifications.

A manual device check should enable both systems’ access, select one app, receive a redacted alert, enable previews and receive an update, remove it on Android, disable the app, revoke access, and disconnect/reconnect without old content replay. Native catalog and permission/session logic are covered automatically; actual macOS banner placement, Android system permission dialogs and system cancellation require this manual check.

### Dismiss on Phone

On an eligible Mac alert, choose **Dismiss on Phone** to request removal from Android. macOS may reveal notification actions on hover or in Notification Center. Closing the Mac alert with its normal close control affects only the Mac. Alerts without current phone cancellation support have no remote dismiss action.

Dismiss handles are one-use, remain in memory, and bind to the notification generation, authenticated Mac identity and connection. Updating/removing an alert, disabling its app or Mac permission, hiding previews, losing Android access, restarting or reconnecting invalidates old actions. Android rechecks the live notification’s package, posting time and clearability before cancellation; secret, ongoing and group-summary notifications cannot be dismissed through this feature. **Phone received dismiss request** means the system cancellation call was accepted; removal is confirmed by the listener event. Failed or stale requests are not replayed.

Android exposes cancellation by notification key rather than an atomic generation comparison; a source app can update an alert between the final live check and the platform call. Automatic checks cover handle/session/permission rejection and real TLS request/result/removal routing. Actual macOS action-button behavior and Android system cancellation remain a manual device check tracked on GitHub.

### Reply from Mac

Enable **Show message previews** on Android, then choose **Reply** on a supported Mac notification, type your message and choose **Send**. The phone must be unlocked. Replies require Android 12+ so the bridge can inspect action mutability and type. Only one unambiguous free-form RemoteInput action belonging to the notifying app is supported; choice-only, activity-launching, immutable, foreign-app and authentication-required actions offer no Reply button. Other notifications still mirror normally.

Each reply uses its own one-use generation/identity/session handle. Android rechecks access, per-Mac/app sharing, previews, lock state, the live notification and original app action before passing RemoteInput text to the original PendingIntent. Reply text is limited to 4096 UTF-8 bytes, supports Unicode/newlines, and rejects blank, malformed or control-character input. Text and app action capabilities stay in bounded memory; they are not saved in bridge history or logs. A failed/stale action is never automatically retried; wait for a new app notification or reply on the phone.

**Reply passed to phone app** reports acceptance of the PendingIntent call, not delivery to the message recipient. The source app controls delivery and may require its own permissions/network state. Android provides no atomic notification-generation check and PendingIntent send, and source apps can update a PendingIntent's backing extras; the bridge cannot guarantee an immutable conversation destination beyond the original capability and live generation checks. Actual third-party app replies and native text-entry UI remain manual acceptance work in issue #24.
