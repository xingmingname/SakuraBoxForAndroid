/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package io.nekohasekai.sagernet

import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.sagernet.shortcut.ShortcutOperation
import io.nekohasekai.sagernet.ui.ShortcutConfirmationActivity

@Suppress("DEPRECATION")
class QuickToggleShortcut : ShortcutConfirmationActivity() {
    override val operation = ShortcutOperation.TOGGLE

    override fun createLegacyShortcut(): Boolean {
        if (intent.action != Intent.ACTION_CREATE_SHORTCUT) return false
        // Legacy INSTALL_SHORTCUT launchers use direct Intents, not the platform
        // shortcut broker. Keep this public, confirmation-only compatibility target.
        setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this,
            ShortcutInfoCompat.Builder(this, "toggle")
                .setIntent(Intent(this, QuickToggleShortcut::class.java).setAction(Intent.ACTION_MAIN))
                .setIcon(IconCompat.createWithResource(this, R.drawable.ic_qu_shadowsocks_launcher))
                .setShortLabel(getString(R.string.quick_toggle)).build()))
        finish()
        return true
    }
}
