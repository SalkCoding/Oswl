#!/usr/bin/env bash
set -euo pipefail

# Builds _site/ for GitHub Project Pages (https://<org>.github.io/Oswl/).
# Usage: .github/scripts/prepare-pages-site.sh [owner/repo]

REPO="${1:-}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

mkdir -p _site
cp src/main/resources/static/landing/index.html _site/index.html
cp -r src/main/resources/static/css _site/
cp -r src/main/resources/static/icon _site/
cp -r src/main/resources/static/graphic _site/
mkdir -p _site/img
cp -r src/main/resources/static/img/screenshots _site/img/
touch _site/.nojekyll

LICENSE_LINK="${REPO:+https://github.com/${REPO}/blob/main/LICENSE}"
LICENSE_LINK="${LICENSE_LINK:-https://github.com/SalkCoding/Oswl/blob/main/LICENSE}"

# Root-absolute asset paths break on Project Pages; rewrite to relative paths.
sed -i \
  -e 's|href="/css/|href="css/|g' \
  -e 's|href="/icon/|href="icon/|g' \
  -e 's|src="/icon/|src="icon/|g' \
  -e 's|src="/graphic/|src="graphic/|g' \
  -e 's|src="/img/|src="img/|g' \
  -e "s|href=\"/oss-notices\"|href=\"${LICENSE_LINK}\"|g" \
  _site/index.html

test -f _site/index.html
test -f _site/css/tailwind.css
test -f _site/icon/icon-logo.svg
test -f _site/graphic/symbol_w.svg
test -f _site/img/screenshots/security-center.png

echo "Pages site ready at _site/"
