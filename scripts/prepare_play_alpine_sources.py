#!/usr/bin/env python3
"""Assemble pinned Alpine sources for the experimental Play runtime.

This is evidence for a subset of bundled components, not a complete license compliance package.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import tarfile
import tempfile
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, build_opener

ROOT = Path(__file__).resolve().parents[1]
HOSTS = {'distfiles.alpinelinux.org', 'raw.githubusercontent.com'}
SAFE_PATH = re.compile(r'^[A-Za-z0-9_./+@~-]+$')
MAX_BYTES = 2 * 1024**3


def safe_path(value):
    path = PurePosixPath(value)
    if not value or not SAFE_PATH.fullmatch(value) or path.is_absolute() or any(p in ('.', '..') for p in value.split('/')):
        raise ValueError(f'Unsafe relative path: {value!r}')
    return path


def validate_url(url):
    parsed = urlsplit(url)
    if (parsed.scheme != 'https' or parsed.hostname not in HOSTS or parsed.username or parsed.password or
            parsed.port not in (None, 443) or parsed.query or parsed.fragment):
        raise ValueError('Source URL must use an approved HTTPS host')


def recipe_url(commit, repo, origin, relative):
    return f'https://raw.githubusercontent.com/alpinelinux/aports/{commit}/{repo}/{origin}/{quote(str(relative), safe="/")}'


class RestrictedRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def verify_file(path, algorithm, expected):
    digest = hashlib.new(algorithm)
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise ValueError(f'{algorithm} mismatch: {path.name}')


def verify_git_blob(path, expected):
    digest = hashlib.sha1()
    digest.update(f'blob {path.stat().st_size}\0'.encode())
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise ValueError(f'Git blob mismatch: {path.name}')


def verify_source_list(recipe, entries):
    text = recipe.read_text(encoding='utf-8')
    actual = re.findall(r'(?m)^([0-9a-f]{128})\s+([^\s]+)', text)
    expected = [(e['sha512'], e['name']) for e in entries]
    if sorted(actual) != sorted(expected) or len({name for _, name in expected}) != len(expected):
        raise ValueError('Source checksums do not match pinned APKBUILD')


def eligible_groups(apk_lock):
    groups = set()
    packages = list(apk_lock['basePackages'])
    for arch in apk_lock['architectures'].values():
        packages.extend(arch['packages'])
    for item in packages:
        groups.add((item['repository'], item['origin'], item['aportsCommit']))
    return groups


def verify_groups(lock, apk_lock):
    expected = eligible_groups(apk_lock)
    actual = [(g['repository'], g['origin'], g['commit']) for g in lock['groups']]
    if len(actual) != len(set(actual)) or set(actual) != expected or lock['alpineRelease'] != apk_lock['alpineRelease']:
        raise ValueError('Source lock does not match bundled Alpine packages')


def materialize(url, target, algorithm, digest, opener):
    validate_url(url)
    if not re.fullmatch('[0-9a-f]{%d}' % (128 if algorithm == 'sha512' else 64), digest):
        raise ValueError('Invalid digest')
    if target.is_file():
        try:
            verify_file(target, algorithm, digest)
            return target
        except ValueError:
            pass
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = None
    try:
        with opener(url, timeout=60) as response, tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as output:
            tmp = Path(output.name)
            size = 0
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_BYTES:
                    raise ValueError('Source file exceeds size limit')
                output.write(chunk)
        verify_file(tmp, algorithm, digest)
        os.replace(tmp, target)
        return target
    finally:
        if tmp is not None:
            tmp.unlink(missing_ok=True)


def prepare(lock, apk_lock, cache, output):
    verify_groups(lock, apk_lock)
    tasks = []
    for group in lock['groups']:
        repo, origin, commit = group['repository'], group['origin'], group['commit']
        if repo not in ('main', 'community') or not re.fullmatch(r'[a-zA-Z0-9+_.-]+', origin) or not re.fullmatch(r'[0-9a-f]{40}', commit):
            raise ValueError('Invalid package provenance')
        base = Path(repo) / origin / commit
        for entry in group['recipeFiles']:
            rel = safe_path(entry['path'])
            expected_url = recipe_url(commit, repo, origin, rel)
            if entry['url'] != expected_url:
                raise ValueError('Recipe URL differs from pinned revision')
            tasks.append((entry['url'], base / 'recipe' / str(rel), 'sha256', entry['sha256']))
        if not any(e['path'] == 'APKBUILD' for e in group['recipeFiles']):
            raise ValueError('Missing APKBUILD')
        for entry in group['sources']:
            rel = safe_path(entry['name'])
            if len(rel.parts) != 1:
                raise ValueError('Source names must be single path components')
            tasks.append((entry['url'], base / 'sources' / str(rel), 'sha512', entry['sha512']))
    if len({str(t[1]) for t in tasks}) != len(tasks):
        raise ValueError('Duplicate source bundle entry')
    opener = build_opener(RestrictedRedirects()).open
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda t: materialize(t[0], cache / t[1], t[2], t[3], opener), tasks))
    for group in lock['groups']:
        base = cache / group['repository'] / group['origin'] / group['commit']
        for entry in group['recipeFiles']:
            verify_git_blob(base / 'recipe' / str(safe_path(entry['path'])), entry['gitBlobSha1'])
        verify_source_list(base / 'recipe/APKBUILD', group['sources'])
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as raw:
            temp = Path(raw.name)
            with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0, compresslevel=1) as gz:
                with tarfile.open(fileobj=gz, mode='w') as archive:
                    manifest = (json.dumps(lock, sort_keys=True, indent=2) + '\n').encode()
                    info = tarfile.TarInfo('source-lock.json')
                    info.size = len(manifest)
                    info.mode = 0o644
                    archive.addfile(info, io.BytesIO(manifest))
                    for _, relative, _, _ in sorted(tasks, key=lambda t: str(t[1])):
                        path = cache / relative
                        info = tarfile.TarInfo(str(relative))
                        info.size = path.stat().st_size
                        info.mode = 0o644
                        with path.open('rb') as data:
                            archive.addfile(info, data)
        os.replace(temp, output)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)
    verify = hashlib.sha256()
    with output.open('rb') as result:
        for chunk in iter(lambda: result.read(1024 * 1024), b''):
            verify.update(chunk)
    print(f'Created {output}; SHA-256 {verify.hexdigest()}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, default=ROOT / 'runtime_tools/play-alpine-sources.lock.json')
    parser.add_argument('--apk-lock', type=Path, default=ROOT / 'runtime_tools/play-apk-cache.lock.json')
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(json.loads(args.lock.read_text()), json.loads(args.apk_lock.read_text()), args.cache, args.output)


if __name__ == '__main__':
    main()
