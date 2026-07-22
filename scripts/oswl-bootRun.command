#!/bin/bash
# Double-click in Finder (macOS) to start OsWL locally.
# Keeps Terminal open so you can stop the server with Ctrl+C.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT}" || exit 1

echo "Starting OsWL from: ${ROOT}"
echo "Press Ctrl+C in this window to stop the server."
echo ""

if [[ -f "${ROOT}/scripts/check-java.sh" ]]; then
  bash "${ROOT}/scripts/check-java.sh" || { echo ""; read -r -p "Press Enter to close…"; exit 1; }
fi

chmod +x "${ROOT}/gradlew" 2>/dev/null || true
"${ROOT}/gradlew" bootRun

echo ""
echo "Server stopped. Press Enter to close this window."
read -r _
