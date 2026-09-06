#!/usr/bin/env bash
# OsWL JDK preflight — requires Java 25+ (matches build.gradle toolchain).
set -euo pipefail

REQUIRED_MAJOR=25
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

detect_java() {
  if command -v java &>/dev/null; then
    java -version 2>&1 | head -n1
    return 0
  fi
  return 1
}

java_major() {
  local ver
  ver="$(java -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*".*/\1/')"
  echo "${ver:-0}"
}

print_install_hints() {
  echo ""
  echo -e "${YELLOW}OsWL requires JDK ${REQUIRED_MAJOR} or later.${NC}"
  echo ""
  echo "Install options:"
  echo "  macOS (Homebrew):  brew install openjdk@${REQUIRED_MAJOR}"
  echo "                     echo 'export PATH=\"/opt/homebrew/opt/openjdk@${REQUIRED_MAJOR}/bin:\$PATH\"' >> ~/.zshrc"
  echo "  Ubuntu/Debian:     sudo apt install openjdk-${REQUIRED_MAJOR}-jdk"
  echo "  Windows:           winget install Microsoft.OpenJDK.${REQUIRED_MAJOR}"
  echo "                     or download from https://adoptium.net/"
  echo ""
  echo "Then verify:  java -version"
  echo "Start OsWL:   ./gradlew bootRun"
}

if ! detect_java; then
  echo -e "${RED}[OsWL] Java (JDK) is not installed or not on PATH.${NC}"
  print_install_hints
  exit 1
fi

MAJOR="$(java_major)"
if [[ "${MAJOR}" -lt "${REQUIRED_MAJOR}" ]]; then
  echo -e "${RED}[OsWL] Java ${MAJOR} found, but JDK ${REQUIRED_MAJOR}+ is required.${NC}"
  print_install_hints
  exit 1
fi

echo -e "${GREEN}[OsWL] JDK OK (Java ${MAJOR}).${NC}"
