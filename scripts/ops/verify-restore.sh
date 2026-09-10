#!/usr/bin/env bash
# OsWL restore-rehearsal verification — run this against a freshly restored
# instance (restored PostgreSQL + injected OSWL_ENCRYPTION_KEY + app started) to confirm the
# three things a backup restore actually needs to prove:
#   (a) the encryption key is correct — a stored VCS token decrypts without error
#   (b) the restored scan history is queryable
#   (c) the restored audit log is queryable
#
# This is an interactive drill script (a human runs it during a DR rehearsal), not an
# unattended health check — logging in still goes through real email OTP 2FA, so it pauses to
# ask you for the code. See docs/en/Backup-And-Restore.md for the full restore procedure this
# script verifies the end of.
#
# Usage:
#   OSWL_VERIFY_EMAIL=admin@example.com \
#   OSWL_VERIFY_PASSWORD='...' \
#   OSWL_VERIFY_PROJECT_ID=1 \
#   ./scripts/ops/verify-restore.sh [base-url, default http://localhost:8080]

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
EMAIL="${OSWL_VERIFY_EMAIL:-}"
PASSWORD="${OSWL_VERIFY_PASSWORD:-}"
PROJECT_ID="${OSWL_VERIFY_PROJECT_ID:-}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
PASS=0
FAIL=0

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL + 1)); }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

require() {
  command -v "$1" &>/dev/null || { echo "Missing required tool: $1" >&2; exit 1; }
}
require curl
require jq

if [[ -z "$EMAIL" || -z "$PASSWORD" ]]; then
  echo "Set OSWL_VERIFY_EMAIL and OSWL_VERIFY_PASSWORD (an account you can log into on the" >&2
  echo "restored instance) before running this script." >&2
  exit 1
fi

JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

echo "== 1. App health =="
HEALTH=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" || true)
if [[ "$HEALTH" == "200" ]]; then
  pass "GET /actuator/health returned 200 — app started against the restored DB"
else
  fail "GET /actuator/health returned $HEALTH — app may not have started; stop here and check logs"
  exit 1
fi

echo "== 2. Log in (email + password + OTP) =="
CSRF=$(curl -s -c "$JAR" "$BASE_URL/login" | grep -oE 'name="_csrf" value="[^"]+"' | head -1 | sed 's/.*value="//;s/"//')
curl -s -b "$JAR" -c "$JAR" -o /dev/null \
  -d "email=$EMAIL" -d "password=$PASSWORD" -d "_csrf=$CSRF" \
  "$BASE_URL/login"

info "A one-time code was emailed to $EMAIL (check the mail relay / GreenMail log)."
read -rp "Enter the OTP code: " OTP_CODE

XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR")
OTP_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d "{\"code\":\"$OTP_CODE\"}" \
  "$BASE_URL/login/otp-verify")

if [[ "$OTP_STATUS" == "200" ]]; then
  pass "Login + OTP succeeded"
else
  fail "POST /login/otp-verify returned $OTP_STATUS — cannot continue without a session"
  exit 1
fi

XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' "$JAR")

echo "== 3. VCS token decryption (OSWL_ENCRYPTION_KEY correctness) =="
VCS_RESPONSE=$(curl -s -w '\n%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $XSRF" \
  "$BASE_URL/api/settings/vcs")
VCS_STATUS=$(echo "$VCS_RESPONSE" | tail -1)
VCS_BODY=$(echo "$VCS_RESPONSE" | sed '$d')
if [[ "$VCS_STATUS" == "200" ]]; then
  COUNT=$(echo "$VCS_BODY" | jq 'length' 2>/dev/null || echo "?")
  pass "GET /api/settings/vcs returned 200 ($COUNT connection(s)) — no decryption error"
else
  fail "GET /api/settings/vcs returned $VCS_STATUS — likely a wrong OSWL_ENCRYPTION_KEY (decryption failure)"
fi

echo "== 4. Recent scan history =="
if [[ -z "$PROJECT_ID" ]]; then
  info "OSWL_VERIFY_PROJECT_ID not set — skipping scan-history check"
else
  SCAN_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $XSRF" \
    "$BASE_URL/projects/$PROJECT_ID/scan-history")
  if [[ "$SCAN_STATUS" == "200" ]]; then
    pass "GET /projects/$PROJECT_ID/scan-history returned 200 — scan history is queryable"
  else
    fail "GET /projects/$PROJECT_ID/scan-history returned $SCAN_STATUS"
  fi
fi

echo "== 5. Audit log (requires SYSTEM_ADMIN) =="
AUDIT_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $XSRF" \
  "$BASE_URL/api/admin/audit-logs")
if [[ "$AUDIT_STATUS" == "200" ]]; then
  pass "GET /api/admin/audit-logs returned 200 — audit log is queryable"
elif [[ "$AUDIT_STATUS" == "403" ]]; then
  info "GET /api/admin/audit-logs returned 403 — the account used isn't SYSTEM_ADMIN, skipping (not a restore failure)"
else
  fail "GET /api/admin/audit-logs returned $AUDIT_STATUS"
fi

echo
echo "== Summary: $PASS passed, $FAIL failed =="
[[ "$FAIL" -eq 0 ]]
