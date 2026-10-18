"""Offline SB-SEC-001 manifest/resource regression checks. Does not launch Android."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
NS = "io.nekohasekai.sagernet"


def check(manifest):
    tree = ET.parse(manifest)
    app = tree.getroot().find("application")
    components = {}
    for element in app:
        name = element.get(ANDROID + "name", "")
        if name.startswith("."):
            name = NS + name
        components[name] = element
    executor = components[NS + ".ui.TrustedShortcutActivity"]
    assert executor.get(ANDROID + "exported") == "false"
    assert executor.find("intent-filter") is None
    assert executor.get(ANDROID + "process") == ":bg"
    for cls in [".QuickToggleShortcut", ".ui.QuickEnableShortcut", ".ui.QuickDisableShortcut"]:
        assert components[NS + cls].get(ANDROID + "exported") == "true"
    assert components[NS + ".bg.VpnService"].get(ANDROID + "permission") == "android.permission.BIND_VPN_SERVICE"
    assert components[NS + ".bg.TileService"].get(ANDROID + "permission") == "android.permission.BIND_QUICK_SETTINGS_TILE"
    assert components[NS + ".bg.ProxyService"].get(ANDROID + "exported") == "false"
    for name in [".ui.SwitchActivity", ".ui.ScannerActivity", ".ui.VpnRequestActivity"]:
        node = components[NS + name]
        assert node.get(ANDROID + "exported", "false") == "false"
        assert node.find("intent-filter") is None
    checks = ["non-exported executor", "legacy compatibility gateways", "VPN/Tile permission boundaries", "internal activities"]
    for variant, package in [("main", "app.sakurabox.client"), ("debug", "app.sakurabox.client.debug")]:
        shortcuts = ET.parse(ROOT / f"app/src/{variant}/res/xml/shortcuts.xml").getroot()
        assert len(shortcuts) == 4
        for shortcut in shortcuts:
            sid = shortcut.get(ANDROID + "shortcutId")
            intent = shortcut.find("intent")
            assert intent.get(ANDROID + "targetPackage") == package
            if sid == "scan":
                assert intent.get(ANDROID + "targetClass") == NS + ".ui.ScannerActivity"
                assert intent.get(ANDROID + "action") == "android.intent.action.MAIN"
                assert shortcut.get(ANDROID + "icon") == "@drawable/ic_qu_camera_launcher"
            else:
                assert sid in {"toggle", "enable", "disable"}
                assert intent.get(ANDROID + "targetClass") == NS + ".ui.TrustedShortcutActivity"
                assert intent.get(ANDROID + "action") == NS + ".shortcut." + sid.upper()
                assert shortcut.get(ANDROID + "icon") == "@drawable/ic_qu_shadowsocks_launcher"
        checks.append(variant + " shortcuts: actions, IDs, icons, target package")
    java = ROOT / "app/src/main/java/io/nekohasekai/sagernet"
    for path in [java / "QuickToggleShortcut.kt", java / "ui/QuickEnableShortcut.kt", java / "ui/QuickDisableShortcut.kt", java / "ui/ShortcutConfirmationActivity.kt"]:
        source = path.read_text(encoding="utf-8")
        assert "ShortcutConfirmationActivity" in source
        for forbidden in ["SagerConnection(", "SagerNet.startService", "SagerNet.stopService", "SagerNet.reloadService", "DataStore.selectedProxy"]:
            assert forbidden not in source, (path.name, forbidden)
    checks.append("public gateways have no direct VPN-control or selectedProxy access")
    boot = (java / "BootReceiver.kt").read_text(encoding="utf-8")
    assert boot.index("if (!ProtectedVpnBootActions.accepts(intent.action, Build.VERSION.SDK_INT)) return") < boot.index("runOnDefaultDispatcher {")
    assert {n.get(ANDROID + "name") for n in components[NS + ".BootReceiver"].findall("intent-filter/action")} == {
        "android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"}
    checks.append("boot receiver rejects non-system action before scheduler/VPN side effects")
    return {"result": "PASS", "manifest": str(manifest), "checks": checks,
            "limitation": "Static checks only; not Android cross-UID or Launcher dynamic proof."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=ROOT / "app/src/main/AndroidManifest.xml")
    args = parser.parse_args()
    print(json.dumps(check(args.manifest), ensure_ascii=True, indent=2))
