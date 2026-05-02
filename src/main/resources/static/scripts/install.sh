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

# ── 색상 출력 ─────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[OsWL]${NC} $*"; }
warn()  { echo -e "${YELLOW}[OsWL]${NC} $*"; }
error() { echo -e "${RED}[OsWL]${NC} $*" >&2; exit 1; }

# ── 의존성 확인 ───────────────────────────────────────────────────────────────
require() { command -v "$1" &>/dev/null || error "$1 이(가) 필요합니다. 설치 후 다시 시도하세요."; }
require curl
require jq

info "OsWL CLI 설치를 시작합니다..."

# ── 설정 디렉터리 생성 ─────────────────────────────────────────────────────────
mkdir -p "${CONFIG_DIR}"
chmod 700 "${CONFIG_DIR}"

# ── oswl 실행 스크립트 생성 ────────────────────────────────────────────────────
cat > "/tmp/${BINARY_NAME}" << 'SCRIPT_EOF'
#!/usr/bin/env bash
set -euo pipefail

CONFIG_FILE="${HOME}/.oswl/config"
BINARY_NAME="oswl"

# ── 설정 로드 ──────────────────────────────────────────────────────────────────
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

# ── auth 명령 ──────────────────────────────────────────────────────────────────
cmd_auth() {
    local key="" server=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --key|-k)    key="$2";    shift 2 ;;
            --server|-s) server="$2"; shift 2 ;;
            *) echo "Unknown option: $1" >&2; exit 1 ;;
        esac
    done

    [[ -z "${key}" ]] && { echo "오류: --key <api_key> 가 필요합니다."; exit 1; }

    load_config
    OSWL_API_KEY="${key}"
    [[ -n "${server}" ]] && OSWL_SERVER_URL="${server}"

    # 서버 연결 테스트
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${OSWL_API_KEY}" \
        "${OSWL_SERVER_URL}/api/scan/ping" 2>/dev/null || echo "000")

    if [[ "${http_code}" == "200" ]]; then
        save_config
        echo "[OsWL] 인증 성공! 설정이 저장되었습니다."
        echo "       서버: ${OSWL_SERVER_URL}"
    elif [[ "${http_code}" == "401" ]]; then
        echo "[OsWL] 오류: API 키가 유효하지 않습니다." >&2; exit 1
    else
        # 서버 미응답 시에도 키는 저장 (오프라인 초기 설정 허용)
        save_config
        echo "[OsWL] 경고: 서버에 연결할 수 없습니다 (HTTP ${http_code})."
        echo "       키를 저장했지만, 스캔 전 서버를 확인하세요."
    fi
}

# ── scan 명령 ──────────────────────────────────────────────────────────────────
cmd_scan() {
    load_config

    if [[ -z "${OSWL_API_KEY:-}" ]]; then
        echo "[OsWL] 오류: API 키가 설정되지 않았습니다. 먼저 'oswl auth --key <key>' 를 실행하세요." >&2
        exit 1
    fi

    local project_dir="${1:-$PWD}"
    local version="${OSWL_VERSION:-unknown}"

    # 버전 자동 감지 (Maven, Gradle, npm, Python)
    if [[ -f "${project_dir}/pom.xml" ]]; then
        version=$(grep -m1 '<version>' "${project_dir}/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/' || echo "unknown")
    elif [[ -f "${project_dir}/build.gradle" ]]; then
        version=$(grep -m1 "^version" "${project_dir}/build.gradle" | sed "s/version\s*=\s*['\"]//;s/['\"].*//" || echo "unknown")
    elif [[ -f "${project_dir}/package.json" ]]; then
        version=$(jq -r '.version // "unknown"' "${project_dir}/package.json" 2>/dev/null || echo "unknown")
    fi

    echo "[OsWL] 의존성 스캔 중... (버전: ${version})"

    # 의존성 수집 (환경에 따라 실제 수집 로직으로 교체)
    local payload
    payload=$(build_payload "${project_dir}" "${version}")

    echo "[OsWL] 서버로 전송 중: ${OSWL_SERVER_URL}"

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
        echo "[OsWL] 스캔 완료! scanId=${scan_id} status=${status}"
    elif [[ "${http_code}" == "401" ]]; then
        echo "[OsWL] 오류: API 키가 거부되었습니다. 'oswl auth --key <new_key>' 를 실행하세요." >&2
        exit 1
    else
        echo "[OsWL] 오류: 서버 응답 HTTP ${http_code}" >&2
        echo "${body}" >&2
        exit 1
    fi
}

# ── 페이로드 빌드 (샘플 구조 – 실제 환경에서는 패키지 매니저 출력 파싱으로 교체) ───
build_payload() {
    local _dir="$1" version="$2"
    # 실제 구현에서는 `mvn dependency:tree`, `gradle dependencies`, `npm ls --json` 등의
    # 출력을 파싱해 components 배열을 채워야 합니다.
    jq -n \
        --arg version "${version}" \
        '{
            version: $version,
            components: []
        }'
}

# ── 도움말 ─────────────────────────────────────────────────────────────────────
cmd_help() {
    cat << 'HELP'
OsWL CLI – 오픈소스 공급망 분석 도구

사용법:
  oswl auth --key <api_key> [--server <url>]   API 키 저장 및 서버 연결 테스트
  oswl scan [<project_dir>]                    의존성 스캔 후 결과를 서버로 전송
  oswl help                                    이 도움말 표시

환경 변수:
  OSWL_API_KEY      API 키 (config 파일 대신 사용 가능)
  OSWL_SERVER_URL   서버 URL (기본값: http://localhost:8080)
  OSWL_VERSION      버전 자동 감지 실패 시 수동 지정
HELP
}

# ── 진입점 ─────────────────────────────────────────────────────────────────────
case "${1:-help}" in
    auth)  shift; cmd_auth "$@"  ;;
    scan)  shift; cmd_scan "$@"  ;;
    help|--help|-h) cmd_help ;;
    *) echo "알 수 없는 명령: $1. 'oswl help' 를 실행하세요." >&2; exit 1 ;;
esac
SCRIPT_EOF

chmod +x "/tmp/${BINARY_NAME}"

# ── 설치 위치 결정 ──────────────────────────────────────────────────────────────
if [[ -w "${INSTALL_DIR}" ]]; then
    mv "/tmp/${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
else
    warn "권한 부족 – sudo를 사용합니다."
    sudo mv "/tmp/${BINARY_NAME}" "${INSTALL_DIR}/${BINARY_NAME}"
fi

# ── PATH 확인 ──────────────────────────────────────────────────────────────────
if ! command -v "${BINARY_NAME}" &>/dev/null; then
    warn "${INSTALL_DIR} 이(가) PATH에 없습니다. ~/.bashrc 또는 ~/.zshrc에 다음을 추가하세요:"
    warn "  export PATH=\"\$PATH:${INSTALL_DIR}\""
fi

info "설치 완료! 다음 명령으로 API 키를 설정하세요:"
info "  oswl auth --key <your_api_key> --server ${SERVER_URL}"
