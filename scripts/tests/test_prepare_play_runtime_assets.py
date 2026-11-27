import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "prepare_play_runtime_assets", Path(__file__).resolve().parents[1] / "prepare_play_runtime_assets.py"
)
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class PreparePlayRuntimeAssetsTest(unittest.TestCase):
    def test_packaging_includes_pinned_alpine_and_opencode_for_both_abis(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "manifest.json"
            manifest.write_text(json.dumps({"architectures": {
                abi: {"alpineUrl": "https://example.org/a", "alpineSha256": "a" * 64,
                      "openCodeUrl": "https://example.org/o", "openCodeSha256": "b" * 64}
                for abi in ("arm64-v8a", "x86_64")
            }}))
            with patch.object(sys, "argv", ["script", "--manifest", str(manifest), "--output-dir", tmp]), \
                    patch.object(assets, "write_verified") as write:
                assets.main()
            paths = {str(call.args[2].relative_to(tmp)) for call in write.call_args_list}
            self.assertEqual({f"play-runtime/{abi}/{name}.tgz" for abi in ("arm64-v8a", "x86_64")
                              for name in ("alpine", "opencode")}, paths)
    def test_verified_download_is_written(self):
        payload = b"verified alpine"
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "alpine.tar.gz"
            assets.write_verified("https://example.org/archive", hashlib.sha256(payload).hexdigest(), destination,
                                  opener=lambda _: io.BytesIO(payload))
            self.assertEqual(payload, destination.read_bytes())

    def test_checksum_mismatch_never_replaces_existing_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "alpine.tar.gz"
            destination.write_bytes(b"previous")
            with self.assertRaises(ValueError):
                assets.write_verified("https://example.org/archive", hashlib.sha256(b"expected").hexdigest(), destination,
                                      opener=lambda _: io.BytesIO(b"corrupt"))
            self.assertEqual(b"previous", destination.read_bytes())

    def test_rejects_insecure_urls(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                assets.write_verified("http://example.org/archive", "0" * 64, Path(tmp) / "out")


if __name__ == "__main__":
    unittest.main()
