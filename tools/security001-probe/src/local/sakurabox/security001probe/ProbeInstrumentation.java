package local.sakurabox.security001probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

/** Uses only framework APIs. Must be packaged and signed independently from SakuraBox.
 * It verifies denial/prompt boundaries, NOT VPN traffic or selectedProxy persistence. */
public final class ProbeInstrumentation extends Instrumentation {
    private String target;
    private static final String NS = "io.nekohasekai.sagernet";

    @Override public void onCreate(Bundle arguments) {
        target = arguments == null ? null : arguments.getString("target");
        if (target == null) target = "app.sakurabox.client.debug";
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String[] actions = { "TOGGLE", "ENABLE", "DISABLE" };
            for (String action : actions) for (boolean forged : new boolean[]{false, true}) {
                ProbeActivity activity = foreground();
                final boolean[] launched = {true};
                runOnMainSync(() -> launched[0] = activity.attempt(target,
                        NS + ".ui.TrustedShortcutActivity", NS + ".shortcut." + action, null, forged));
                if (launched[0]) throw new AssertionError("Private executor accepted a cross-UID Intent");
                runOnMainSync(activity::finish);
            }
            String[] gateways = { NS + ".QuickToggleShortcut", NS + ".ui.QuickEnableShortcut", NS + ".ui.QuickDisableShortcut" };
            for (String gateway : gateways) for (boolean forged : new boolean[]{false, true}) {
                ProbeActivity activity = foreground();
                runOnMainSync(() -> {
                    if (!activity.attempt(target, gateway, Intent.ACTION_MAIN, null, forged))
                        throw new AssertionError("Legacy gateway no longer accessible");
                });
                if (!awaitConfirmation()) throw new AssertionError("Gateway did not stop at explicit user consent");
                // Intentionally never click Allow. Back cancels the request.
                getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                SystemClock.sleep(350);
                runOnMainSync(activity::finish);
            }
            for (String action : new String[]{Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED}) {
                boolean denied = false;
                try {
                    getTargetContext().sendBroadcast(new Intent(action).setComponent(new ComponentName(target, NS + ".BootReceiver")));
                } catch (SecurityException expected) { denied = true; }
                if (!denied) throw new AssertionError("System-protected broadcast was sendable by ordinary UID");
            }
            // Delivery of an arbitrary explicit broadcast may be allowed; the
            // target must reject it before side effects. Check VPN/DB separately.
            getTargetContext().sendBroadcast(new Intent("local.sakurabox.security001probe.FORGED_BOOT")
                    .setComponent(new ComponentName(target, NS + ".BootReceiver")));
            result.putString("boundary", "PASS: 6 private-entry denials, 6 consent prompts and 3 protected-broadcast denials");
            result.putString("not_covered", "Profile/state mutations, recreation, VPN permission, Launcher, Tile, notifications, onRevoke, real networking");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("boundary", "FAIL");
            result.putString("reason", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private ProbeActivity foreground() {
        Intent own = new Intent(getTargetContext(), ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ProbeActivity activity = (ProbeActivity) startActivitySync(own);
        waitForIdleSync();
        SystemClock.sleep(300);
        return activity;
    }

    private boolean awaitConfirmation() {
        long until = SystemClock.uptimeMillis() + 5000;
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try { if (target.contentEquals(root.getPackageName()) && hasAllow(root)) return true; }
                finally { root.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < until);
        return false;
    }

    private boolean hasAllow(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && ("Allow once".equalsIgnoreCase(text.toString()) || "仅允许这一次".contentEquals(text))) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (hasAllow(child)) return true; } finally { child.recycle(); }
        }
        return false;
    }
}
