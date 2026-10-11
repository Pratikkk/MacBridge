#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p .build/checks
swiftc Sources/MacBridge/CompanionState.swift Sources/MacBridge/PairingQR.swift Sources/MacBridge/BonjourPublisher.swift Sources/MacBridge/NotificationPresenter.swift Sources/MacBridge/LoginItem.swift \
  Tests/MacBridgeTests/CompanionTests.swift -o .build/checks/MacBridgeChecks
.build/checks/MacBridgeChecks
swift build -c release --product MacBridgeIdentity
identity_helper="$(swift build -c release --show-bin-path)/MacBridgeIdentity"
python3 - "$identity_helper" <<'PY'
import hashlib
import subprocess
import sys
helper = sys.argv[1]
account = hashlib.sha256(b'MacBridge isolated missing identity check').hexdigest()
for arguments in [('invalid', account), ('read', 'invalid')]:
    result = subprocess.run([helper, *arguments], capture_output=True, timeout=15)
    assert result.returncode == 2 and not result.stdout, 'Invalid helper request was not rejected'
result = subprocess.run([helper, 'read', account], capture_output=True, timeout=15)
assert result.returncode == 6 and not result.stdout, 'Non-app caller was not rejected before Keychain lookup'
result = subprocess.run([helper, 'authorize', account], capture_output=True, timeout=15)
assert result.returncode == 6 and not result.stdout, 'Non-app authorization caller was not rejected'
print('PASS: real native Keychain helper rejects malformed requests and non-app callers')
PY
swiftc Sources/MacBridgeIdentity/IdentityPartition.swift Tests/MacBridgeTests/IdentityPartitionTests.swift \
  -o .build/checks/IdentityPartitionChecks
.build/checks/IdentityPartitionChecks
swiftc -import-objc-header Tests/MacBridgeTests/IdentityAccessFixtures.h \
  Sources/MacBridgeIdentity/IdentityAccess.swift Tests/MacBridgeTests/IdentityAccessTests.swift \
  -o .build/checks/IdentityAccessChecks
.build/checks/IdentityAccessChecks
