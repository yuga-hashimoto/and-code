#!/usr/bin/env python3
"""Check LOAD segment alignment of every native library in an APK or AAB."""

import argparse
import struct
import sys
import zipfile

PAGE_SIZE = 16384


def load_alignments(data: bytes) -> list[int]:
    if len(data) < 16 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("invalid or unsupported ELF header")
    endian = "<" if data[5] == 1 else ">"
    is_64 = data[4] == 2
    try:
        phoff = struct.unpack_from(endian + ("Q" if is_64 else "I"), data, 32 if is_64 else 28)[0]
        entsize, count = struct.unpack_from(endian + "HH", data, 54 if is_64 else 42)
        minimum = 56 if is_64 else 32
        if not count or entsize < minimum or phoff + count * entsize > len(data):
            raise ValueError("invalid or truncated ELF program table")
        alignments = []
        for index in range(count):
            offset = phoff + index * entsize
            if struct.unpack_from(endian + "I", data, offset)[0] == 1:
                alignments.append(struct.unpack_from(endian + ("Q" if is_64 else "I"), data, offset + (48 if is_64 else 28))[0])
        if not alignments:
            raise ValueError("no ELF LOAD segments")
        return alignments
    except struct.error as exc:
        raise ValueError("truncated ELF header") from exc


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--warn-elf32", action="store_true", help="report ELF32 alignment failures as warnings")
    parser.add_argument("artifact", help="APK or AAB to inspect")
    args = parser.parse_args()
    failed = False
    try:
        with zipfile.ZipFile(args.artifact) as archive:
            names = sorted(name for name in archive.namelist() if name.endswith(".so") and "/lib/" in "/" + name)
            if not names:
                raise ValueError("no native libraries found")
            for name in names:
                try:
                    data = archive.read(name)
                    alignments = load_alignments(data)
                    ok = all(align >= PAGE_SIZE and align & (align - 1) == 0 for align in alignments)
                    warning = not ok and args.warn_elf32 and data[4] == 1
                    status = "PASS" if ok else "WARN" if warning else "FAIL"
                    print(f"{status} {name}: " + ", ".join(hex(align) for align in alignments))
                    failed |= not ok and not warning
                except ValueError as exc:
                    print(f"FAIL {name}: {exc}")
                    failed = True
    except (OSError, zipfile.BadZipFile, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
