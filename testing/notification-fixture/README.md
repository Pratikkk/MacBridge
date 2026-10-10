# Physical notification flow fixture

This separate Android test app posts controlled notifications and records replies locally. It never contacts a network or messaging app. Keep real user alerts out of the test. Android 12+ is required.

Build with the repository's local debug keystore and Android SDK tools:

```sh
ANDROID_SDK_ROOT=/path/to/sdk testing/notification-fixture/build.sh
adb -s SERIAL install --no-incremental -r build/notification-fixture/fixture.apk
```

Use JDK 11+ (`JAVA_HOME`/`PATH`). The defaults are platform `android-36.1` and build tools `36.0.0`; `FIXTURE_PLATFORM` and `FIXTURE_BUILD_TOOLS` override them. Generated APKs/classes stay under the ignored root `build` directory.

Open **MacBridge Test Notifications**, allow notifications through the system prompt and tap **Post reply test** once so MacBridge discovers the fixture. Connect MacBridge to the Mac, enable notification sharing for that Mac and select the fixture in Android notification settings. macOS alert access and companion notification reception must be enabled.

For replies, enable previews with only the test fixture selected and keep the phone unlocked. Record the original filter/preview choices and restore them afterwards. Previews expose selected apps' message content to the Mac; do not enable them for real apps just to run this test.

The launcher buttons post reply, unsupported, secret, ongoing, summary, immutable, authentication-required and ambiguous-action variants, or remove only the fixture alert. ADB control is also available, protected by the system `DUMP` permission:

```sh
adb -s SERIAL shell am broadcast -a com.macbridge.notificationfixture.TEST \
  -n com.macbridge.notificationfixture/.FixtureReceiver --es mode reply
```

Modes: `reply`, `unsupported`, `secret`, `ongoing`, `summary`, `immutable`, `auth`, `ambiguous`, `remove`, `reset`. Reset clears only local fixture reply results and the fixture alert. The reply receiver is not exported and is reachable only through its notification PendingIntent.

Recent Android versions reject immutable RemoteInput actions before posting. The fixture reports that restriction with a toast and records `posted=false` in its local `shared_prefs/post_results.xml`, without crashing or retaining an old alert. On those devices, immutable capability rejection remains covered by the Android automated tests; do not claim a physical immutable mirror was tested. Successful posting records `posted=true` and its mode in the same file.

Physical acceptance checklist:

- With previews off, the Mac shows a redacted alert and no Reply. The eligible alert still supports Dismiss on Phone.
- With previews on, verify Unicode title/body and native Reply / Send. Send a synthetic message such as `Hello 世界 🌉`; verify the fixture's `results.xml` has exactly one receipt and the expected text. This proves local app dispatch, not real messaging-app delivery.
- For a one-Send measurement, use `reset`, verify empty `results.xml`, then post a new reply alert and invoke Send exactly once. Recheck after subsequent checks to detect delayed duplicate receipts.
- Close the Mac alert normally; the fixture alert must remain on Android. Repost and use Dismiss on Phone; only the fixture alert must disappear on Android.
- Repost/update/remove; stale handles must not execute. Unsupported/immutable/authentication-required/ambiguous variants must offer no Reply. Secret/ongoing/summary variants must not appear on Mac.
- Disable the fixture filter, previews or notification sharing; old mirrors/actions must clear. Disconnect/reconnect or restart the companion; old notifications must not replay.
- Separately observe system permission revocation and restore it through the OS UI. Do not treat automated checks as proof of OS dialogs or actual banners.

Read only the fixture's results after a known synthetic reply:

```sh
adb -s SERIAL shell run-as com.macbridge.notificationfixture cat shared_prefs/results.xml
```

The fixture can remain installed for repeat testing. Do not uninstall MacBridge or clear its data. Physical acceptance is tracked in repository issue #24 and stays open until observations are recorded.
