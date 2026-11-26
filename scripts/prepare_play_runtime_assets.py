#!/usr/bin/env python3
"""Fetch pinned Alpine and OpenCode archives for Play-distributed app assets."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, build_opener

MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
ABIS = ("arm64-v8a", "x86_64")


class HttpsRedirectsOnly(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlsplit(newurl).scheme != "https":
            raise ValueError("Runtime archive redirect must use HTTPS")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def write_verified(url: str, expected_sha256: str, destination: Path, opener=None) -> None:
    if urlsplit(url).scheme != "https" or not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ValueError("Invalid runtime archive URL or SHA-256")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and hashlib.sha256(destination.read_bytes()).hexdigest() == expected_sha256:
        return
    opener = opener or build_opener(HttpsRedirectsOnly()).open
    temporary = None
    try:
        with opener(url) as source, tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            digest = hashlib.sha256()
            total = 0
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_ARCHIVE_BYTES:
                    raise ValueError("Runtime archive exceeds size limit")
                digest.update(chunk)
                output.write(chunk)
            if digest.hexdigest() != expected_sha256:
                raise ValueError("Runtime archive SHA-256 mismatch")
        os.replace(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    for abi in ABIS:
        arch = manifest["architectures"][abi]
        destination = args.output_dir / "play-runtime" / abi / "alpine.tgz"
        write_verified(arch["alpineUrl"], arch["alpineSha256"], destination)
        # aapt transforms .gz assets into a different name and payload; remove the old generated
        # name when reusing a build directory so it cannot inflate or confuse the final bundle.
        destination.with_name("alpine.tar.gz").unlink(missing_ok=True)
        opencode = args.output_dir / "play-runtime" / abi / "opencode.tgz"
        write_verified(arch["openCodeUrl"], arch["openCodeSha256"], opencode)
        print(f"Verified Play runtime asset: {abi}")


if __name__ == "__main__":
    main()
