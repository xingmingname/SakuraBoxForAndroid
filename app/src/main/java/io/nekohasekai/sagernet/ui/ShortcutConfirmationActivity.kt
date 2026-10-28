package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.shortcut.ShortcutConsent
import io.nekohasekai.sagernet.shortcut.ShortcutIntents
import io.nekohasekai.sagernet.shortcut.ShortcutOperation

/** Public compatibility gateway. No service connection or VPN-state write lives here. */
abstract class ShortcutConfirmationActivity : Activity() {
    protected abstract val operation: ShortcutOperation
    private var consent: ShortcutConsent? = null
    private var dialog: AlertDialog? = null
    private var resumed = false

    protected open fun createLegacyShortcut(): Boolean = false

    // Only obscured events are consumed; ordinary clicks remain handled by the Button.
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never restore a grant or a pending decision after recreation/process death.
        if (savedInstanceState != null) { finish(); return }
        try {
            if (createLegacyShortcut()) return
        } catch (_: RuntimeException) { finish(); return }
        if (intent.action != Intent.ACTION_MAIN) { finish(); return }
        val request = ShortcutIntents.read(intent, operation) ?: run { finish(); return }
        val message = if (request.profileId != null) {
            val profile = try { SagerDatabase.proxyDao.getById(request.profileId) }
                catch (_: RuntimeException) { null } ?: run { finish(); return }
            // Names are untrusted subscription text. Keep the consent prompt legible.
            val name = profile.displayName().filterNot {
                it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
            }.take(80)
            getString(R.string.shortcut_confirm_profile, name, request.profileId)
        } else getString(when (operation) {
            ShortcutOperation.TOGGLE -> R.string.shortcut_confirm_toggle
            ShortcutOperation.ENABLE -> R.string.shortcut_confirm_enable
            ShortcutOperation.DISABLE -> R.string.shortcut_confirm_disable
        })
        val decision = ShortcutConsent(request)
        consent = decision
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.shortcut_confirm_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.shortcut_confirm_allow, null)
            .create().also { prompt ->
                prompt.setOnDismissListener { decision.cancel(); finish() }
                prompt.setOnShowListener {
                    prompt.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        filterTouchesWhenObscured = true
                        setOnTouchListener { _, event ->
                            (event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0 ||
                                (Build.VERSION.SDK_INT >= 29 &&
                                    (event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0)
                        }
                        setOnClickListener {
                            val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                            val approved = decision.confirm(resumed && prompt.isShowing,
                                hasWindowFocus(), !keyguard.isKeyguardLocked)
                            if (approved != null) {
                                // Build a new Intent; do not forward flags, selectors or extras.
                                startActivity(ShortcutIntents.trusted(this@ShortcutConfirmationActivity, approved))
                            }
                            prompt.dismiss()
                        }
                    }
                }
                prompt.show()
            }
    }

    override fun onResume() { super.onResume(); resumed = true }

    override fun onPause() {
        resumed = false
        consent?.cancel()
        super.onPause()
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second sender cannot replace the request the user is reading.
        consent?.cancel()
        dialog?.dismiss()
        finish()
    }

    override fun onDestroy() {
        consent?.cancel()
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }
}
