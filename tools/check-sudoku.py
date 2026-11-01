"""Check the experimental Sudoku port using only workspace-local outputs/caches."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TAGS = "with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"
CORE_TESTS = (
    "^TestSudoku|^Test(VersionBoxReportsSakuraCoreAndCompilerMetadata|"
    "LogRedactionAtOutputBoundary|DNSStartReproducesThenFixesEmptyDirectDetour|"
    "DNSDetourMigrationPreservesConfiguredAndProxyDetours|"
    "DNSDetourMigrationUsesActualDirectOptions|CoreStartsProtocolAndDNSMatrix|"
    "SFAExportDefaultsOnlyTouchMissingTunAutoRoute)$"
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_provenance():
    package = ROOT / "libcore/sudoku"
    manifest = json.loads((package / "UPSTREAM.json").read_text(encoding="utf-8"))
    for entry in manifest["files"]:
        expected = entry.get("adapted_sha256")
        if expected and sha256(package / entry["path"]) != expected:
            raise RuntimeError(f"Pinned Sudoku source changed: {entry['path']}")
    return manifest["commit"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go-root", type=Path, required=True)
    parser.add_argument("--run-directory", type=Path, required=True)
    parser.add_argument("--online", action="store_true", help="Allow public Go dependency downloads; telemetry remains disabled")
    args = parser.parse_args()
    run = args.run_directory.resolve()
    if not run.is_relative_to(ROOT / ".local-builds"):
        parser.error("Use a new run directory inside this workspace's .local-builds")
    go = args.go_root.resolve() / "bin" / ("go.exe" if os.name == "nt" else "go")
    if not go.is_file():
        parser.error("Go executable not found")
    commit = verify_provenance()
    run.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    cache = ROOT / ".local-builds/sudoku-check-cache"
    env.update({
        "GOROOT": str(args.go_root.resolve()), "GOTOOLCHAIN": "local", "GOENV": "off",
        "GOPATH": str(cache / "gopath"), "GOMODCACHE": str(ROOT / ".local-builds/sudoku-module-cache"),
        "GOCACHE": str(ROOT / ".local-builds/sudoku-go-cache"), "CGO_ENABLED": "0",
        "GOPROXY": "https://proxy.golang.org" if args.online else "off",
        "GOSUMDB": "sum.golang.org" if args.online else "off", "GOFLAGS": "-mod=readonly",
        "APPDATA": str(cache / "config"), "LOCALAPPDATA": str(cache / "local"),
        "XDG_CONFIG_HOME": str(cache / "config"), "XDG_CACHE_HOME": str(cache / "local"),
        "TEMP": str(run / "tmp"), "TMP": str(run / "tmp"), "TMPDIR": str(run / "tmp"),
        "NEKO_CONFIG_FIXTURES": "", "SB_BUNDLED_UI_ZIP": str(ROOT / "app/src/main/assets/yacd.zip"),
    })
    for path in (run / "tmp", cache / "local", cache / "config/go/telemetry"):
        path.mkdir(parents=True, exist_ok=True)
    (cache / "config/go/telemetry/mode").write_text("off", encoding="utf-8")
    before = {p: sha256(p) for p in (ROOT / "libcore/go.mod", ROOT / "libcore/go.sum")}
    summary = {"upstream_commit": commit, "checks": {}}

    def check(name, cwd, arguments):
        print(f"Running {name}...", flush=True)
        with (run / f"{name}.jsonl").open("wb") as output, (run / f"{name}.stderr.txt").open("wb") as errors:
            result = subprocess.run([str(go), *arguments], cwd=cwd, env=env, stdout=output, stderr=errors)
        events = [json.loads(line) for line in (run / f"{name}.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
        summary["checks"][name] = {
            "exit": result.returncode,
            "passed_tests": sum(e.get("Action") == "pass" and "Test" in e for e in events),
            "skipped_tests": [e["Test"] for e in events if e.get("Action") == "skip" and "Test" in e],
            "failed_tests": [e["Test"] for e in events if e.get("Action") == "fail" and "Test" in e],
        }
        return result.returncode

    transport_exit = check("transport", ROOT / "libcore", ["test", "-count=1", "-timeout=180s", "-json", "./sudoku/..."])
    subprocess.run([sys.executable, str(ROOT / "tools/prepare-host-tests.py"), "--output", str(run / "host")], cwd=ROOT, env=env, check=True)
    core_exit = check("core", run / "host", ["test", "-tags", TAGS, "-count=1", "-timeout=180s", "-run", CORE_TESTS, "-json", "."])
    if any(sha256(path) != digest for path, digest in before.items()):
        raise RuntimeError("Read-only validation changed the module files")
    verify_provenance()
    (run / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return bool(transport_exit or core_exit)


if __name__ == "__main__":
    sys.exit(main())
