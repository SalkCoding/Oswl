#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# OsWL CLI Installer ─ Mac / Linux
# Usage:
#   curl -fsSL https://<your-server>/scripts/install.sh | bash
#
# After install, save your API key once:
#   oswl auth --key <your_api_key> [--server https://your-server]
#
# Then scan your project (user credentials required):
#   cd /your/project && oswl scan -k YOUR_API_KEY -u YOUR_EMAIL
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

require zip
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

    local project_dir="" key="" server=""
    local username="${OSWL_USERNAME:-}" password="${OSWL_PASSWORD:-}"

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --key|-k)        key="$2";        shift 2 ;;
            --server|-s)     server="$2";     shift 2 ;;
            -u|--username)   username="$2";   shift 2 ;;
            -p|--password)   password="$2";   shift 2 ;;
            -*)              echo "[OsWL] Unknown option: $1" >&2; exit 1 ;;
            *)               project_dir="$1"; shift ;;
        esac
    done

    # Flag overrides take precedence over saved config / env vars
    [[ -n "${key}"    ]] && OSWL_API_KEY="${key}"
    [[ -n "${server}" ]] && OSWL_SERVER_URL="${server}"
    OSWL_SERVER_URL="${OSWL_SERVER_URL:-http://localhost:8080}"

    if [[ -z "${OSWL_API_KEY:-}" ]]; then
        echo "[OsWL] Error: --key <api_key> is required (or set OSWL_API_KEY / run 'oswl auth')." >&2
        exit 1
    fi

    # User credentials are mandatory — authenticate before uploading scan data
    if [[ -z "${username}" ]]; then
        echo "[OsWL] Error: -u <email> is required (or set OSWL_USERNAME)." >&2
        exit 1
    fi

    # Prompt for password interactively if not provided on the command line / env
    if [[ -z "${password}" ]]; then
        printf "Password for %s: " "${username}" >&2
        IFS= read -rs password </dev/tty
        echo >&2
    fi

    project_dir="${project_dir:-${PWD}}"
    local version="${OSWL_VERSION:-unknown}"

    # Auto-detect version (Maven, Gradle, npm, Cargo, Python)
    if   [[ -f "${project_dir}/pom.xml" ]]; then
        version=$(grep -m1 '<version>' "${project_dir}/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' || echo "unknown")
    elif [[ -f "${project_dir}/build.gradle.kts" ]]; then
        version=$(grep -m1 "^version" "${project_dir}/build.gradle.kts" | sed "s/version\s*=\s*['\"]//;s/['\"].*//" || echo "unknown")
    elif [[ -f "${project_dir}/build.gradle" ]]; then
        version=$(grep -m1 "^version" "${project_dir}/build.gradle" | sed "s/version\s*=\s*['\"]//;s/['\"].*//" || echo "unknown")
    elif [[ -f "${project_dir}/package.json" ]]; then
        version=$(jq -r '.version // "unknown"' "${project_dir}/package.json" 2>/dev/null || echo "unknown")
    elif [[ -f "${project_dir}/Cargo.toml" ]]; then
        version=$(grep -m1 '^version' "${project_dir}/Cargo.toml" | sed 's/version\s*=\s*"\(.*\)"/\1/' || echo "unknown")
    elif [[ -f "${project_dir}/pyproject.toml" ]]; then
        version=$(grep -m1 '^version' "${project_dir}/pyproject.toml" | sed 's/version\s*=\s*"\(.*\)"/\1/' || echo "unknown")
    fi

    echo "[OsWL] Scanning dependencies... (version: ${version})"

    local zipfile
    zipfile=$(pack_manifests "${project_dir}")
    trap 'rm -f "${zipfile}"' EXIT

    echo "[OsWL] Parsing manifests on server..."
    local parse_response
    parse_response=$(curl -s -w "\n%{http_code}" \
        -X POST \
        -H "Authorization: Bearer ${OSWL_API_KEY}" \
        -F "archive=@${zipfile}" \
        "${OSWL_SERVER_URL}/api/scan/parse")
    rm -f "${zipfile}"
    trap - EXIT

    http_code=$(echo "${parse_response}" | tail -n1)
    body=$(echo "${parse_response}" | sed '$d')

    if [[ "${http_code}" != "200" ]]; then
        echo "[OsWL] Error: Parse failed HTTP ${http_code}" >&2
        echo "${body}" >&2
        exit 1
    fi

    local count
    count=$(echo "${body}" | jq '.componentCount // 0')
    echo "[OsWL] Found ${count} component(s)."

    local payload
    payload=$(echo "${body}" | jq \
        --arg version "${version}" \
        --arg email "${username}" \
        --arg pass  "${password}" \
        '{version: $version, components: .components, submitterEmail: $email, submitterPassword: $pass}')

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
    body=$(echo "${response}" | sed '$d')

    if [[ "${http_code}" == "200" ]]; then
        local scan_id
        scan_id=$(echo "${body}" | jq -r '.scanId // "?"')
        echo "[OsWL] Scan submitted! scanId=${scan_id}"
        echo "       Analysis is running on the server. Check the Security Center for results."
    elif [[ "${http_code}" == "401" ]]; then
        echo "[OsWL] Error: Authentication failed. Check your API key and credentials." >&2
        exit 1
    elif [[ "${http_code}" == "403" ]]; then
        echo "[OsWL] Error: User does not have SCAN_SUBMIT permission." >&2
        exit 1
    else
        echo "[OsWL] Error: Server responded HTTP ${http_code}" >&2
        echo "${body}" >&2
        exit 1
    fi
}


# -- Manifest packaging (rules from manifest-rules.json) -----------------------

MANIFEST_RULES_FILE="${HOME}/.oswl/manifest-rules.json"

ensure_manifest_rules() {
    mkdir -p "${HOME}/.oswl"
    local url="${OSWL_SERVER_URL}/scripts/manifest-rules.json"
    if curl -fsSL "${url}" -o "${MANIFEST_RULES_FILE}.tmp" 2>/dev/null; then
        mv "${MANIFEST_RULES_FILE}.tmp" "${MANIFEST_RULES_FILE}"
        dbg "Updated manifest rules from ${url}"
    elif [[ ! -f "${MANIFEST_RULES_FILE}" ]]; then
        echo "[OsWL] Error: manifest-rules.json not found. Check server URL or run: curl -fsSL ${url} -o ${MANIFEST_RULES_FILE}" >&2
        exit 1
    fi
}

_manifest_should_skip() {
    local relpath="$1" d
    while IFS= read -r d; do
        [[ -z "${d}" ]] && continue
        [[ "${relpath}" =~ (^|/)${d}(/|$) ]] && return 0
    done < <(jq -r '.skipDirs[]' "${MANIFEST_RULES_FILE}")
    return 1
}

_manifest_is_included() {
    local relpath="$1"
    local base
    base=$(basename "${relpath}")
    jq -e --arg r "${relpath}" --arg b "${base}" '
      (.exactFileNames | index($b)) != null
      or ([.fileSuffixes[] | select($b | endswith(.))] | length > 0)
      or ([.pathPrefixes[] | select($r | startswith(.))] | length > 0)
      or (($r | startswith("buildSrc/")) and ([.buildSrcSuffixes[] | select($b | endswith(.))] | length > 0))
    ' "${MANIFEST_RULES_FILE}" >/dev/null
}

pack_manifests() {
    ensure_manifest_rules
    local dir="$1"
    local zipfile
    zipfile=$(mktemp "${TMPDIR:-/tmp}/oswl-manifests-XXXXXX.zip")
    local -a rels=()
    local f relpath

    while IFS= read -r -d '' f; do
        relpath="${f#"${dir}/"}"
        relpath="${relpath#/}"
        _manifest_should_skip "${relpath}" && continue
        _manifest_is_included "${relpath}" || continue
        rels+=("${relpath}")
        dbg "manifest: ${relpath}"
    done < <(find "${dir}" -type f -print0)

    if [[ ${#rels[@]} -eq 0 ]]; then
        rm -f "${zipfile}"
        echo "[OsWL] Error: No manifest files found under ${dir}" >&2
        exit 1
    fi

    (cd "${dir}" && zip -q -r "${zipfile}" "${rels[@]}")
    echo "${zipfile}"
}

# ── Help ─────────────────────────────────────────────────────────────────────────
cmd_help() {
    cat << 'HELP'
OsWL CLI - Open-source Software Composition Analysis tool

Usage:
  oswl auth --key <api_key> [--server <url>]            Save API key and verify server connection
  oswl scan [<project_dir>] -k <key> -u <email>         Scan dependencies and upload to server
  oswl help                                             Show this help message

Scan flags:
  --key|-k    <api_key>   API key for the target project  (required; or env OSWL_API_KEY)
  -u|--username <email>   Your OsWL account email         (required; or env OSWL_USERNAME)
  -p|--password <pass>    Your OsWL account password      (required; prompted if omitted; or env OSWL_PASSWORD)
  --server|-s   <url>     OsWL server URL                 (or env OSWL_SERVER_URL, default: http://localhost:8080)

Global flags:
  --debug    Print detailed debug output (build system detection, component resolution, etc.)

Environment variables:
  OSWL_API_KEY      API key (can be used instead of config file)
  OSWL_USERNAME     OsWL account email for scan authentication
  OSWL_PASSWORD     OsWL account password for scan authentication
  OSWL_SERVER_URL   Server URL (default: http://localhost:8080)
  OSWL_VERSION      Specify version manually if auto-detection fails

Examples:
  # minimal (password prompted interactively)
  oswl scan --key oswl_abc123 -u dev@company.com

  # full
  oswl scan -k oswl_abc123 -u dev@company.com -p secret --server https://sca.company.com

  # CI/CD via env vars (credentials not in shell history)
  export OSWL_API_KEY=oswl_abc123
  export OSWL_USERNAME=ci@company.com
  export OSWL_PASSWORD=secret
  export OSWL_SERVER_URL=https://sca.company.com
  oswl scan

Notes:
  - Dependencies are parsed on the server (same engine as Quick Import).
  - Password is prompted if -p is omitted; never stored on disk or in audit logs.
  - Requires curl, jq, zip. Server must serve /scripts/manifest-rules.json.
  - For CI/CD, set OSWL_API_KEY, OSWL_USERNAME, OSWL_PASSWORD, OSWL_SERVER_URL.
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
    warn "Insufficient permissions - using sudo."
    sudo mv "/tmp/${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
fi

# ── Check PATH ─────────────────────────────────────────────────────────────────────────────────
if ! command -v "${BINARY_NAME}" &>/dev/null; then
    warn "${INSTALL_DIR} is not in your PATH. Add the following to ~/.bashrc or ~/.zshrc:"
    warn "  export PATH=\"\$PATH:${INSTALL_DIR}\""
fi

info "Installation complete! Set your API key with:"
info "  oswl auth --key <your_api_key> --server ${SERVER_URL}"
