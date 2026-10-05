#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import re
from pathlib import Path


ANDROID_ABIS = ("arm64-v8a", "x86_64")
NATIVE_EXECUTABLES = {
    "bin/proot": "libopencode_android_proot.so",
    "libexec/proot/loader": "libopencode_android_proot_loader.so",
    "libexec/proot/loader32": "libopencode_android_proot_loader32.so",
}
RUNTIME_LIBRARIES = {
    # pattern -> (destination name, required). The talloc SONAME tracks the package
    # version (e.g. libtalloc.so.2.4.3, or .2.5.0 after the 2.5.0 bump), so it is matched by
    # glob rather than hardcoded.
    "libandroid-shmem.so": ("libandroid-shmem.so", True),
    "libc++_shared.so": ("libc++_shared.so", False),
    "libtalloc.so.*": ("libtalloc.so", True),
}
NATIVE_EXECUTABLE_SEARCH_DIRS = ("bin", "libexec")


def select_runtime_library(lib_dir: Path, source_pattern: str) -> Path | None:
    matches = sorted(lib_dir.glob(source_pattern))
    # Prefer a real file over a version symlink so the copied payload is self-contained.
    regular = [path for path in matches if not path.is_symlink()]
    chosen = (regular or matches)
    return chosen[-1] if chosen else None


def native_executable_name(relative_path: str) -> str:
    existing = NATIVE_EXECUTABLES.get(relative_path)
    if existing:
        return existing
    safe = re.sub(r"[^0-9A-Za-z_]+", "_", relative_path.replace("\\", "/"))
    safe = safe.strip("_") or "command"
    return f"libopencode_exec_{safe}.so"


def patch_needed(path: Path, old_name: str, new_name: str) -> None:
    if not path.is_file():
        return
    old = old_name.encode("utf-8") + b"\0"
    new = new_name.encode("utf-8") + b"\0"
    if len(new) > len(old):
        raise ValueError(f"replacement {new_name!r} is longer than {old_name!r}")
    payload = path.read_bytes()
    if old not in payload:
        return
    payload = payload.replace(old, new + (b"\0" * (len(old) - len(new))))
    path.write_bytes(payload)


def copy_abi(linux_assets_dir: Path, output_dir: Path, abi: str) -> None:
    prefix_dir = linux_assets_dir / "opencode-runtime" / abi / "prefix"
    abi_output = output_dir / abi
    abi_output.mkdir(parents=True, exist_ok=True)
    for source_relative, destination_name in sorted(NATIVE_EXECUTABLES.items()):
        source = prefix_dir / source_relative
        if not source.is_file():
            raise FileNotFoundError(f"Required Android runtime executable missing: {source}")
        destination = abi_output / destination_name
        shutil.copy2(source, destination)
        destination.chmod(0o755)
    lib_dir = prefix_dir / "lib"
    for source_pattern, (destination_name, required) in sorted(RUNTIME_LIBRARIES.items()):
        source = select_runtime_library(lib_dir, source_pattern)
        if source is None:
            if required:
                raise FileNotFoundError(f"Required Android runtime library missing: {lib_dir / source_pattern}")
            continue
        destination = abi_output / destination_name
        shutil.copy2(source, destination)
        destination.chmod(0o755)
    patch_needed(abi_output / "libopencode_android_proot.so", "libtalloc.so.2", "libtalloc.so")


def prepare_native_libs(linux_assets_dir: Path, output_dir: Path) -> None:
    if output_dir.exists():
        for item in output_dir.rglob("*"):
            if item.is_file():
                item.unlink()
        for item in sorted((p for p in output_dir.rglob("*") if p.is_dir()), reverse=True):
            item.rmdir()
    output_dir.mkdir(parents=True, exist_ok=True)
    for abi in ANDROID_ABIS:
        copy_abi(linux_assets_dir, output_dir, abi)


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare Android-packaged native launcher libraries")
    parser.add_argument("--linux-assets-dir", required=True, help="Generated AndCode runtime assets directory")
    parser.add_argument("--output-dir", required=True, help="Generated jniLibs output directory")
    args = parser.parse_args()
    prepare_native_libs(
        linux_assets_dir=Path(args.linux_assets_dir).expanduser().resolve(),
        output_dir=Path(args.output_dir).expanduser().resolve(),
    )


if __name__ == "__main__":
    main()
