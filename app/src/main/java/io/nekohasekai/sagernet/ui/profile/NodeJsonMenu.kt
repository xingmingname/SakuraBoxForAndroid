package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.NodeJsonProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object NodeJsonMenu {
    const val PROFILE_ID = "nodeJsonProfileId"
    const val MODE = "nodeJsonMode"
    fun edit(activity: android.content.Context, id: Long, mode: Int, enable: Boolean = false) {
        activity.startActivity(Intent(activity, ConfigEditActivity::class.java).apply {
            putExtra(PROFILE_ID, id); putExtra(MODE, mode); putExtra("enableJson", enable)
        })
    }
    fun show(activity: FragmentActivity, anchor: View, id: Long) {
        activity.lifecycleScope.launch {
            val bean = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getById(id)?.requireBean() } ?: return@launch
            val enabled = NodeJson.enabled(bean)
            val popup = PopupMenu(activity, anchor)
            popup.menu.add(0, 1, 0, if (enabled) R.string.json_disable else R.string.json_enable)
            popup.menu.add(0, 2, 1, R.string.json_full)
            popup.menu.add(0, 3, 2, R.string.json_outbound)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    2 -> edit(activity, id, NodeJson.FULL)
                    3 -> edit(activity, id, NodeJson.OUTBOUND)
                    1 -> if (enabled) {
                        activity.lifecycleScope.launch {
                            try {
                                val saved = withContext(Dispatchers.IO) { NodeJsonProfile.save(id, NodeJson.state(bean), NodeJson.NONE, "") }
                                if (saved.runtimeWarning) android.widget.Toast.makeText(activity, R.string.json_saved_reconnect, android.widget.Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {
                                MaterialAlertDialogBuilder(activity).setMessage(R.string.json_conflict).setPositiveButton(android.R.string.ok, null).show()
                            }
                        }
                    } else {
                        val modes = listOf(NodeJson.FULL, NodeJson.OUTBOUND).filter { NodeJson.saved(bean, it).isNotBlank() }
                        if (modes.size == 1) edit(activity, id, modes.single(), true)
                        else MaterialAlertDialogBuilder(activity).setTitle(R.string.json_choose)
                            .setItems(arrayOf(activity.getString(R.string.json_full), activity.getString(R.string.json_outbound))) { _, at ->
                                edit(activity, id, if (at == 0) NodeJson.FULL else NodeJson.OUTBOUND, true)
                            }.show()
                    }
                }
                true
            }
            popup.show()
        }
    }
}
