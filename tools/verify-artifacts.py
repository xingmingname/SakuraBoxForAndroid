"""Local APK/AAR checks; never downloads or modifies existing artifacts."""
import argparse
import hashlib
import io
import json
import os
import shutil
import re
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

MACHINES = {"armeabi-v7a": 40, "arm64-v8a": 183, "x86": 3, "x86_64": 62}
CHUNK_SIZE = 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    with path.open("rb") as source:
        return stream_summary(source)[0]


def stream_summary(source, markers=()):
    checksum = hashlib.sha256()
    found = set()
    overlap = max((len(marker) - 1 for marker in markers), default=0)
    tail = b""
    for block in iter(lambda: source.read(CHUNK_SIZE), b""):
        checksum.update(block)
        window = tail + block
        found.update(marker for marker in markers if marker in window)
        tail = window[-overlap:] if overlap else b""
    return checksum.hexdigest(), found


def streams_identical(first, second):
    while True:
        a, b = first.read(CHUNK_SIZE), second.read(CHUNK_SIZE)
        if a != b:
            return False
        if not a:
            return True


def copy_member(archive, name, target):
    with archive.open(name) as source, target.open("xb") as destination:
        shutil.copyfileobj(source, destination, CHUNK_SIZE)


def elf_info(data):
    return elf_info_stream(io.BytesIO(data))


def elf_info_stream(source):
    header = source.read(64)
    assert header[:4] == b"\x7fELF", "Not ELF"
    assert len(header) >= 52 and header[4] in (1, 2), "Invalid ELF header"
    is64 = header[4] == 2
    assert header[5] == 1, "Unexpected ELF byte order"
    offset = struct.unpack_from("<Q" if is64 else "<I", header, 32 if is64 else 28)[0]
    size, count = struct.unpack_from("<HH", header, 54 if is64 else 42)
    format_string = "<IIQQQQQQ" if is64 else "<IIIIIIII"
    assert size >= struct.calcsize(format_string), "Invalid ELF program header size"
    alignments = []
    for index in range(count):
        source.seek(offset + index * size)
        values = struct.unpack(format_string, source.read(struct.calcsize(format_string)))
        if values[0] == 1:
            alignment = values[-1]
            file_offset, address = (values[2], values[3]) if is64 else (values[1], values[2])
            assert alignment >= 16384 and file_offset % alignment == address % alignment, "ELF is not 16 KiB compatible"
            alignments.append(alignment)
    assert alignments, "No ELF LOAD segments"
    return {"machine": struct.unpack_from("<H", header, 18)[0], "bits": 64 if is64 else 32, "load_alignments": alignments}


def command(arguments):
    result = subprocess.run([str(arg) for arg in arguments], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed ({result.returncode}): {result.stdout}\n{result.stderr}")
    return result.stdout + result.stderr


def inspect_core(archive, aar, abi, args, entry):
    apk_member, aar_member = f"lib/{abi}/libgojni.so", f"jni/{abi}/libgojni.so"
    marker = b"1.14.3-sakura"
    with archive.open(apk_member) as source:
        entry["apk_core_sha256"], found = stream_summary(source, (marker,))
    with aar.open(aar_member) as source:
        entry["aar_core_sha256"] = stream_summary(source)[0]
    assert marker in found, "Compiled sing-box version marker missing"
    with archive.open(apk_member) as first, aar.open(aar_member) as second:
        identical = streams_identical(first, second)
    readelf = args.sdk / "ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
    with tempfile.TemporaryDirectory(prefix="sakurabox-verify-", dir=args.temporary_root) as temporary:
        work = Path(temporary)
        so_path = work / "libgojni.so"
        copy_member(archive, apk_member, so_path)
        if identical:
            entry["aar_matches_apk_without_transformation"] = True
        else:
            # Check the exact AGP strip transformation before accepting a different binary.
            aar_so, stripped_so = work / "aar.so", work / "stripped.so"
            copy_member(aar, aar_member, aar_so)
            command([readelf.parent / "llvm-strip.exe", "--strip-unneeded", "-o", stripped_so, aar_so])
            with so_path.open("rb") as first, stripped_so.open("rb") as second:
                assert streams_identical(first, second), "APK core differs from rebuilt AAR after AGP symbol stripping"
            entry["aar_matches_apk_after_agp_strip"] = True
        build_info = command([args.go_root / "bin/go.exe", "version", "-m", so_path])
        assert "github.com/sagernet/sing-box\tv1.14.3" in build_info, "sing-box module version is not v1.14.3"
        symbols = command([readelf, "--dyn-syms", so_path])
        required = ["Java_go_Seq_init", "Java_libcore_Libcore_migrateConfig", "Java_libcore_Libcore_exportPortableConfig", "Java_libcore_Libcore_newSingBoxInstance"]
        assert all(symbol in symbols for symbol in required), "JNI entry points missing"
        entry["jni_export_symbols"] = required
        return build_info.replace(str(so_path), "<temporary-libgojni.so>")


def main():
    if not __debug__:
        raise RuntimeError("Artifact verification must run without Python -O")
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-directory", required=True, type=Path)
    parser.add_argument("--aar", required=True, type=Path)
    parser.add_argument("--expected-signer", required=True, help="Explicit trusted signing certificate SHA-256")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--temporary-root", type=Path, help="Existing local scratch directory; only newly created temporary files are removed")
    parser.add_argument("--jni-runtime-status", default="Not executed; static JNI checks only")
    parser.add_argument("--go-root", type=Path, default=Path(__file__).resolve().parent.parent / ".local-builds/toolchains/go1.26.9")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "missing-sdk"))))
    parser.add_argument("--java", type=Path, default=Path(os.environ["JAVA_HOME"]) / "bin/java.exe" if os.environ.get("JAVA_HOME") else Path(shutil.which("java") or "missing-java"))
    args = parser.parse_args()
    metadata = dict(line.split("=", 1) for line in (Path(__file__).resolve().parent.parent / "nb4a.properties")
                    .read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith("#"))
    expected_id = metadata["PACKAGE_NAME"] + ".debug"
    expected_version = metadata["VERSION_NAME"]
    args.output.mkdir(parents=True, exist_ok=False)
    build_tools = args.sdk / "build-tools/35.0.1"
    apks = sorted(args.apk_directory.glob("*.apk"))
    assert len(apks) == 4, f"Expected four ABI APKs, got {len(apks)}"
    result = {"aar": str(args.aar.resolve()), "aar_sha256": file_digest(args.aar), "apks": [], "jni_runtime_test": args.jni_runtime_status}
    with zipfile.ZipFile(args.aar) as aar:
        for apk in apks:
            abi = next((abi for abi in MACHINES if f"-{abi}-" in apk.name), None)
            assert abi, "Unknown APK ABI"
            assert apk.name == f"SakuraBox-{expected_version}-{abi}-debug.apk", "Unexpected APK filename"
            entry = {"path": str(apk.resolve()), "abi": abi, "bytes": apk.stat().st_size, "sha256": file_digest(apk)}
            with zipfile.ZipFile(apk) as archive:
                libraries = [name for name in archive.namelist() if name.endswith(".so")]
                assert f"lib/{abi}/libgojni.so" in libraries, "Missing libgojni.so"
                entry["libraries"] = {}
                for name in libraries:
                    assert name.split("/")[1] == abi, "APK contains an unexpected ABI"
                    with archive.open(name) as source:
                        info = elf_info_stream(source)
                    with archive.open(name) as source:
                        stream_summary(source)  # Consume through EOF, retaining ZIP CRC validation.
                    assert info["machine"] == MACHINES[abi], "ELF machine and ABI disagree"
                    entry["libraries"][name] = info
                build_info = inspect_core(archive, aar, abi, args, entry)
                dex_markers = {b"migrateConfig", b"dnsServers"}
                found = set()
                for name in archive.namelist():
                    if name.endswith(".dex"):
                        with archive.open(name) as source:
                            found.update(stream_summary(source, dex_markers)[1])
                assert found == dex_markers, "Java/Go bridge methods absent in DEX"
                (args.output / f"{abi}-go-version.txt").write_text(build_info, encoding="utf-8")
            signature = command([args.java, "-jar", build_tools / "lib/apksigner.jar", "verify", "--verbose", "--print-certs", apk])
            assert "Verified using v1 scheme (JAR signing): true" in signature and "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
            signer = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", signature)
            assert signer, "No signer fingerprint"
            entry["signer_sha256"] = signer.group(1)
            assert signer.group(1) == args.expected_signer.lower(), "Signer differs from the explicitly expected certificate"
            command([build_tools / "zipalign.exe", "-c", "-P", "16", "4", apk])
            badging = command([build_tools / "aapt2.exe", "dump", "badging", apk])
            assert f"name='{expected_id}'" in badging and f"versionName='{expected_version}'" in badging
            (args.output / f"{abi}-signature.txt").write_text(signature, encoding="utf-8")
            (args.output / f"{abi}-badging.txt").write_text(badging, encoding="utf-8")
            result["apks"].append(entry)
    (args.output / "verification.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
