#!/usr/bin/env python3
"""Render the pinned Play Alpine package and upstream build-recipe inventory."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'runtime_tools/play-apk-cache.lock.json'
BASE_NOTICE = ROOT / 'app/src/main/assets/legal/oss_licenses.md'
PLAY_NOTICE = ROOT / 'app/src/play/assets/legal/oss_licenses.md'
DOCUMENTATION = ROOT / 'docs/play-store/ALPINE_PACKAGE_INVENTORY.md'


def render(lock):
    groups = defaultdict(lambda: {'packages': set(), 'licenses': set(), 'abis': set()})
    def add(item, abi, name):
        origin, commit, repo = item['origin'], item['aportsCommit'], item['repository']
        if not re.fullmatch(r'[a-zA-Z0-9+_.-]+', origin) or not re.fullmatch(r'[0-9a-f]{40}', commit):
            raise ValueError('Invalid source provenance in Alpine lock')
        if repo not in ('main', 'community'):
            raise ValueError('Invalid Alpine repository')
        group = groups[(origin, commit, repo)]
        group['packages'].add(f"{name} {item['version']}")
        group['licenses'].add(item['license'])
        group['abis'].add(abi)
    for item in lock['basePackages']:
        add(item, item['abi'], item['name'])
    for abi, architecture in lock['architectures'].items():
        for item in architecture['packages']:
            add(item, abi, item['package'])
    lines = [
        '# Play Alpine package inventory', '',
        'This inventory lists packages in the bundled Alpine base and mandatory offline cache.',
        'Versions, declared licenses and aports revisions come from Alpine package metadata.',
        'The linked aports directories contain the matching APKBUILD and packaging files.',
        'They refer to upstream source archives; this inventory alone is not a complete',
        'corresponding-source bundle or a substitute for reviewing each license obligation.', '',
        'Package archive hashes and signed repository index hashes are recorded in',
        'runtime_tools/play-apk-cache.lock.json in the AndCode source repository.', '',
    ]
    for (origin, commit, repo), group in sorted(groups.items()):
        lines += [f'## {origin}',
                  'Packages: ' + ', '.join(sorted(group['packages'])),
                  'Architectures: ' + ', '.join(sorted(group['abis'])),
                  'Declared licenses: ' + '; '.join(sorted(group['licenses'])),
                  f'Alpine build recipe: https://github.com/alpinelinux/aports/tree/{commit}/{repo}/{origin}', '']
    return '\n'.join(lines).rstrip() + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    content = render(json.loads(LOCK.read_text()))
    outputs = {
        PLAY_NOTICE: BASE_NOTICE.read_text().rstrip() + '\n\n' + content,
        DOCUMENTATION: content,
    }
    for path, expected in outputs.items():
        if args.check:
            if not path.is_file() or path.read_text() != expected:
                raise SystemExit(f'Outdated Alpine package inventory: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected)


if __name__ == '__main__':
    main()
