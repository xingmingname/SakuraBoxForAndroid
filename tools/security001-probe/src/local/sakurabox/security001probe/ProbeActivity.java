package local.sakurabox.security001probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Runs in an ordinary, separate application UID, never in SakuraBox's UID. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(this);
        text.setText("Isolated SB-SEC-001 probe. Never approve a VPN operation during a negative test.");
        layout.addView(text);
        EditText target = new EditText(this);
        target.setSingleLine(true);
        target.setText("app.sakurabox.client.debug");
        layout.addView(target);
        EditText profile = new EditText(this);
        profile.setSingleLine(true);
        profile.setHint("Optional synthetic profile ID, for Toggle only");
        layout.addView(profile);
        String[] names = {"Toggle", "Enable", "Disable", "Private executor (must deny)"};
        String[] classes = {".QuickToggleShortcut", ".ui.QuickEnableShortcut", ".ui.QuickDisableShortcut", ".ui.TrustedShortcutActivity"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setText(names[i]);
            button.setOnClickListener(view -> {
                Object id = null;
                if (index == 0 && profile.length() != 0) {
                    try { id = Long.valueOf(profile.getText().toString()); }
                    catch (NumberFormatException invalid) { text.setText("Invalid synthetic ID"); return; }
                }
                boolean accepted = attempt(target.getText().toString(), "io.nekohasekai.sagernet" + classes[index],
                        index == 3 ? "io.nekohasekai.sagernet.shortcut.TOGGLE" : Intent.ACTION_MAIN, id, true);
                text.setText(accepted ? "Intent dispatched. Expect consent only; cancel it and verify VPN/node state unchanged."
                        : "SecurityException: cross-UID operation rejected.");
            });
            layout.addView(button);
        }
        setContentView(layout);
    }

    boolean attempt(String target, String cls, String action, Object profile, boolean forge) {
        Intent intent = new Intent(action).setComponent(new ComponentName(target, cls));
        if (profile instanceof Long) intent.putExtra("profile", (Long) profile);
        if (profile instanceof String) intent.putExtra("profile", (String) profile);
        if (forge) {
            intent.putExtra("authorized", true);
            intent.putExtra("callingPackage", target);
            intent.putExtra("token", "forged-not-a-capability");
            intent.putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://" + target);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        try { startActivity(intent); return true; }
        catch (SecurityException expected) { return false; }
    }
}
