#!/usr/bin/env bash
set -euo pipefail
export LANG=C.UTF-8 LC_ALL=C.UTF-8

# Builds _site/ for GitHub Project Pages (https://<org>.github.io/Oswl/).
# Landing HTML lives in landing/ (not served by the Spring Boot app).
# Usage: .github/scripts/prepare-pages-site.sh [owner/repo]

REPO="${1:-}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

mkdir -p _site
cp landing/index.html _site/index.html
cp landing/landing-i18n.js _site/
cp -r landing/i18n _site/
cp -r oswl-app/src/main/resources/static/css _site/
cp -r oswl-app/src/main/resources/static/icon _site/
cp -r oswl-app/src/main/resources/static/graphic _site/
mkdir -p _site/img
cp -r oswl-app/src/main/resources/static/img/screenshots _site/img/
touch _site/.nojekyll

OSS_NOTICES_LINK="${REPO:+https://github.com/${REPO}/blob/main/THIRD_PARTY_LICENSES.md}"
OSS_NOTICES_LINK="${OSS_NOTICES_LINK:-https://github.com/SalkCoding/Oswl/blob/main/THIRD_PARTY_LICENSES.md}"

# Root-absolute asset paths break on Project Pages; rewrite to relative paths.
sed -i \
  -e 's|href="/css/|href="css/|g' \
  -e 's|href="/icon/|href="icon/|g' \
  -e 's|src="/icon/|src="icon/|g' \
  -e 's|src="/graphic/|src="graphic/|g' \
  -e 's|src="/img/|src="img/|g' \
  -e 's|src="/landing-i18n.js"|src="landing-i18n.js"|g' \
  -e "s|href=\"/oss-notices\"|href=\"${OSS_NOTICES_LINK}\"|g" \
  _site/index.html

test -f _site/index.html
test -f _site/landing-i18n.js
test -f _site/i18n/en.json
test -f _site/i18n/ko.json
test -f _site/css/tailwind.css
test -f _site/icon/icon-logo.svg
test -f _site/graphic/symbol_w.svg
test -f _site/img/screenshots/security-center.png

echo "Pages site ready at _site/"
