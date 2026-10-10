#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
./build-app.sh
python3 install_app.py "$@"
