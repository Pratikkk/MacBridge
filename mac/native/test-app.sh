#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p .build/checks
swiftc Sources/MacBridge/CompanionState.swift Sources/MacBridge/PairingQR.swift Sources/MacBridge/BonjourPublisher.swift Sources/MacBridge/NotificationPresenter.swift \
  Tests/MacBridgeTests/CompanionTests.swift -o .build/checks/MacBridgeChecks
.build/checks/MacBridgeChecks
