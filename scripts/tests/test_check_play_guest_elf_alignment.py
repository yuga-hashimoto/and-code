import importlib.util
import io
from pathlib import Path
import struct
import sys
import tarfile
import unittest
import gzip

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('guest_elf', ROOT / 'scripts/check_play_guest_elf_alignment.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def elf64(alignment):
    data = bytearray(120)
    data[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<I', data, 64, 1)
    struct.pack_into('<Q', data, 112, alignment)
    return bytes(data)


def part(entries):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode='w') as archive:
        for name, payload in entries.items():
            item = tarfile.TarInfo(name)
            item.size = len(payload)
            archive.addfile(item, io.BytesIO(payload))
    return gzip.compress(out.getvalue(), mtime=0)


class GuestElfAuditTest(unittest.TestCase):
    def test_misaligned_offset_and_virtual_address_is_rejected(self):
        data = bytearray(elf64(16384))
        struct.pack_into('<Q', data, 72, 4096)
        report = checker.Report()
        report.record('base', 'bad', bytes(data))
        self.assertEqual(1, report.counts['base']['unaligned'])

    def test_scans_concatenated_alpine_package_streams(self):
        apk = part({'.SIGN.RSA.test': b'sig'}) + part({'.PKGINFO': b'info'}) + part({
            'usr/bin/aligned': elf64(16384), 'usr/bin/legacy': elf64(4096), 'etc/text': b'not ELF',
        })
        report = checker.Report()
        checker.scan_apk(apk, 'example.apk', report)
        self.assertEqual(1, report.counts['packages']['aligned'])
        self.assertEqual(1, report.counts['packages']['unaligned'])
        self.assertTrue(any('legacy' in item for item in report.examples))

    def test_rejects_corrupt_package_stream(self):
        with self.assertRaises(ValueError):
            checker.scan_apk(b'not a gzip stream', 'bad.apk', checker.Report())


if __name__ == '__main__':
    unittest.main()
