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

## Android release signing (pending)

The existing Gradle release config reads `KEYSTORE_PATH`, `STORE_PASSWORD` and
`KEY_PASSWORD`, with alias `upload`. Use an owner-managed keystore outside the
checkout and supply credentials through a protected local/CI environment. The
debug keystore is not a release identity. Android release build, signature
verification and clean-install/upgrade acceptance remain outstanding. A device
with the debug APK cannot normally upgrade to a differently signed release APK;
do not uninstall it or clear pairing to work around this without user approval.

Release readiness is tracked in [issue #16](https://github.com/Pratikkk/MacBridge/issues/16).
