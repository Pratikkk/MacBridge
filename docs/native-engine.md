# Native Swift engine

The installed macOS 15+ companion launches `MacBridgeEngine` as a direct child of the SwiftUI app. Apple Network.framework provides TLS 1.2 or later and asynchronous socket callbacks; CryptoKit verifies the existing P-256 challenge protocol. The UI uses the same bounded JSON event/control pipes and unchanged sharing preferences. Production bundles contain no Python engine resources and require no Python/OpenSSL installation. Python/OpenSSL are used only by development reference tests and build/install tooling.

## Identity and migration

The existing signed `MacBridgeIdentity` helper remains unchanged. Its app → engine → helper caller check still runs before Keychain access. The engine preserves the stored public key, certificate, device ID and peer pins. Missing, denied, malformed or mismatched identities fail closed. Legacy file migration validates the public key, adds and reads back the Keychain value, builds a working TLS identity, then saves the public migration record before removing the unchanged legacy file.

The engine constructs a PKCS#12 container in memory with AES-256-CBC/PBKDF2 and SHA-256 password integrity, using a transient container password rather than collecting a user password. Security imports with `kSecImportToMemoryOnly`; this flag requires macOS 15, which the user selected as the app minimum. No container or private key is written to disk by the production engine. Security may hold copies in process memory; this is not a non-exportable Secure Enclave key. New identities and same-key renewal use native DER X.509 generation with CryptoKit ECDSA signatures. Certificate serial integers are minimally encoded and checked against OpenSSL in regression fixtures.

## Transfers and lifecycle

Strict framing rejects malformed UTF-8, duplicate fields, non-finite numbers, excessive nesting and frames over 1 MiB. Type checks distinguish booleans and integers. Four unauthenticated connection slots have ten-second authentication deadlines; authenticated idle connections have a 45-second deadline. Engine-wide monotonic rate budgets survive reconnects. Notification bodies and action handles remain in memory and are scoped to the current phone session.

Transfers are sequential and ACK driven, with one outstanding outgoing chunk and a cancellable deadline. Disk work runs in the separate engine's utility queue, never the UI process's main thread. Progress/checkpoint updates are coalesced to 250 ms; terminal states remain immediate. There are no idle polling timers or busy-wait loops. Native callbacks and stdin EOF/termination stop the listener and preserve valid paused transfers.

Receiver/sender checkpoints retain the existing identity binding, filenames, SHA-256 prefix/snapshot validation and ten-minute resume window. Existing Python-era checkpoints migrate without changing pairing. A verified publication is journaled before rename, retaining lost-final-ACK recovery. Storage quotas preserve completed files; permission revocation removes partials and resume receipts. Cancellation tokens protect a different transfer from a stale UI command. Native fixture mode requires an explicit isolated state directory and loopback host; it cannot load a migrated production Keychain identity or operate the real clipboard.

## Reproducible checks

Run `mac/native/test-app.sh` first; it builds the debug engine and runs native protocol, isolated TLS interoperability, UI and Keychain helper checks. Then run Android's `:app:assembleDebug :app:testDebugUnitTest` and the Python reference engine suite. Eight Android interoperability cases run the existing real Android transport and file-receiving manager against the Swift engine; Gradle tracks the native binary and adapter as test inputs so engine changes invalidate cached results. Native Android cases run on macOS and require the debug engine to be built first.

`mac/native/build-app.sh` builds and signs the production app with a macOS 15 minimum. Verify using `codesign --verify --strict --deep mac/native/dist/MacBridge.app`. Installed-app migration and saved-phone reconnect remain separate physical acceptance checks; native fixtures never use live identities, clipboard content or alerts. Developer ID signing/notarization, broader independent security review, third-party notification UI acceptance and real login/logout/update acceptance remain separate release work.
