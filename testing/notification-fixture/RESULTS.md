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
- Posting the controlled reply fixture produces one active Android fixture alert. No synthetic reply has been recorded yet.
- During the retry, the companion reported “Phone received dismiss request” and the fixture alert was absent on Android. The exact Mac button interaction was not observed, so this is supporting evidence rather than a completed native-action acceptance check.
- Fixed the fixture launcher layout to apply system-bar insets and use a scrollable button list. The rebuilt fixture is installed with matching APK SHA-256; device UI inspection confirms the first post button and remove button are accessible below the system bars.
- AppleScript inspection of Notification Center was explicitly authorized, but macOS denied assistive access. Native-action testing requires that system permission before proceeding.

Actual banner/action inspection, normal-close isolation, directly observed Dismiss on Phone, native text entry, fixture reply receipt, permission revocation and reconnect observations are **pending**. No real user alerts were operated by the test controls and no replies were sent to real messaging apps. Installation and automated checks do not prove the physical flow. Complete the checklist in README.md; repository issue #24 remains open.
