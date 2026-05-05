#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# OsWL CLI Installer – Mac / Linux
# Usage:
#   curl -fsSL https://<your-server>/scripts/install.sh | bash
#
# After install, authenticate once:
#   oswl auth --key <your_api_key> [--server https://your-server]
#
# Then scan your project:
#   cd /your/project && oswl scan
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

INSTALL_DIR="${OSWL_INSTALL_DIR:-/usr/local/bin}"
CONFIG_DIR="${HOME}/.oswl"
CONFIG_FILE="${CONFIG_DIR}/config"
BINARY_NAME="oswl"
SERVER_URL="${OSWL_SERVER_URL:-http://localhost:8080}"

# ── Colored output ─────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[OsWL]${NC} $*"; }
warn()  { echo -e "${YELLOW}[OsWL]${NC} $*"; }
error() { echo -e "${RED}[OsWL]${NC} $*" >&2; exit 1; }

# ── Check dependencies ───────────────────────────────────────────────────────
require() { command -v "$1" &>/dev/null || error "$1 is required. Please install it and try again."; }
require curl
require jq

info "Starting OsWL CLI installation..."

# ── Create config directory ─────────────────────────────────────────────────────
mkdir -p "${CONFIG_DIR}"
chmod 700 "${CONFIG_DIR}"

# ── Generate oswl script ────────────────────────────────────────────────────────
cat > "/tmp/${BINARY_NAME}" << 'SCRIPT_EOF'
#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${HOME}/.oswl/config"
BINARY_NAME="oswl"
DEBUG_MODE=false

dbg() { if [[ "${DEBUG_MODE}" == "true" ]]; then echo "[OsWL][DEBUG] $*" >&2; fi; }

# ── Load config ──────────────────────────────────────────────────────────────────
load_config() {
    if [[ -f "${CONFIG_FILE}" ]]; then
        # shellcheck source=/dev/null
        source "${CONFIG_FILE}"
    fi
}

save_config() {
    cat > "${CONFIG_FILE}" << EOF
OSWL_API_KEY="${OSWL_API_KEY:-}"
OSWL_SERVER_URL="${OSWL_SERVER_URL:-http://localhost:8080}"
EOF
    chmod 600 "${CONFIG_FILE}"
}

# ── auth command ──────────────────────────────────────────────────────────────────
cmd_auth() {
    local key="" server=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --key|-k)    key="$2";    shift 2 ;;
            --server|-s) server="$2"; shift 2 ;;
            *) echo "Unknown option: $1" >&2; exit 1 ;;
        esac
    done

    [[ -z "${key}" ]] && { echo "Error: --key <api_key> is required."; exit 1; }

    load_config
    OSWL_API_KEY="${key}"
    [[ -n "${server}" ]] && OSWL_SERVER_URL="${server}"
    OSWL_SERVER_URL="${OSWL_SERVER_URL:-http://localhost:8080}"

    # Test server connection
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${OSWL_API_KEY}" \
        "${OSWL_SERVER_URL}/api/scan/ping" 2>/dev/null || echo "000")

    if [[ "${http_code}" == "200" ]]; then
        save_config
        echo "[OsWL] Authentication successful! Config saved."
        echo "       Server: ${OSWL_SERVER_URL}"
    elif [[ "${http_code}" == "401" ]]; then
        echo "[OsWL] Error: Invalid API key." >&2; exit 1
    else
        # Save key even if server is unreachable (allow offline initial setup)
        save_config
        echo "[OsWL] Warning: Could not reach server (HTTP ${http_code})."
        echo "       Key saved, but verify server before scanning."
    fi
}

# ── scan command ──────────────────────────────────────────────────────────────────
cmd_scan() {
    load_config

    if [[ -z "${OSWL_API_KEY:-}" ]]; then
        echo "[OsWL] Error: API key not set. Run 'oswl auth --key <key>' first." >&2
        exit 1
    fi

    local project_dir="${1:-$PWD}"
    local version="${OSWL_VERSION:-unknown}"

    # Auto-detect version (Maven, Gradle, npm, Python)
    if [[ -f "${project_dir}/pom.xml" ]]; then
        version=$(grep -m1 '<version>' "${project_dir}/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' || echo "unknown")
    elif [[ -f "${project_dir}/build.gradle" ]]; then
        version=$(grep -m1 "^version" "${project_dir}/build.gradle" | sed "s/version\s*=\s*['\"]//;s/['\"].*//" || echo "unknown")
    elif [[ -f "${project_dir}/package.json" ]]; then
        version=$(jq -r '.version // "unknown"' "${project_dir}/package.json" 2>/dev/null || echo "unknown")
    fi

    echo "[OsWL] Scanning dependencies... (version: ${version})"

    local payload
    payload=$(build_payload "${project_dir}" "${version}")

    echo "[OsWL] Sending to server: ${OSWL_SERVER_URL}"

    local response http_code
    response=$(curl -s -w "\n%{http_code}" \
        -X POST \
        -H "Authorization: Bearer ${OSWL_API_KEY}" \
        -H "Content-Type: application/json" \
        -d "${payload}" \
        "${OSWL_SERVER_URL}/api/scan")

    http_code=$(echo "${response}" | tail -n1)
    local body
    body=$(echo "${response}" | head -n -1)

    if [[ "${http_code}" == "200" ]]; then
        local scan_id status
        scan_id=$(echo "${body}" | jq -r '.scanId // "?"')
        status=$(echo "${body}"  | jq -r '.status  // "?"')
        echo "[OsWL] Scan submitted! scanId=${scan_id}"
        echo "       Analysis is running on the server. Check the Security Center for results."
    elif [[ "${http_code}" == "401" ]]; then
        echo "[OsWL] Error: API key rejected. Run 'oswl auth --key <new_key>'." >&2
        exit 1
    else
        echo "[OsWL] Error: Server responded HTTP ${http_code}" >&2
        echo "${body}" >&2
        exit 1
    fi
}

# ── Dependency collection helpers ────────────────────────────────────────────
_collect_gradle() {
    local dir="$1"
    local gradle="${dir}/gradlew"
    if [[ -x "${gradle}" ]]; then
        dbg "Gradle: using wrapper at ${gradle}"
    else
        gradle="gradle"
        dbg "Gradle: wrapper not found, using system gradle"
    fi
    local result
    result=$(
        (cd "${dir}" && "${gradle}" dependencies --configuration runtimeClasspath -q 2>/dev/null) \
        | grep -E '^[[:space:]|]*[+\\]---' \
        | grep -oP '[+\\]--- \K[^: ]+:[^: ]+:[^ (\r\n]+' \
        | sed 's/ ->.*//;s/ \*$//' \
        | awk -F: '{printf "{\"name\":\"%s:%s\",\"version\":\"%s\",\"ecosystem\":\"MAVEN\",\"dependencyInfo\":\"Gradle runtimeClasspath\"}\n",$1,$2,$3}' \
        | jq -s '.' 2>/dev/null
    ) || result="[]"
    dbg "Gradle resolved components: $(echo "${result}" | jq 'length' 2>/dev/null || echo '?')"
    echo "${result}"
}

_collect_maven() {
    local dir="$1"
    mvn -f "${dir}/pom.xml" dependency:list -q 2>/dev/null \
    | grep -oP '^\[INFO\]\s+\K[^:]+:[^:]+:jar:[^:]+' \
    | awk -F: '{printf "{\"name\":\"%s:%s\",\"version\":\"%s\",\"ecosystem\":\"MAVEN\",\"dependencyInfo\":\"Maven\"}\n",$1,$2,$4}' \
    | jq -s '.' 2>/dev/null || echo "[]"
}

_collect_npm() {
    local dir="$1"
    jq '[.packages // {} | to_entries[]
        | select(.key != "" and (.value.version // "") != "")
        | {name: (.key | ltrimstr("node_modules/")),
           version: .value.version,
           ecosystem: "NPM",
           dependencyInfo: (if (.value.dev // false) then "npm devDependency" else "npm dependency" end)}]' \
        "${dir}/package-lock.json" 2>/dev/null || echo "[]"
}

_collect_pip() {
    local dir="$1"
    grep -E '^[A-Za-z0-9_.-]+==[^ ]+' "${dir}/requirements.txt" 2>/dev/null \
    | awk -F'==' '{printf "{\"name\":\"%s\",\"version\":\"%s\",\"ecosystem\":\"PYPI\",\"dependencyInfo\":\"pip\"}\n",$1,$2}' \
    | jq -s '.' 2>/dev/null || echo "[]"
}

# ── Build payload ─────────────────────────────────────────────────────────────
build_payload() {
    local dir="$1" version="$2"
    local components="[]"

    if   [[ -f "${dir}/build.gradle" || -f "${dir}/build.gradle.kts" ]]; then
        dbg "Detected build system: Gradle (dir=${dir})"
        components=$(_collect_gradle "${dir}")
    elif [[ -f "${dir}/pom.xml" ]]; then
        dbg "Detected build system: Maven"
        components=$(_collect_maven "${dir}")
    elif [[ -f "${dir}/package-lock.json" ]]; then
        dbg "Detected build system: npm"
        components=$(_collect_npm "${dir}")
    elif [[ -f "${dir}/requirements.txt" ]]; then
        dbg "Detected build system: pip"
        components=$(_collect_pip "${dir}")
    else
        dbg "No recognized build file found in ${dir}"
    fi

    local count
    count=$(echo "${components}" | jq 'length' 2>/dev/null || echo "0")
    echo "[OsWL] Found ${count} component(s)." >&2

    jq -n \
        --arg version "${version}" \
        --argjson components "${components}" \
        '{version: $version, components: $components}'
}

# ── Help ─────────────────────────────────────────────────────────────────────────
cmd_help() {
    cat << 'HELP'
OsWL CLI - Open-source Software Composition Analysis tool

Usage:
  oswl auth --key <api_key> [--server <url>]   Save API key and verify server connection
  oswl scan [<project_dir>]                    Scan dependencies and upload results to server
  oswl help                                    Show this help message

Global flags:
  --debug    Print detailed debug output (build system detection, component resolution, etc.)

Environment variables:
  OSWL_API_KEY      API key (can be used instead of config file)
  OSWL_SERVER_URL   Server URL (default: http://localhost:8080)
  OSWL_VERSION      Specify version manually if auto-detection fails
HELP
}

# ── Entry point ─────────────────────────────────────────────────────────────────
# Pre-process global flags (--debug) before dispatching
_args=()
for _a in "$@"; do
    if [[ "${_a}" == "--debug" ]]; then
        DEBUG_MODE=true
    else
        _args+=("${_a}")
    fi
done
set -- "${_args[@]+"${_args[@]}"}"
unset _a _args

case "${1:-help}" in
    auth)  shift; cmd_auth "$@"  ;;
    scan)  shift; cmd_scan "$@"  ;;
    help|--help|-h) cmd_help ;;
    *) echo "Unknown command: $1. Run 'oswl help' for usage." >&2; exit 1 ;;
esac
SCRIPT_EOF

chmod +x "/tmp/${BINARY_NAME}"

# ── Determine install location ─────────────────────────────────────────────────────────────────
if [[ -w "${INSTALL_DIR}" ]]; then
    mv "/tmp/${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
else
    warn "Insufficient permissions – using sudo."
    sudo mv "/tmp/${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
fi

# ── Check PATH ─────────────────────────────────────────────────────────────────────────────────
if ! command -v "${BINARY_NAME}" &>/dev/null; then
    warn "${INSTALL_DIR} is not in your PATH. Add the following to ~/.bashrc or ~/.zshrc:"
    warn "  export PATH=\"\$PATH:${INSTALL_DIR}\""
fi

info "Installation complete! Set your API key with:"
info "  oswl auth --key <your_api_key> --server ${SERVER_URL}"
