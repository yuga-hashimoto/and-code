import hashlib
import base64
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

path = Path(__file__).resolve().parents[1] / 'prepare_play_apk_cache.py'
spec = importlib.util.spec_from_file_location('prepare_play_apk_cache', path)
try:
    cache = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(cache)
except FileNotFoundError:
    cache = None


def sha(data):
    return hashlib.sha256(data).hexdigest()


class PreparePlayApkCacheTest(unittest.TestCase):
    def test_package_metadata_must_match_the_pinned_repository_index(self):
        self.assertIsNotNone(cache, 'offline APK cache builder is required')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            index = root / 'index.tar.gz'
            digest = b'\x12\x34\x56\x78' + b'\0' * 16
            data = ('C:Q1' + base64.b64encode(digest).decode() + '\nP:bash\nV:5-r0\n'
                    'L:GPL-3.0-or-later\no:bash\nc:' + 'a' * 40 + '\n\n').encode()
            with tarfile.open(index, 'w:gz') as tar:
                info = tarfile.TarInfo('APKINDEX')
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
            lock = {'indexes': [{'file': 'index.tar.gz', 'repository': 'main', 'sha256': sha(index.read_bytes())}],
                    'packages': [{'package': 'bash', 'version': '5-r0', 'repository': 'main',
                                  'origin': 'bash', 'license': 'GPL-3.0-or-later', 'aportsCommit': 'a' * 40,
                                  'cacheName': 'bash-5-r0.12345678.apk', 'sha256': 'b' * 64,
                                  'url': 'https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/bash-5-r0.apk'}]}
            cache.verify_package_inventory(lock, root, 'aarch64', '3.24')
            lock['packages'][0]['license'] = 'MIT'
            with self.assertRaises(ValueError):
                cache.verify_package_inventory(lock, root, 'aarch64', '3.24')

    def test_base_inventory_must_match_the_pinned_rootfs(self):
        self.assertIsNotNone(cache, 'offline APK cache builder is required')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / 'alpine.tgz'
            data = b'P:apk-tools\nV:3-r0\no:apk-tools\nL:GPL-2.0-only\nc:' + b'a' * 40 + b'\n\n'
            with tarfile.open(archive, 'w:gz') as tar:
                info = tarfile.TarInfo('./lib/apk/db/installed')
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
            expected = [{'abi': 'arm64-v8a', 'name': 'apk-tools', 'version': '3-r0', 'origin': 'apk-tools',
                         'license': 'GPL-2.0-only', 'aportsCommit': 'a' * 40, 'repository': 'main'}]
            cache.verify_base_inventory(expected, 'arm64-v8a', archive)
            expected[0]['version'] = 'wrong'
            with self.assertRaises(ValueError):
                cache.verify_base_inventory(expected, 'arm64-v8a', archive)

    def test_signed_index_and_verified_package_make_deterministic_flat_archive(self):
        self.assertIsNotNone(cache, 'offline APK cache builder is required')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'index.gz').write_bytes(b'signed index')
            lock = {'indexes': [{'file': 'index.gz', 'cacheName': 'APKINDEX.1234.tar.gz',
                                 'sha256': sha(b'signed index')}],
                    'packages': [{'url': 'https://dl-cdn.alpinelinux.org/p.apk',
                                  'cacheName': 'p-1.12345678.apk', 'sha256': sha(b'package')}]}
            def opener(_):
                return io.BytesIO(b'package')
            out = root / 'cache.tgz'
            cache.build_cache(lock, root, root / 'downloads', out, opener=opener)
            first = out.read_bytes()
            with tarfile.open(out, 'r:gz') as archive:
                self.assertEqual(['APKINDEX.1234.tar.gz', 'p-1.12345678.apk'], archive.getnames())
                self.assertEqual(b'package', archive.extractfile('p-1.12345678.apk').read())
            cache.build_cache(lock, root, root / 'downloads', out, opener=opener)
            self.assertEqual(first, out.read_bytes())

    def test_bad_hash_does_not_replace_existing_archive(self):
        self.assertIsNotNone(cache, 'offline APK cache builder is required')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            out = root / 'cache.tgz'
            out.write_bytes(b'previous')
            lock = {'indexes': [], 'packages': [{'url': 'https://dl-cdn.alpinelinux.org/p.apk',
                    'cacheName': 'p-1.12345678.apk', 'sha256': sha(b'expected')}]}
            with self.assertRaises(ValueError):
                cache.build_cache(lock, root, root / 'downloads', out, opener=lambda _: io.BytesIO(b'wrong'))
            self.assertEqual(b'previous', out.read_bytes())

    def test_rejects_paths_and_duplicate_names(self):
        self.assertIsNotNone(cache, 'offline APK cache builder is required')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for names in (['../escape.apk'], ['same.apk', 'same.apk']):
                lock = {'indexes': [], 'packages': [{'url': 'https://dl-cdn.alpinelinux.org/p.apk',
                        'cacheName': name, 'sha256': sha(b'package')} for name in names]}
                with self.subTest(names=names), self.assertRaises(ValueError):
                    cache.build_cache(lock, root, root / 'downloads', root / 'cache.tgz', opener=lambda _: io.BytesIO(b'package'))

if __name__ == '__main__':
    unittest.main()
