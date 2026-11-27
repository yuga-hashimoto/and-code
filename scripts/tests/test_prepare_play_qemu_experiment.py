import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('experiment', ROOT / 'scripts/prepare_play_qemu_experiment.py')
experiment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(experiment)


class QemuExperimentExtractionTest(unittest.TestCase):
    def test_rejects_mismatched_package_before_creating_payload(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'payload'
            lock = {'architecture': 'x86_64', 'packages': [
                {'name': 'sample', 'filename': 'pool/main/s/sample/sample.deb', 'sha256': '0' * 64},
            ]}
            with patch.object(experiment, 'build_opener') as builder:
                builder.return_value.open.return_value = io.BytesIO(b'untrusted data')
                with self.assertRaises(ValueError):
                    experiment.prepare(lock, output)
            self.assertFalse(output.exists())

    def test_extracts_only_runtime_files_and_flattens_library_links(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            data = io.BytesIO()
            with tarfile.open(fileobj=data, mode='w') as archive:
                for name, contents in [('data/data/com.termux/files/usr/bin/qemu-x86_64', b'executable'),
                                       ('data/data/com.termux/files/usr/lib/libdemo.so.1.2', b'library'),
                                       ('etc/unwanted', b'ignore')]:
                    entry = tarfile.TarInfo(name)
                    entry.size = len(contents)
                    archive.addfile(entry, io.BytesIO(contents))
                entry = tarfile.TarInfo('data/data/com.termux/files/usr/lib/libdemo.so.1')
                entry.type = tarfile.SYMTYPE
                entry.linkname = 'libdemo.so.1.2'
                archive.addfile(entry)
            data.seek(0)
            links = {}
            with tarfile.open(fileobj=data) as archive:
                experiment.extract_runtime(archive, base, links)
            experiment.materialize_links(base, links)
            self.assertEqual(b'executable', (base / 'qemu-x86_64').read_bytes())
            self.assertEqual(b'library', (base / 'lib/libdemo.so.1').read_bytes())
            self.assertFalse((base / 'etc/unwanted').exists())

    def test_rejects_library_link_that_escapes_runtime_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                experiment.materialize_links(Path(tmp), {'libbad.so': '../../outside'})


if __name__ == '__main__':
    unittest.main()
