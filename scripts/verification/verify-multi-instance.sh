#!/usr/bin/env bash
# OsWL multi-instance verification — a local approximation of "two instances behind a load
# balancer" run entirely on one machine, since no Docker/PostgreSQL cluster is assumed to be
# available. Boots two `local`-profile instances against the SAME H2 file DB (opened with
# AUTO_SERVER=TRUE so more than one JVM can hold it open at once), enables JDBC-backed HTTP
# sessions and cluster-wide scheduler locking (both opt-in, off by default — see
# application.yaml's `spring.session.store-type` and `oswl.scheduler-lock.enabled`), and checks
# the two things that actually matter for horizontal scaling:
#   (a) a session created by logging into instance A is honored by instance B (the JDBC session
#       store, not sticky in-memory Tomcat sessions, is what makes this possible)
#   (b) the nightly monitoring job runs on exactly one instance per cycle, not once per instance
#       (ShedLock, via the shared `shedlock` table)
#
# This is NOT a substitute for a real PostgreSQL-backed staging rehearsal — H2's AUTO_SERVER mode
# and two processes on one host don't exercise real network partitions, clock skew, or PostgreSQL
# advisory-lock semantics. Its assertions are incomplete; successful output is not evidence of correct session/scheduler wiring.
#
# Requires: a built boot jar (`./gradlew bootJar`), curl. Uses its own throwaway H2 DB under
# build/cluster-verification — never touches the developer's own ./oswl-db.
#
# Usage: ./scripts/verification/verify-multi-instance.sh

set -euo pipefail

# Draft assertions still accept redirected login pages and confuse scheduler-owner
# changes with duplicate execution. Keep this harness available for repair, not CI.
if [[ "${OSWL_ALLOW_DRAFT_CLUSTER_CHECK:-0}" != "1" ]]; then
  echo "Unverified draft: session identity and per-cycle scheduler checks need repair. See ROADMAP.md." >&2
  exit 2
fi

cd "$(dirname "$0")/../.."

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
PASS=0
FAIL=0
pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL + 1)); }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

require() { command -v "$1" &>/dev/null || { echo "Missing required tool: $1" >&2; exit 1; }; }
require curl
require java

WORKDIR="$(pwd)/build/cluster-verification"
DB_PATH="$WORKDIR/cluster-db"
LOG_A="$WORKDIR/instance-a.log"
LOG_B="$WORKDIR/instance-b.log"
JAR_A="$WORKDIR/cookies-a.txt"
PORT_A=18080
PORT_B=18081
ENC_KEY="KOaEB3zxojumnrmUsXpl4tPQGXSCLd+pE5bEuOirJy0="

rm -rf "$WORKDIR"
mkdir -p "$WORKDIR"

echo "== 1. Locate boot jar =="
JAR=$(find build/libs -maxdepth 1 -name "*.jar" ! -name "*plain*" 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
  echo "No boot jar found under build/libs — run ./gradlew bootJar first." >&2
  exit 1
fi
info "Using $JAR"

echo "== 2. Pre-create spring_session/shedlock tables =="
H2_JAR=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2" -name "h2-*.jar" ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | head -1)
if [[ -z "$H2_JAR" ]]; then
  echo "Could not locate the H2 jar in the Gradle cache." >&2
  exit 1
fi
DB_URL="jdbc:h2:file:${DB_PATH};MODE=PostgreSQL;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1"
java -cp "$H2_JAR" org.h2.tools.RunScript \
  -url "$DB_URL" -user sa -password "" \
  -script src/main/resources/db/spring_session_and_shedlock.sql
pass "spring_session/spring_session_attributes/shedlock tables created"

echo "== 3. Start two instances against the same DB =="
# LocalSmtpConfig (the embedded GreenMail SMTP mock, @Profile("local")) hardcodes port 3025 with
# no property to override it — fine for a single local dev instance, but a second instance on the
# same `local` profile fails to bind it. Rather than touch that dev-only code for a multi-instance
# test it was never meant to run under, instance A keeps the full `local` profile (GreenMail
# included, useful for watching OTP mail), and instance B runs with no profile, supplying only the
# handful of properties that profile would otherwise provide and actually matter here — the shared
# DB and the encryption key. `ddl-auto=none` on B avoids two instances racing to alter the schema.
COMMON_ARGS=(
  "--spring.datasource.url=${DB_URL}"
  "--spring.datasource.driver-class-name=org.h2.Driver"
  "--spring.datasource.username=sa"
  "--spring.datasource.password="
  "--spring.session.store-type=jdbc"
  "--oswl.scheduler-lock.enabled=true"
  "--oswl.monitoring.enabled=true"
  "--oswl.monitoring.cron=0/20 * * * * *"
  "--oswl.encryption.key=${ENC_KEY}"
  "--logging.level.com.salkcoding.oswl.scheduler.ContinuousMonitoringScheduler=DEBUG"
)

cleanup() {
  kill "${PID_A:-}" "${PID_B:-}" 2>/dev/null || true
  wait "${PID_A:-}" "${PID_B:-}" 2>/dev/null || true
}
trap cleanup EXIT

wait_for_login_page() {
  local port=$1
  for _ in $(seq 1 60); do
    # A fresh (no admin yet) instance 302s /login -> /setup, so follow redirects (-L): /setup is
    # the thing that's always a real 200 regardless of setup state.
    code=$(curl -s --ipv4 -L -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/setup" || true)
    [[ "$code" == "200" ]] && return 0
    sleep 2
  done
  return 1
}

# A first, alone, with ddl-auto=update — it owns schema creation. B (ddl-auto=none) only starts
# once A's schema is confirmed up, so B never queries a table that doesn't exist yet.
java -jar "$JAR" --server.port=$PORT_A --spring.profiles.active=local \
  --spring.jpa.hibernate.ddl-auto=update "${COMMON_ARGS[@]}" > "$LOG_A" 2>&1 &
PID_A=$!
wait_for_login_page "$PORT_A" || { fail "Instance A did not come up — see $LOG_A"; exit 1; }
pass "Instance A up on :$PORT_A (owns schema creation)"


# application.yaml defaults spring.profiles.active to "local" when SPRING_PROFILES_ACTIVE isn't
# set (`active: ${SPRING_PROFILES_ACTIVE:local}`), so omitting --spring.profiles.active here
# would silently activate `local` (and its GreenMail bean) anyway. Naming a profile that has no
# application-<name>.yaml is harmless — Spring Boot just applies the base application.yaml.
java -jar "$JAR" --server.port=$PORT_B --spring.profiles.active=cluster-verify-b \
  --spring.jpa.hibernate.ddl-auto=none "${COMMON_ARGS[@]}" > "$LOG_B" 2>&1 &
PID_B=$!
wait_for_login_page "$PORT_B" || { fail "Instance B did not come up — see $LOG_B"; exit 1; }
pass "Instance B up on :$PORT_B (same DB file, AUTO_SERVER=TRUE — both JVMs hold it open concurrently)"

echo "== 4. Create the initial admin account (via instance A's /setup) =="
EMAIL="clusterverify@test.local"
PASSWORD="ClusterVerifyPass123!"
CSRF=$(curl -s --ipv4 -c "$JAR_A" "http://127.0.0.1:${PORT_A}/setup" \
  | grep -oE 'name="_csrf" value="[^"]+"' | head -1 | sed 's/.*value="//;s/"//')
curl -s --ipv4 -b "$JAR_A" -c "$JAR_A" -o /dev/null \
  -d "email=${EMAIL}" -d "password=${PASSWORD}" -d "passwordConfirm=${PASSWORD}" \
  -d "displayName=Cluster Verify" -d "_csrf=${CSRF}" \
  "http://127.0.0.1:${PORT_A}/setup"
pass "Initial admin created on instance A"

echo "== 5. Log in on instance A, then reuse the session cookie against instance B =="
CSRF=$(curl -s --ipv4 -c "$JAR_A" "http://127.0.0.1:${PORT_A}/login" \
  | grep -oE 'name="_csrf" value="[^"]+"' | head -1 | sed 's/.*value="//;s/"//')
curl -s --ipv4 -b "$JAR_A" -c "$JAR_A" -o /dev/null \
  -d "email=${EMAIL}" -d "password=${PASSWORD}" -d "_csrf=${CSRF}" \
  "http://127.0.0.1:${PORT_A}/login"

STATUS_B=$(curl -s --ipv4 -o /dev/null -w '%{http_code}' -L -b "$JAR_A" "http://127.0.0.1:${PORT_B}/onboarding")
if [[ "$STATUS_B" == "200" ]]; then
  pass "Session created on instance A was accepted by instance B (final: $STATUS_B) — JDBC session sharing works"
else
  fail "Instance B rejected instance A's session cookie (final status $STATUS_B) — sessions are NOT shared"
fi

echo "== 6. Scheduler: wait ~100s (5 cron cycles at 20s) and check which instance ran the job =="
sleep 100
# ContinuousMonitoringScheduler itself logs nothing — the service it calls
# (ContinuousMonitoringService) is what actually logs the "[Monitor] cycle START/DONE" lines.
COUNT_A=$(grep -c '\[Monitor\] Continuous monitoring cycle START' "$LOG_A" || true)
COUNT_B=$(grep -c '\[Monitor\] Continuous monitoring cycle START' "$LOG_B" || true)
info "Monitoring-cycle log lines — A=$COUNT_A B=$COUNT_B"
if [[ "$COUNT_A" -gt 0 && "$COUNT_B" -gt 0 ]]; then
  fail "Both instances logged scheduler activity — ShedLock did not prevent duplicate execution"
elif [[ "$COUNT_A" -eq 0 && "$COUNT_B" -eq 0 ]]; then
  fail "Neither instance logged scheduler activity — the job may not have fired at all; check both logs"
else
  pass "Exactly one instance (A=$COUNT_A, B=$COUNT_B) ran the scheduled job — ShedLock is deduplicating correctly"
fi

echo
echo "== Summary: $PASS passed, $FAIL failed =="
info "Logs kept at $LOG_A and $LOG_B for inspection. Throwaway DB at $DB_PATH.*"
[[ "$FAIL" -eq 0 ]]
