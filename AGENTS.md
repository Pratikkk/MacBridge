# Project workflow

- After each completed feature, run the appropriate build and meaningful normal-path and edge-case checks before committing and pushing to this project's configured GitHub remote. The user has authorized this workflow for future feature work.
- For affected code, cover applicable failures such as disconnected peers, reconnects, malformed or oversized input, Unicode, expired or reused pairing codes, identity changes, permission revocation, and process shutdown. Add regression checks for bugs found during development.
- Commit the feature's source, tests, documentation, and required reproducible build configuration. Exclude IDE state, generated APKs/apps, local paths, runtime identities, private keys, and live pairing secrets.
- Push the feature commit on the working branch after required checks pass. Do not force-push. If remote changes or branch protections prevent pushing, resolve safely or report the specific blocker.
- Verify the pushed commit against the remote branch. Report what changed, the validation results, and any remaining limitations; do not claim every possible edge case was tested.
- After each successfully validated Android feature, install the built APK on the connected, authorized physical Android device using `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`, preserving existing app data and pairing. Verify installation. If no physical device is connected or authorized, report the blocker; if multiple physical devices are connected, ask which to use. Never uninstall or wipe app data to work around an installation failure without explicit authorization.

## Validation commands

- Android: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (Android SDK and JDK required; generate the local debug keystore as documented).
- Mac engine: `python3 -m unittest discover -s mac -v` (Python 3, OpenSSL and local socket access required).
- Native Mac checks: `mac/native/test-app.sh`.
- Native Mac app: `mac/native/build-app.sh`, then verify the bundle signature. The Mac UI requires macOS 13+; standalone Swift checks work with Command Line Tools.
- Review `git diff --check` and the exact commit contents before pushing.
