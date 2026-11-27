import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('sources', ROOT / 'scripts/prepare_play_copyleft_sources.py')
sources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sources)


class SourceBundleTest(unittest.TestCase):
    def test_lock_covers_every_copyleft_package_revision(self):
        source_lock = json.loads((ROOT / 'runtime_tools/play-copyleft-sources.lock.json').read_text())
        apk_lock = json.loads((ROOT / 'runtime_tools/play-apk-cache.lock.json').read_text())
        sources.verify_groups(source_lock, apk_lock)
        source_lock['groups'].pop()
        with self.assertRaises(ValueError):
            sources.verify_groups(source_lock, apk_lock)

    def test_download_is_verified_before_replacing_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'source'
            target.write_bytes(b'old')
            url = 'https://distfiles.alpinelinux.org/source'
            digest = hashlib.sha512(b'new').hexdigest()
            with self.assertRaises(ValueError):
                sources.materialize(url, target, 'sha512', digest, lambda *_args, **_kwargs: io.BytesIO(b'bad'))
            self.assertEqual(b'old', target.read_bytes())
            sources.materialize(url, target, 'sha512', digest, lambda *_args, **_kwargs: io.BytesIO(b'new'))
            self.assertEqual(b'new', target.read_bytes())

    def test_source_checksums_must_match_pinned_recipe(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recipe = root / 'APKBUILD'
            recipe.write_text('sha512sums="\n' + 'a' * 128 + '  archive.tar.gz\n"\n')
            sources.verify_source_list(recipe, [{'name': 'archive.tar.gz', 'sha512': 'a' * 128}])
            with self.assertRaises(ValueError):
                sources.verify_source_list(recipe, [{'name': 'archive.tar.gz', 'sha512': 'b' * 128}])
            with self.assertRaises(ValueError):
                sources.verify_source_list(recipe, [])

    def test_url_and_relative_paths_are_restricted(self):
        for url in ('http://distfiles.alpinelinux.org/a', 'https://evil.example/a', 'https://user@raw.githubusercontent.com/a'):
            with self.assertRaises(ValueError):
                sources.validate_url(url)
        for name in ('../escape', '/absolute', 'sub/../../escape'):
            with self.assertRaises(ValueError):
                sources.safe_path(name)
        self.assertEqual('dir/file.patch', str(sources.safe_path('dir/file.patch')))

    def test_recipe_git_blob_identity_is_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'recipe'
            path.write_bytes(b'content')
            sha = hashlib.sha1(b'blob 7\0content').hexdigest()
            sources.verify_git_blob(path, sha)
            with self.assertRaises(ValueError):
                sources.verify_git_blob(path, '0' * 40)

    def test_file_hash_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source'
            path.write_bytes(b'content')
            sources.verify_file(path, 'sha256', hashlib.sha256(b'content').hexdigest())
            with self.assertRaises(ValueError):
                sources.verify_file(path, 'sha256', '0' * 64)


if __name__ == '__main__':
    unittest.main()
