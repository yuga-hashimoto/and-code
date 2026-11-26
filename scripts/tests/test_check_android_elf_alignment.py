import importlib.util
import struct
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "check_android_elf_alignment", Path(__file__).resolve().parents[1] / "check_android_elf_alignment.py"
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def elf64(alignment):
    data = bytearray(120)
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 112, alignment)
    return bytes(data)


def elf32(alignment):
    data = bytearray(84)
    data[:16] = b"\x7fELF\x01\x01\x01" + bytes(9)
    struct.pack_into("<I", data, 28, 52)
    struct.pack_into("<HH", data, 42, 32, 1)
    struct.pack_into("<I", data, 52, 1)
    struct.pack_into("<I", data, 80, alignment)
    return bytes(data)


class AlignmentTest(unittest.TestCase):
    def test_accepts_16k_load_segment(self):
        self.assertEqual(checker.load_alignments(elf64(16384)), [16384])

    def test_exposes_4k_load_segment(self):
        self.assertEqual(checker.load_alignments(elf64(4096)), [4096])

    def test_checks_32_bit_loader(self):
        self.assertEqual(checker.load_alignments(elf32(4096)), [4096])

    def test_rejects_non_elf_and_truncated_program_table(self):
        with self.assertRaises(ValueError):
            checker.load_alignments(b"not ELF")
        with self.assertRaises(ValueError):
            checker.load_alignments(elf64(16384)[:90])


if __name__ == "__main__":
    unittest.main()
