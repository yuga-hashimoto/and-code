#!/usr/bin/env python3
"""Prepare a disposable QEMU research payload, never included in a Play artifact."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tarfile
import tempfile
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'runtime_tools'))
from termux_assets import load_data_tar_bytes_from_deb  # noqa: E402

BASE = 'https://packages.termux.dev/apt/termux-main/'
PREFIX = 'data/data/com.termux/files/usr/'
NAME = re.compile(r'^[A-Za-z0-9_.+@-]+$')


def extract_runtime(archive, root, links):
    (root / 'lib').mkdir(parents=True, exist_ok=True)
    for member in archive:
        path = member.name.removeprefix('./')
        if not path.startswith(PREFIX):
            continue
        rel = path[len(PREFIX):]
        if rel == 'bin/qemu-x86_64' and member.isfile():
            target = root / 'qemu-x86_64'
        elif rel.startswith('lib/') and NAME.fullmatch(rel[4:]) and '.so' in rel[4:]:
            target = root / 'lib' / rel[4:]
            if member.issym() or member.islnk():
                links[rel[4:]] = member.linkname
                continue
            if not member.isfile():
                continue
        else:
            continue
        if member.size > 128 * 1024 * 1024:
            raise ValueError('Research file exceeds size limit')
        data = archive.extractfile(member).read()
        if target.exists() and target.read_bytes() != data:
            raise ValueError(f'Conflicting research payload: {target.name}')
        target.write_bytes(data)


def materialize_links(root, links):
    for name in links:
        current = name
        seen = set()
        while current in links:
            if current in seen:
                raise ValueError('Cyclic library link')
            seen.add(current)
            target = links[current]
            if not NAME.fullmatch(target) or target in ('.', '..'):
                raise ValueError('Unsafe library link')
            current = target
        source = root / 'lib' / current
        if not source.is_file():
            raise ValueError(f'Missing library link target: {current}')
        dest = root / 'lib' / name
        if dest.exists() and dest.read_bytes() != source.read_bytes():
            raise ValueError(f'Conflicting library link: {name}')
        shutil.copyfile(source, dest)


class Redirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urlsplit(newurl)
        if parsed.scheme != 'https' or parsed.hostname != 'packages.termux.dev' or parsed.username or parsed.password:
            raise ValueError('Unexpected package redirect')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def prepare(lock, output):
    if lock.get('architecture') != 'x86_64':
        raise ValueError('This experiment is limited to x86_64')
    packages = lock['packages']
    if len({p['name'] for p in packages}) != len(packages):
        raise ValueError('Duplicate package')
    opener = build_opener(Redirects()).open
    def download(entry):
        name, digest = entry['filename'], entry['sha256']
        if not re.fullmatch(r'pool/main/[A-Za-z0-9_./+:-]+\.deb', name) or '..' in name.split('/'):
            raise ValueError('Unsafe package path')
        if not re.fullmatch(r'[0-9a-f]{64}', digest):
            raise ValueError('Invalid package hash')
        with opener(BASE + name, timeout=60) as response:
            data = response.read(128 * 1024 * 1024 + 1)
        if len(data) > 128 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('Package integrity failure')
        return data
    with ThreadPoolExecutor(max_workers=6) as pool:
        payloads = list(pool.map(download, packages))
    if output.exists():
        raise ValueError('Research output already exists; choose a new directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        staging = Path(tmp) / 'payload'
        staging.mkdir()
        links = {}
        for payload in payloads:
            data, name = load_data_tar_bytes_from_deb(payload)
            with tarfile.open(fileobj=io.BytesIO(data), mode='r:*') as archive:
                extract_runtime(archive, staging, links)
        materialize_links(staging, links)
        if not (staging / 'qemu-x86_64').is_file():
            raise ValueError('QEMU executable missing')
        os.rename(staging, output)
    print(f'Prepared research-only QEMU payload at {output}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, default=ROOT / 'runtime_tools/play-qemu-experiment.lock.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(json.loads(args.lock.read_text()), args.output)


if __name__ == '__main__':
    main()
