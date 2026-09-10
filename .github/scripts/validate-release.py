#!/usr/bin/env python3
"""Validate release inputs without publishing or changing Git references."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]

def keys(value, prefix=''):
    return {prefix + k for k, v in value.items() if not isinstance(v, dict)} | set().union(
        *(keys(v, prefix + k + '.') for k, v in value.items() if isinstance(v, dict)), set())

def properties(path):
    result = set()
    for line in path.read_text(encoding='utf-8').splitlines():
        if '=' not in line or line.lstrip().startswith(('#', '!')):
            continue
        key = line.split('=', 1)[0].strip()
        if key in result:
            raise ValueError(f'{path.name}: duplicate key {key}')
        result.add(key)
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag')
    parser.add_argument('--notes-output', type=Path)
    args = parser.parse_args()
    match = re.search(r"^version = '([0-9]+(?:\.[0-9]+){2,3})'$", (ROOT / 'build.gradle').read_text(encoding='utf-8'), re.M)
    if not match:
        parser.error('build.gradle must declare a three- or four-part numeric release version')
    version = match.group(1)
    if args.tag and args.tag != 'v' + version:
        parser.error(f'tag {args.tag!r} does not match build version v{version}')
    templates = ROOT / 'src/main/resources/templates'
    for template in templates.rglob('*.html'):
        for asset_version in re.findall(r"\(v='([^']+)'\)", template.read_text(encoding='utf-8')):
            if asset_version != version:
                parser.error(f'{template.relative_to(ROOT)}: asset cache version differs')
    landing = (ROOT / 'landing/index.html').read_text(encoding='utf-8')
    if f'data-i18n="hero.badge">OsWL {version}</span>' not in landing:
        parser.error('landing fallback release version differs')
    sarif = (ROOT / 'src/main/java/com/salkcoding/oswl/service/reporting/SarifExportService.java').read_text(encoding='utf-8')
    if f'driver.put("version", "{version}")' not in sarif:
        parser.error('SARIF tool version does not match build.gradle')
    changelog = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8')
    match = re.search(r'^## \[' + re.escape(version) + r'\][^\n]*\n(.*?)(?=^## |\Z)', changelog, re.M | re.S)
    if not match:
        parser.error(f'CHANGELOG.md has no entry for {version}')
    notes = match.group(1).strip()
    # Release bodies are read on GitHub, not relative to the repository root.
    notes = re.sub(r'\]\((docs/[^)]+)\)', lambda m: f'](https://github.com/SalkCoding/Oswl/blob/v{version}/{m.group(1)})', notes)
    base_keys = None
    ui_keys = None
    for lang, suffix in [('en', ''), ('ko', '_ko'), ('ja', '_ja')]:
        data = json.loads((ROOT / f'landing/i18n/{lang}.json').read_text(encoding='utf-8'))
        current = keys(data)
        if base_keys is not None and current != base_keys:
            parser.error(f'landing {lang} translation keys differ')
        base_keys = current
        if data['hero']['badge'] != 'OsWL ' + version:
            parser.error(f'landing {lang} release version differs')
        current = properties(ROOT / f'src/main/resources/messages{suffix}.properties')
        if ui_keys is not None and current != ui_keys:
            parser.error(f'application {lang} translation keys differ')
        ui_keys = current
        if not (ROOT / f'docs/{lang}/Whats-New-v{version}.md').is_file():
            parser.error(f'missing {lang} release notes')
    if args.notes_output:
        args.notes_output.parent.mkdir(parents=True, exist_ok=True)
        args.notes_output.write_text(notes + '\n', encoding='utf-8')
    print(f'Release inputs validated: v{version}; English/Korean/Japanese keys match')

if __name__ == '__main__':
    main()
