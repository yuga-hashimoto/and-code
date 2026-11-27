#!/usr/bin/env python3
"""Build verified, deterministic Alpine package caches for the Play flavor."""
from concurrent.futures import ThreadPoolExecutor
import argparse
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import tarfile
import tempfile
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, build_opener

MAX_ITEM_BYTES = 128 * 1024 * 1024
SAFE_NAME = re.compile(r'^[A-Za-z0-9_+@.-]+$')


class AlpineRedirectsOnly(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def validate_url(url):
    parsed = urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname != 'dl-cdn.alpinelinux.org' or parsed.username or parsed.password:
        raise ValueError('Package URL must use the official Alpine HTTPS CDN')


def validate_name(name):
    if not SAFE_NAME.fullmatch(name) or name in ('.', '..') or '..' in name:
        raise ValueError('Invalid cache file name')


def verified_file(path, digest):
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == digest


def verify_base_inventory(expected, abi, archive):
    with tarfile.open(archive, 'r:gz') as tar:
        installed = tar.extractfile('./lib/apk/db/installed').read().decode('utf-8')
    actual = []
    for paragraph in installed.split('\n\n'):
        fields = dict(line.split(':', 1) for line in paragraph.splitlines() if len(line) > 2 and line[1] == ':')
        if fields:
            actual.append((fields['P'], fields['V'], fields.get('o', fields['P']),
                           fields.get('L', 'unknown'), fields.get('c', '')))
    recorded = [(item['name'], item['version'], item['origin'], item['license'], item['aportsCommit'])
                for item in expected if item['abi'] == abi and item['repository'] == 'main']
    if sorted(actual) != sorted(recorded):
        raise ValueError(f'Alpine base package inventory does not match {abi} rootfs')


def verify_package_inventory(lock, index_dir, arch, branch):
    packages = {}
    for entry in lock['indexes']:
        validate_name(entry['file'])
        path = index_dir / entry['file']
        if not verified_file(path, entry['sha256']):
            raise ValueError(f'Signed Alpine index hash mismatch: {entry["file"]}')
        with tarfile.open(path, 'r:gz') as archive:
            data = archive.extractfile('APKINDEX').read().decode('utf-8')
        for paragraph in data.split('\n\n'):
            fields = dict(line.split(':', 1) for line in paragraph.splitlines() if len(line) > 2 and line[1] == ':')
            if all(key in fields for key in ('P', 'V', 'C')):
                packages[(entry['repository'], fields['P'], fields['V'])] = fields
    for entry in lock['packages']:
        key = (entry['repository'], entry['package'], entry['version'])
        fields = packages.get(key)
        if fields is None or not fields['C'].startswith('Q1'):
            raise ValueError(f'Package not present in signed index: {key}')
        checksum = base64.b64decode(fields['C'][2:], validate=True).hex()[:8]
        name = f'{entry["package"]}-{entry["version"]}'
        expected_path = f'/alpine/v{branch}/{entry["repository"]}/{arch}/{name}.apk'
        if (entry['cacheName'] != f'{name}.{checksum}.apk' or
                urlsplit(entry['url']).path != expected_path or
                entry['origin'] != fields.get('o', fields['P']) or
                entry['license'] != fields.get('L', 'unknown') or
                entry['aportsCommit'] != fields.get('c', '')):
            raise ValueError(f'Package metadata differs from signed index: {key}')


def download_package(entry, directory, opener):
    url, name, digest = entry['url'], entry['cacheName'], entry['sha256']
    validate_url(url)
    target = directory / name
    if verified_file(target, digest):
        return target
    temp = None
    try:
        with opener(url) as source, tempfile.NamedTemporaryFile(dir=directory, delete=False) as output:
            temp = Path(output.name)
            sha = hashlib.sha256()
            size = 0
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_ITEM_BYTES:
                    raise ValueError('Alpine package exceeds size limit')
                sha.update(chunk)
                output.write(chunk)
            if sha.hexdigest() != digest:
                raise ValueError(f'SHA-256 mismatch for {name}')
        os.replace(temp, target)
        return target
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def build_cache(lock, index_dir, download_dir, output, opener=None):
    indexes, packages = lock['indexes'], lock['packages']
    items = indexes + packages
    names = [entry['cacheName'] for entry in items]
    for name in names:
        validate_name(name)
    if len(names) != len(set(names)):
        raise ValueError('Duplicate cache file name')
    for entry in items:
        if not re.fullmatch(r'[0-9a-f]{64}', entry['sha256']):
            raise ValueError('Invalid SHA-256')
    files = {}
    for entry in indexes:
        validate_name(entry['file'])
        path = index_dir / entry['file']
        if not verified_file(path, entry['sha256']):
            raise ValueError(f'Signed Alpine index hash mismatch: {entry["file"]}')
        files[entry['cacheName']] = path
    download_dir.mkdir(parents=True, exist_ok=True)
    opener = opener or build_opener(AlpineRedirectsOnly()).open
    with ThreadPoolExecutor(max_workers=8) as pool:
        paths = list(pool.map(lambda entry: download_package(entry, download_dir, opener), packages))
    files.update((entry['cacheName'], path) for entry, path in zip(packages, paths))
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as raw:
            temp = Path(raw.name)
            with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0, compresslevel=1) as gz:
                with tarfile.open(fileobj=gz, mode='w') as archive:
                    for name, path in sorted(files.items()):
                        info = tarfile.TarInfo(name)
                        info.size = path.stat().st_size
                        info.mode = 0o600
                        info.mtime = 0
                        with path.open('rb') as source:
                            archive.addfile(info, source)
        os.replace(temp, output)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)
    return hashlib.sha256(output.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, required=True)
    parser.add_argument('--indexes', type=Path, required=True)
    parser.add_argument('--base-assets', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--cache-dir', type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads(args.lock.read_text())
    result = {}
    for abi in ('arm64-v8a', 'x86_64'):
        verify_base_inventory(lock['basePackages'], abi, args.base_assets / 'play-runtime' / abi / 'alpine.tgz')
        arch = 'aarch64' if abi == 'arm64-v8a' else 'x86_64'
        branch = '.'.join(lock['alpineRelease'].split('.')[:2])
        verify_package_inventory(lock['architectures'][abi], args.indexes, arch, branch)
        output = args.output_dir / 'play-runtime' / abi / 'apk-cache.tgz'
        result[abi] = build_cache(lock['architectures'][abi], args.indexes, args.cache_dir / abi, output)
        print(f'Verified Play Alpine package cache: {abi}')
    metadata = args.output_dir / 'play-runtime' / 'apk-cache-hashes.json'
    metadata.write_text(json.dumps(result, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
