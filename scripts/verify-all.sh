#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="/data2/chat_project/chat_frontend/chat_app"
FLUTTER_BIN="/data2/chat_project/flutter_sdk/flutter/bin/flutter"

cd "$ROOT_DIR"
git diff --check
mvn test

cd "$FRONTEND_DIR"
git diff --check
"$FLUTTER_BIN" test

if [[ "${RUN_WEB_BUILD:-0}" == "1" ]]; then
  "$FLUTTER_BIN" build web --release \
    --dart-define=API_BASE_URL="${API_BASE_URL:-http://localhost:18080}" \
    --dart-define=WS_BASE_URL="${WS_BASE_URL:-ws://localhost:18080}"
fi
