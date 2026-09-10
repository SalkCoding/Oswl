#!/usr/bin/env python3
"""Prepare English Markdown for the Wiki without changing repository sources.

Uses only the Python standard library. Run from any directory:
    python .github/scripts/prepare-wiki.py owner/repo [--ref main]
"""

import argparse
from pathlib import Path
import re
import subprocess
from urllib.parse import quote, unquote, urlsplit, urlunsplit


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'docs/en'
OUTPUT = ROOT / 'build/wiki'
# Match code spans first so literal Markdown examples remain unchanged.
INLINE = re.compile(
    r'(?P<code>(?P<ticks>`+).*?(?P=ticks))'
    r'|(?P<prefix>!?\[[^\n]*?\]\()(?P<url><[^>]+>|[^\s)]+)'
)
REFERENCE = re.compile(r'^(?P<prefix> {0,3}\[[^]]+\]:\s*)(?P<url><[^>]+>|\S+)')
FENCE = re.compile(r'^ {0,3}(`{3,}|~{3,})')


def rewrite_url(url, source, repository, ref):
    angled = url.startswith('<') and url.endswith('>')
    value = url[1:-1] if angled else url
    parts = urlsplit(value)
    if parts.scheme or parts.netloc or not parts.path:
        return url
    base = ROOT if parts.path.startswith('/') else source.parent
    target = (base / unquote(parts.path).lstrip('/')).resolve()
    if not target.is_relative_to(ROOT):
        raise ValueError(f'{source.name}: link escapes repository: {url}')
    # Support the traditional extensionless Wiki links in a sidebar as well.
    if not target.exists() and target.with_suffix('.md').is_file():
        target = target.with_suffix('.md')
    if not target.exists():
        raise ValueError(f'{source.name}: missing link target: {url}')
    if target.parent == SOURCE and target.suffix == '.md':
        path = quote(target.stem)
    else:
        kind = 'tree' if target.is_dir() else 'blob'
        relative = quote(target.relative_to(ROOT).as_posix(), safe='/')
        path = f'https://github.com/{repository}/{kind}/{quote(ref, safe="")}/{relative}'
    rewritten = urlunsplit(('', '', path, parts.query, parts.fragment))
    return f'<{rewritten}>' if angled else rewritten


def prepare(text, source, repository, ref):
    fence = None
    output = []

    def replace(match):
        if match.re is INLINE and match['code'] is not None:
            return match[0]
        return match['prefix'] + rewrite_url(match['url'], source, repository, ref)

    for line in text.splitlines(keepends=True):
        marker = FENCE.match(line)
        if fence:
            if marker and marker[1][0] == fence[0] and len(marker[1]) >= len(fence) and not line[marker.end():].strip():
                fence = None
            output.append(line)
            continue
        if marker:
            fence = marker[1]
            output.append(line)
            continue
        if line.startswith(('    ', '\t')):
            output.append(line)
            continue
        output.append(REFERENCE.sub(replace, line) if REFERENCE.match(line) else INLINE.sub(replace, line))
    return ''.join(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repository', help='GitHub owner/repository')
    parser.add_argument('--ref', default='main', help='Repository ref used for non-Wiki links')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        parser.error('repository must be owner/repository')
    # Exclude local-only documents retained outside version control.
    tracked = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard',
                                       'docs/en/*.md'], cwd=ROOT, text=True).splitlines()
    sources = sorted({ROOT / name for name in tracked if (ROOT / name).is_file()})
    names = {source.name for source in sources}
    if not {'Home.md', '_Sidebar.md'} <= names or 'README.md' in names:
        parser.error('English docs must include Home.md and _Sidebar.md, without a competing README.md')
    # Resolve all links before writing any output, so a broken source fails publishing.
    prepared = {source.name: prepare(source.read_text(encoding='utf-8'), source, args.repository, args.ref)
                for source in sources}
    if OUTPUT.is_symlink() or OUTPUT.resolve() != ROOT / 'build/wiki':
        parser.error('Wiki output must be the repository build/wiki directory')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for existing in OUTPUT.iterdir():
        if existing.is_symlink() or not existing.is_file() or existing.suffix != '.md':
            parser.error(f'unexpected file in Wiki output: {existing.name}')
    for existing in OUTPUT.glob('*.md'):
        if existing.name not in prepared:
            existing.unlink()
    for name, text in prepared.items():
        (OUTPUT / name).write_text(text, encoding='utf-8', newline='\n')
    print(f'Prepared {len(prepared)} English Wiki pages in {OUTPUT}')


if __name__ == '__main__':
    main()
