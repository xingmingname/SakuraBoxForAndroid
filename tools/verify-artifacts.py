"""Local APK/AAR checks; never downloads or modifies existing artifacts."""
import argparse
import hashlib
import json
import os
import shutil
import re
import struct
import subprocess
import zipfile
from pathlib import Path

MACHINES = {"armeabi-v7a": 40, "arm64-v8a": 183, "x86": 3, "x86_64": 62}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def elf_info(data):
    assert data[:4] == b"\x7fELF", "Not ELF"
    is64 = data[4] == 2
    assert data[5] == 1, "Unexpected ELF byte order"
    offset = struct.unpack_from("<Q" if is64 else "<I", data, 32 if is64 else 28)[0]
    size, count = struct.unpack_from("<HH", data, 54 if is64 else 42)
    alignments = []
    for index in range(count):
        values = struct.unpack_from("<IIQQQQQQ" if is64 else "<IIIIIIII", data, offset + index * size)
        if values[0] == 1:
            alignment = values[-1]
            file_offset, address = (values[2], values[3]) if is64 else (values[1], values[2])
            assert alignment >= 16384 and file_offset % alignment == address % alignment, "ELF is not 16 KiB compatible"
            alignments.append(alignment)
    assert alignments, "No ELF LOAD segments"
    return {"machine": struct.unpack_from("<H", data, 18)[0], "bits": 64 if is64 else 32, "load_alignments": alignments}


def command(arguments):
    result = subprocess.run([str(arg) for arg in arguments], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed ({result.returncode}): {result.stdout}\n{result.stderr}")
    return result.stdout + result.stderr


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-directory", required=True, type=Path)
    parser.add_argument("--aar", required=True, type=Path)
    parser.add_argument("--expected-signer", required=True, help="Explicit trusted signing certificate SHA-256")
    parser.add_argument("--output", required=True, type=Path)
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
    readelf = args.sdk / "ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
    apks = sorted(args.apk_directory.glob("*.apk"))
    assert len(apks) == 4, f"Expected four ABI APKs, got {len(apks)}"
    result = {"aar": str(args.aar.resolve()), "aar_sha256": digest(args.aar.read_bytes()), "apks": [], "jni_runtime_test": args.jni_runtime_status}
    with zipfile.ZipFile(args.aar) as aar:
        for apk in apks:
            abi = next((abi for abi in MACHINES if f"-{abi}-" in apk.name), None)
            assert abi, "Unknown APK ABI"
            assert apk.name == f"SakuraBox-{expected_version}-{abi}-debug.apk", "Unexpected APK filename"
            entry = {"path": str(apk.resolve()), "abi": abi, "bytes": apk.stat().st_size, "sha256": digest(apk.read_bytes())}
            with zipfile.ZipFile(apk) as archive:
                libraries = [name for name in archive.namelist() if name.endswith(".so")]
                assert f"lib/{abi}/libgojni.so" in libraries, "Missing libgojni.so"
                entry["libraries"] = {}
                for name in libraries:
                    assert name.split("/")[1] == abi, "APK contains an unexpected ABI"
                    data = archive.read(name)
                    info = elf_info(data)
                    assert info["machine"] == MACHINES[abi], "ELF machine and ABI disagree"
                    entry["libraries"][name] = info
                core = archive.read(f"lib/{abi}/libgojni.so")
                aar_core = aar.read(f"jni/{abi}/libgojni.so")
                entry["aar_core_sha256"] = digest(aar_core)
                entry["apk_core_sha256"] = digest(core)
                if core != aar_core:
                    # AGP strips native debug symbols when packaging. Verify
                    # the exact expected transformation, not just a version tag.
                    aar_so = args.output / f"{abi}-aar-libgojni.so"
                    stripped_so = args.output / f"{abi}-stripped-libgojni.so"
                    aar_so.write_bytes(aar_core)
                    command([readelf.parent / "llvm-strip.exe", "--strip-unneeded", "-o", stripped_so, aar_so])
                    assert core == stripped_so.read_bytes(), "APK core differs from rebuilt AAR after AGP symbol stripping"
                    entry["aar_matches_apk_after_agp_strip"] = True
                else:
                    entry["aar_matches_apk_without_transformation"] = True
                assert b"1.14.3-sakura" in core, "Compiled sing-box version marker missing"
                so_path = args.output / f"{abi}-libgojni.so"
                so_path.write_bytes(core)
                build_info = command([args.go_root / "bin/go.exe", "version", "-m", so_path])
                assert "github.com/sagernet/sing-box\tv1.14.3" in build_info, "sing-box module version is not v1.14.3"
                symbols = command([readelf, "--dyn-syms", so_path])
                required = ["Java_go_Seq_init", "Java_libcore_Libcore_migrateConfig", "Java_libcore_Libcore_exportPortableConfig", "Java_libcore_Libcore_newSingBoxInstance"]
                assert all(symbol in symbols for symbol in required), "JNI entry points missing"
                dex_data = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
                assert b"migrateConfig" in dex_data and b"dnsServers" in dex_data, "Java/Go bridge methods absent in DEX"
                entry["jni_export_symbols"] = required
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
