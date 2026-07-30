#!/usr/bin/env bash
# E5: thin wrapper around the oswl-vdb Java CLI (com.salkcoding.oswl.vdb.VdbBuilderCli),
# run on an internet-connected machine to build/verify/inspect offline vulnerability-DB
# bundles for import into an air-gapped OsWL instance.
#
# Usage:
#   scripts/oswl-vdb/oswl-vdb.sh build --wanted wanted-list.jsonl --out bundle.zip [--cache-dir .oswl-vdb-cache]
#   scripts/oswl-vdb/oswl-vdb.sh verify bundle.zip
#   scripts/oswl-vdb/oswl-vdb.sh inspect bundle.zip
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <build|verify|inspect> [args...]" >&2
  exit 1
fi

# Gradle's --args takes one whitespace-joined string; join "$@" so callers can still quote
# individual arguments (e.g. a path containing spaces) at the shell level.
ARGS="$*"
cd "${REPO_ROOT}"
exec ./gradlew vdbBuild --args="${ARGS}"
