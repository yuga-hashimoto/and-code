#!/usr/bin/env python3
"""Audit ELF LOAD alignment in Play-bundled Alpine and OpenCode archives."""
import argparse
from collections import Counter, defaultdict
import io
import json
from pathlib import Path
import struct
import sys
import tarfile
import zlib

from check_android_elf_alignment import load_alignments, PAGE_SIZE

MAX_APK_PART = 256 * 1024 * 1024


def load_segments_congruent(header):
    endian = '<' if header[5] == 1 else '>'
    is_64 = header[4] == 2
    phoff = struct.unpack_from(endian + ('Q' if is_64 else 'I'), header, 32 if is_64 else 28)[0]
    entsize, count = struct.unpack_from(endian + 'HH', header, 54 if is_64 else 42)
    for index in range(count):
        offset = phoff + index * entsize
        if struct.unpack_from(endian + 'I', header, offset)[0] != 1:
            continue
        file_offset = struct.unpack_from(endian + ('Q' if is_64 else 'I'), header, offset + (8 if is_64 else 4))[0]
        vaddr = struct.unpack_from(endian + ('Q' if is_64 else 'I'), header, offset + (16 if is_64 else 8))[0]
        if (file_offset - vaddr) % PAGE_SIZE:
            return False
    return True


class Report:
    def __init__(self):
        self.counts = defaultdict(Counter)
        self.examples = []

    def record(self, category, name, header):
        if not header.startswith(b'\x7fELF'):
            return
        try:
            alignments = load_alignments(header)
        except ValueError as exc:
            raise ValueError(f'Cannot inspect {name}: {exc}') from exc
        congruent = load_segments_congruent(header)
        ok = all(a >= PAGE_SIZE and a & (a - 1) == 0 for a in alignments) and congruent
        self.counts[category]['aligned' if ok else 'unaligned'] += 1
        if not ok and len(self.examples) < 20:
            self.examples.append(f'{name}: ' + ', '.join(hex(a) for a in alignments) +
                                 ('' if congruent else '; file offset and address differ modulo 16 KB'))


def scan_tar(archive, category, prefix, report):
    for member in archive:
        if not member.isfile():
            continue
        source = archive.extractfile(member)
        if source is not None:
            report.record(category, f'{prefix}/{member.name}', source.read(65536))


def scan_apk(data, name, report):
    parts = 0
    while data:
        decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
        try:
            raw = decoder.decompress(data, MAX_APK_PART + 1)
        except zlib.error as exc:
            raise ValueError(f'Invalid APK gzip member: {name}') from exc
        if len(raw) > MAX_APK_PART or not decoder.eof:
            raise ValueError(f'Oversized or truncated APK member: {name}')
        data = decoder.unused_data
        parts += 1
        try:
            with tarfile.open(fileobj=io.BytesIO(raw), mode='r:') as archive:
                scan_tar(archive, 'packages', name, report)
        except tarfile.TarError as exc:
            raise ValueError(f'Invalid APK tar member: {name}') from exc
    if not parts:
        raise ValueError(f'Empty APK: {name}')


def audit(abi, runtime, cache):
    report = Report()
    for category, path in (
        ('base', runtime / 'play-runtime' / abi / 'alpine.tgz'),
        ('opencode', runtime / 'play-runtime' / abi / 'opencode.tgz'),
    ):
        with tarfile.open(path, mode='r:gz') as archive:
            scan_tar(archive, category, path.name, report)
    with tarfile.open(cache / 'play-runtime' / abi / 'apk-cache.tgz', mode='r:gz') as archive:
        for member in archive:
            if member.isfile() and member.name.endswith('.apk'):
                if member.size > 128 * 1024 * 1024:
                    raise ValueError(f'Oversized APK: {member.name}')
                scan_apk(archive.extractfile(member).read(), member.name, report)
    for category in ('base', 'opencode', 'packages'):
        if sum(report.counts[category].values()) == 0:
            raise ValueError(f'No ELF files found for {abi}/{category}')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = {}
    try:
        for abi in ('arm64-v8a', 'x86_64'):
            report = audit(abi, args.runtime, args.cache)
            result[abi] = {'counts': dict(report.counts), 'examples': report.examples}
            print(abi, json.dumps(result[abi]['counts'], sort_keys=True))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    except (OSError, ValueError, tarfile.TarError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 2
    return int(any(counts.get('unaligned', 0) for abi in result.values() for counts in abi['counts'].values()))


if __name__ == '__main__':
    raise SystemExit(main())
