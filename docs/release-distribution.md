# Release packaging

MacBridge is currently a local development build. Production signing credentials
will be configured later. This workflow prepares artifacts; it does not publish a
GitHub release, install an app, or change the installed app's Keychain grant.

## Mac preparation

Use macOS 15+, Command Line Tools and Python 3 for the packaging tool. The app
itself has no Python dependency. Build and validate before packaging:

```sh
mac/native/test-app.sh
mac/native/build-app.sh
python3 -m unittest discover -s mac/native -p 'test_package_release.py' -v
python3 mac/native/package_release.py --development \
  --output mac/native/dist/local-package
```

Choose a fresh output directory for each attempt. Existing output is never
overwritten. The tool validates the expected bundle files, rejects symlinks and
unexpected resources (including runtime secrets), verifies the signature, and
packages an isolated copy. Local archives include `development` in the filename
and manifest. They are not suitable for public Gatekeeper distribution.

Artifacts include a versioned ZIP, `SHA256SUMS`, and `release.json` with version,
build, architecture, minimum OS, signing status and checksum. ZIP timestamps are
not normalized: reproducible build instructions do not imply byte-identical
archives. The current build targets the build machine's architecture; produce
and test separate Apple Silicon and Intel artifacts where needed.

## Developer ID and notarization (pending physical acceptance)

Install your **Developer ID Application** certificate and its private key in
Keychain yourself. Configure a `notarytool` Keychain profile yourself using
Apple's supported account credentials. Never put passwords, private keys,
keystores or API-key files in the repository or send them in chat.

```sh
python3 mac/native/package_release.py \
  --identity 'Developer ID Application: YOUR NAME (YOUR TEAM)' \
  --notary-profile 'MacBridge-notary' \
  --output mac/native/dist/notarized-package
```

`--notary-profile` explicitly submits the staged ZIP to Apple. The tool signs
the helper and engine before the outer app, with hardened runtime and secure
timestamps, waits for Accepted, staples and validates the ticket, verifies the
signature and Gatekeeper assessment, then creates the final ZIP. Failed checks
publish no output directory. Without a profile, a Developer ID archive is
clearly labeled `unnotarized`; it is a preparation artifact.

If Apple rejects a submission, inspect its log locally with `xcrun notarytool
log SUBMISSION_ID --keychain-profile MacBridge-notary`. Logs may include account
details; review and redact before sharing. Account secrets are not CLI options
of this packaging tool.

This follows Apple's [notarization requirements](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)
and [custom workflow](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow).
Actual Developer ID signing, Apple acceptance and a downloaded/quarantined
artifact launch have not been validated yet.

## Install and update acceptance

Quit MacBridge normally before replacing it. Extract the ZIP and move
MacBridge.app to Applications, replacing the prior app. Keep its Application
Support directory and Keychain identity; never delete them to fix an update.
Launch the app, reconnect with the saved pairing and check clipboard/files and
notification controls. A switch from local signing to Developer ID may require
owner Keychain approval: enter credentials only in macOS system prompts.

Before public distribution, test a clean install on a separate Mac with normal
Gatekeeper protections, an upgrade retaining identity/pairing and transfer
checkpoints, supported architectures, and Open at Login across updates. Do not
disable Gatekeeper or reset an identity as a release test workaround.

## Android APK preparation

The existing Gradle release config reads `KEYSTORE_PATH`, `STORE_PASSWORD` and
`KEY_PASSWORD`, with alias `upload`. Use an owner-managed keystore outside the
checkout and supply credentials through a protected local/CI environment. The
debug keystore is not a release identity. The packaging tool additionally requires
`MACBRIDGE_RELEASE_CERT_SHA256`, the expected owner's public certificate
SHA-256 fingerprint (hex, with optional colons). Obtain and verify that fingerprint
locally using the owner's key-management tools; do not guess it from an arbitrary
previous APK. The tool fails before building if credentials are missing or the
keystore is inside the repository.

With these variables already supplied through your protected environment:

```sh
python3 -m unittest discover -s tools -p test_android_release.py -v
python3 tools/android_release.py \
  --build-tools "$ANDROID_HOME/build-tools/37.0.0" \
  --output release-artifacts/android-release
```

Use an installed SDK Build Tools directory containing `apksigner` and `aapt`,
and a JDK supported by this project's Gradle version. The script reruns
`:app:assembleRelease`, copies the APK into temporary staging, verifies its
signature and expected certificate using `apksigner`, and checks package identity,
non-debuggable status, numeric version, min SDK 24 and target SDK 36 using `aapt`.
It then produces a versioned APK, `release.json`, and `SHA256SUMS` in a fresh
output directory. No artifact directory is published after failed checks;
existing output is never overwritten. Credentials are passed through environment
variables, not CLI password arguments, and command output is suppressed to avoid
leaking signing configuration. On build failure, diagnose the local Gradle
configuration privately before sharing any logs.

Run the actual disposable-key build regression when the SDK and JDK are ready:

```sh
MACBRIDGE_TEST_BUILD_TOOLS="$ANDROID_HOME/build-tools/37.0.0" \
  python3 -m unittest discover -s tools -p test_android_release.py -v
```

Set `JAVA_HOME` to your supported JDK. This test generates a temporary key with
alias `upload`, builds/verifies a release APK, and deletes its temporary key and
artifact directory afterwards. It never uses the owner's production credentials
or installs anything on a device. Without the opt-in variable, the actual build
test is skipped and the parser/preflight/failure tests still run.

`--test-signing` explicitly labels disposable-key artifacts as `test-only` and
adds `test-signing` to the APK filename. Validation used a generated, disposable
keystore outside the repo, not an owner production key. These test artifacts
must not be distributed or installed over the daily-use debug app. The tool
does not install APKs or publish releases. Build times/signatures can vary;
checksums identify a specific artifact rather than claiming byte reproducibility.

APK verification follows the Android [apksigner documentation](https://developer.android.com/tools/apksigner).
Production certificate selection, key backup and release continuity follow
Android's [app signing guidance](https://developer.android.com/studio/publish/app-signing).

Owner-key release validation and physical clean-install/upgrade acceptance remain
outstanding. A device
with the debug APK cannot normally upgrade to a differently signed release APK;
do not uninstall it or clear pairing to work around this without user approval.
This workflow prepares direct-distribution APKs; Google Play app-bundle publishing
and Play App Signing are not implemented here. Keep the signing key stable for
direct APK updates, and raise the version code before publishing a newer build.

Release readiness is tracked in [issue #16](https://github.com/Pratikkk/MacBridge/issues/16).
