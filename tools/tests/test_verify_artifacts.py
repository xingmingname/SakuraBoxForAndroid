import hashlib
import importlib.util
import io
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

SPEC = importlib.util.spec_from_file_location('verify_artifacts', Path(__file__).resolve().parents[1] / 'verify-artifacts.py')
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


def elf(is64=True, alignment=16384, machine=183):
    header = bytearray(64)
    header[:6] = b'\x7fELF' + bytes([2 if is64 else 1, 1])
    struct.pack_into('<H', header, 18, machine)
    struct.pack_into('<Q' if is64 else '<I', header, 32 if is64 else 28, 64)
    struct.pack_into('<HH', header, 54 if is64 else 42, 56 if is64 else 32, 1)
    segment = struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 128, 128, alignment) if is64 else struct.pack('<IIIIIIII', 1, 0, 0, 0, 128, 128, 5, alignment)
    return bytes(header) + segment + b'1.14.3-sakura'


def archive(member, content):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as target:
        target.writestr(member, content)
    return zipfile.ZipFile(io.BytesIO(output.getvalue()))


class VerificationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='sakurabox-unit-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.args = SimpleNamespace(sdk=self.root, go_root=self.root, temporary_root=self.root)

    def invoke(self, apk_core, aar_core, strip_result=None, build_info=None, symbols=None, failure=False):
        paths = []

        def command(arguments):
            paths.append(Path(arguments[-1]))
            self.assertTrue(paths[-1].is_file())
            if failure:
                raise RuntimeError('Deliberate tool failure')
            if Path(arguments[0]).name == 'llvm-strip.exe':
                Path(arguments[-2]).write_bytes(strip_result)
                return ''
            if Path(arguments[0]).name == 'go.exe':
                return build_info if build_info is not None else 'dep\tgithub.com/sagernet/sing-box\tv1.14.3\n'
            return symbols if symbols is not None else 'Java_go_Seq_init Java_libcore_Libcore_migrateConfig Java_libcore_Libcore_exportPortableConfig Java_libcore_Libcore_newSingBoxInstance'

        entry = {}
        try:
            with archive('lib/arm64-v8a/libgojni.so', apk_core) as apk, archive('jni/arm64-v8a/libgojni.so', aar_core) as aar, mock.patch.object(verify, 'command', command):
                verify.inspect_core(apk, aar, 'arm64-v8a', self.args, entry)
        finally:
            self.assertFalse(list(self.root.glob('sakurabox-verify-*')))
            self.assertTrue(all(not path.exists() for path in paths))
        return entry

    def test_stream_hash_and_marker_across_chunk_boundary(self):
        content = b'x' * (verify.CHUNK_SIZE - 2) + b'1.14.3-sakura'
        checksum, found = verify.stream_summary(io.BytesIO(content), (b'1.14.3-sakura', b'missing'))
        self.assertEqual(checksum, hashlib.sha256(content).hexdigest())
        self.assertEqual(found, {b'1.14.3-sakura'})

    def test_stream_reads_are_bounded(self):
        class Bounded(io.BytesIO):
            def read(self, size=-1):
                self.assert_size(size)
                return super().read(size)
        stream = Bounded(b'data')
        stream.assert_size = lambda size: self.assertTrue(0 < size <= verify.CHUNK_SIZE)
        verify.stream_summary(stream)

    def test_byte_comparison_rejects_length_and_content_changes(self):
        for other in [b'ab', b'abcd', b'xbc']:
            with self.subTest(other=other):
                self.assertFalse(verify.streams_identical(io.BytesIO(b'abc'), io.BytesIO(other)))
        self.assertTrue(verify.streams_identical(io.BytesIO(b'abc'), io.BytesIO(b'abc')))

    def test_elf_all_four_machines(self):
        for abi, machine in verify.MACHINES.items():
            with self.subTest(abi=abi):
                info = verify.elf_info(elf(abi in {'arm64-v8a', 'x86_64'}, machine=machine))
                self.assertEqual(info['machine'], machine)
                self.assertEqual(info['load_alignments'], [16384])

    def test_elf_rejects_bad_alignment(self):
        with self.assertRaisesRegex(AssertionError, '16 KiB'):
            verify.elf_info(elf(alignment=4096))

    def test_elf_rejects_bad_header(self):
        for content in [b'', b'not ELF', b'\x7fELF\x03\x01' + bytes(58)]:
            with self.subTest(content=content), self.assertRaises(AssertionError):
                verify.elf_info(content)

    def test_zip_crc_is_checked(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as target:
            target.writestr('test', b'crc-sentinel')
        with zipfile.ZipFile(io.BytesIO(raw.getvalue().replace(b'crc-sentinel', b'crc-sentinex'))) as target:
            with target.open('test') as stream, self.assertRaises(zipfile.BadZipFile):
                verify.stream_summary(stream)

    def test_copy_does_not_overwrite_existing_file(self):
        target = self.root / 'existing'; target.write_bytes(b'keep')
        with archive('test', b'new') as source, self.assertRaises(FileExistsError):
            verify.copy_member(source, 'test', target)
        self.assertEqual(target.read_bytes(), b'keep')

    def test_identical_core_and_temporary_cleanup(self):
        entry = self.invoke(elf(), elf())
        self.assertTrue(entry['aar_matches_apk_without_transformation'])
        self.assertEqual(entry['apk_core_sha256'], hashlib.sha256(elf()).hexdigest())

    def test_exact_strip_transformation(self):
        entry = self.invoke(elf(), elf() + b'debug-symbols', strip_result=elf())
        self.assertTrue(entry['aar_matches_apk_after_agp_strip'])

    def test_wrong_strip_output_fails_and_cleans(self):
        with self.assertRaisesRegex(AssertionError, 'after AGP'):
            self.invoke(elf(), elf() + b'debug-symbols', strip_result=b'wrong')

    def test_tool_failure_propagates_and_cleans(self):
        with self.assertRaisesRegex(RuntimeError, 'Deliberate'):
            self.invoke(elf(), elf(), failure=True)

    def test_wrong_core_module_fails_and_cleans(self):
        with self.assertRaisesRegex(AssertionError, 'module version'):
            self.invoke(elf(), elf(), build_info='dep\tgithub.com/sagernet/sing-box\tv9.9.9')

    def test_missing_jni_fails_and_cleans(self):
        with self.assertRaisesRegex(AssertionError, 'JNI entry'):
            self.invoke(elf(), elf(), symbols='Java_go_Seq_init')

    def test_missing_version_marker_fails(self):
        with self.assertRaisesRegex(AssertionError, 'version marker'):
            self.invoke(b'no-version', b'no-version')

    def test_file_hash_is_streamed(self):
        target = self.root / 'artifact'; target.write_bytes(b'content')
        self.assertEqual(verify.file_digest(target), hashlib.sha256(b'content').hexdigest())


if __name__ == '__main__':
    unittest.main()
