# Notification flow verification status

Automated checks passed for the implemented notification flow:

- 23 Android notification/TLS tests, including actual RemoteInput/PendingIntent routing to a local test receiver, canceled/unsupported capabilities, Unicode/bounds, session/permission rejection, and authenticated mirroring/dismiss/reply transport checks.
- 70 Mac engine tests and 18 native checks, including existing transfer/pairing regressions and notification catalogs/actions/session teardown.
- Physical fixture APK built and signature verified, then installed successfully on the authorized phone. Manifest checks confirm no network permission, system-DUMP-protected test control and a non-exported reply receiver.

Physical setup and retry observations:

- Android MacBridge listener access enabled.
- macOS alert permission allowed and companion notification reception enabled.
- Both phone and companion now report connected.
- Fixture notification permission approved through its Android system prompt; fixture selected in MacBridge's notification filter. Existing user filter and preview choices were preserved.
- Native Mac alert inspection confirmed the fixture's Unicode title/body and Reply / Dismiss on Phone actions.
- Native Reply / Send dispatched the synthetic Unicode text `Single-send check · 世界 🌉` to the local fixture. After resetting and verifying an empty counter, exactly one Send produced count 1 with exact text; the count remained 1 after later edge checks. An earlier non-isolated attempt showed count 2; its baseline was not controlled, and duplicate delivery did not reproduce in the isolated measurement.
- Normal Mac Close left one fixture notification on Android. A fresh alert's explicit native Dismiss on Phone removed it from Android (count 0).
- Unsupported, authentication-required and ambiguous-action variants appeared on Mac with Dismiss but no Reply. Secret, ongoing and group-summary variants produced no fixture mirror.
- Android removal cleared the fixture mirror. Normal reply posting still worked after the negative-mode checks.
- Fixed the fixture launcher layout to apply system-bar insets and use a scrollable button list. The rebuilt fixture is installed with matching APK SHA-256; device UI inspection confirms the first post button and remove button are accessible below the system bars.
- macOS Accessibility permission was enabled by the user, who also explicitly authorized local Notification Center content inspection. Only fixture actions were operated; unrelated alert contents were excluded from test output and repository updates.
- The physical immutable variant uncovered an Android platform restriction: immutable RemoteInput actions are rejected before posting. Fixed the fixture to record that rejection and show a toast rather than crash. Regression observation: `posted=false`, fixture process remains alive, no stale fixture alert; subsequent normal posting records `posted=true` and exposes native Reply. Rebuilt/signed fixture installed with data preserved and matching APK checksum.

Previews-off redaction, filter/permission revocation, stale-action execution, reconnect/restart and real messaging-app delivery observations remain **pending**. Physical immutable mirror inspection is unavailable on this Android version; automated capability rejection covers it. No real user alerts were operated by the test controls and no replies were sent to real messaging apps. The controlled local reply proves app dispatch, not recipient delivery. Repository issue #24 remains open for unfinished acceptance checks.
