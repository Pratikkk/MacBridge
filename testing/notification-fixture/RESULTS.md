# Notification flow verification status

Automated checks passed for the implemented notification flow:

- 23 Android notification/TLS tests, including actual RemoteInput/PendingIntent routing to a local test receiver, canceled/unsupported capabilities, Unicode/bounds, session/permission rejection, and authenticated mirroring/dismiss/reply transport checks.
- 70 Mac engine tests and 18 native checks, including existing transfer/pairing regressions and notification catalogs/actions/session teardown.
- Physical fixture APK built and signature verified, then installed successfully on the authorized phone. Manifest checks confirm no network permission, system-DUMP-protected test control and a non-exported reply receiver.

Initial physical setup inspection:

- Android MacBridge listener access enabled.
- macOS alert permission allowed and companion notification reception enabled.
- Companion reports no phone connected.
- No Android apps selected for mirroring; previews off.
- Fixture notification permission not yet approved. This device rejects shell runtime-permission grants, so approval must use its normal on-screen prompt.

Actual banner delivery, normal-close isolation, Android removal through Dismiss on Phone, native text entry, fixture reply receipt, permission revocation and reconnect observations are **pending**. No real user alerts were dismissed and no replies were sent to real messaging apps. Installation and automated checks do not prove the physical flow. Complete the setup and checklist in README.md; repository issue #24 remains open.
