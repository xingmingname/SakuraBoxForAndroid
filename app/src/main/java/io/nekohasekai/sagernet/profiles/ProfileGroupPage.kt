package io.nekohasekai.sagernet.profiles

import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ProfileSearchCache
import io.nekohasekai.sagernet.ui.ToolbarFragment
import io.nekohasekai.sagernet.ui.matchesProfileQuery
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor

abstract class ProfileGroupPage : Fragment() {

    lateinit var proxyGroup: ProxyGroup
    var selected = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return LayoutProfileListBinding.inflate(inflater).root
    }

    lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
    var adapter: ConfigurationAdapter? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (::proxyGroup.isInitialized) {
            outState.putParcelable("proxyGroup", proxyGroup)
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
            proxyGroup = it
            onViewCreated(requireView(), null)
        }
    }

    private val isEnabled: Boolean
        get() {
            return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
        }

    private var profileBinding: LayoutProfileListBinding? = null
    private var profileLayoutManager: LinearLayoutManager? = null
    val layoutManager get() = checkNotNull(profileLayoutManager)
    val configurationListView get() = checkNotNull(profileBinding).configurationList

    val select by lazy {
        try {
            (parentFragment as ConfigurationFragment).select
        } catch (e: Exception) {
            Logs.e(e)
            false
        }
    }
    val selectedItem by lazy {
        try {
            (parentFragment as ConfigurationFragment).selectedItem
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    override fun onResume() {
        super.onResume()
        if (profileBinding == null) view?.let { onViewCreated(it, null) }
        if (profileBinding == null) return
        checkOrderMenu()
        configurationListView.requestFocus()
    }

    fun checkOrderMenu() {
        if (select) return

        val pf = requireParentFragment() as? ToolbarFragment ?: return
        val menu = pf.toolbar.menu
        val origin = menu.findItem(R.id.action_order_origin)
        val byName = menu.findItem(R.id.action_order_by_name)
        val byDelay = menu.findItem(R.id.action_order_by_delay)
        when (proxyGroup.order) {
            GroupOrder.ORIGIN -> {
                origin.isChecked = true
            }

            GroupOrder.BY_NAME -> {
                byName.isChecked = true
            }

            GroupOrder.BY_DELAY -> {
                byDelay.isChecked = true
            }
        }

        fun updateTo(order: Int) {
            if (proxyGroup.order == order) return
            runOnDefaultDispatcher {
                proxyGroup.order = order
                GroupManager.updateGroup(proxyGroup)
            }
        }

        origin.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.ORIGIN)
            true
        }
        byName.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.BY_NAME)
            true
        }
        byDelay.setOnMenuItemClickListener {
            it.isChecked = true
            updateTo(GroupOrder.BY_DELAY)
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::proxyGroup.isInitialized) return

        adapter?.dispose()
        profileBinding = LayoutProfileListBinding.bind(view)
        profileLayoutManager = FixedLinearLayoutManager(configurationListView)
        configurationListView.layoutManager = layoutManager
        adapter = ConfigurationAdapter()
        ProfileManager.addListener(adapter!!)
        GroupManager.addListener(adapter!!)
        configurationListView.adapter = adapter
        configurationListView.setItemViewCacheSize(20)
        adapter?.reloadProfiles()

        if (!select) {

            undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
            ) {
                override fun getSwipeDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return 0
                }

                override fun getDragDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) = if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                ): Boolean {
                    adapter?.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    return true
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    adapter?.commitMove()
                }
            }).attachToRecyclerView(configurationListView)

        }

    }

    override fun onDestroyView() {
        adapter?.dispose()
        profileBinding?.configurationList?.adapter = null
        adapter = null
        if (::undoManager.isInitialized) undoManager.flush()
        profileLayoutManager = null
        profileBinding = null
        super.onDestroyView()
    }

    inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
        ProfileManager.Listener,
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyEntity> {

        init {
            setHasStableIds(true)
        }

        var configurationIdList: MutableList<Long> = mutableListOf()
        internal val configurationList = ProfileSearchCache()

        private var reloadJob: Job? = null
        private val missingLoads = mutableMapOf<Long, Job>()
        private val hiddenIds = mutableSetOf<Long>()
        private val reloadTraffic = mutableMapOf<Long, TrafficData>()
        private var query = ""

        private fun active() = adapter === this && profileBinding != null
        private fun getItemAt(index: Int) = configurationIdList.getOrNull(index)?.let(configurationList::get)

        fun dispose() {
            ProfileManager.removeListener(this)
            GroupManager.removeListener(this)
            reloadJob?.cancel()
            missingLoads.values.toList().forEach { it.cancel() }
            missingLoads.clear()
            reloadTraffic.clear()
        }

        private fun loadMissing(id: Long) {
            if (!active() || missingLoads[id]?.isActive == true) return
            missingLoads[id] = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val profile = withContext(Dispatchers.IO) { ProfileManager.getProfile(id) }
                    if (!active() || id in hiddenIds || configurationList.containsKey(id)) return@launch
                    val index = configurationIdList.indexOf(id)
                    if (index < 0) return@launch
                    if (profile == null || profile.groupId != proxyGroup.id) {
                        configurationIdList.removeAt(index)
                        notifyItemRemoved(index)
                    } else {
                        configurationList[id] = profile
                        notifyItemChanged(index)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Logs.w(error) }
                finally {
                    if (missingLoads[id] === currentCoroutineContext()[Job]) missingLoads.remove(id)
                }
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ConfigurationHolder {
            return ConfigurationHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.layout_profile, parent, false)
            )
        }

        override fun getItemId(position: Int): Long {
            return configurationIdList[position]
        }

        override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
            val profile = getItemAt(position)
            if (profile != null) holder.bind(profile)
            else {
                holder.unbind()
                configurationIdList.getOrNull(position)?.let(::loadMissing)
            }
        }

        override fun onViewRecycled(holder: ConfigurationHolder) {
            holder.unbind()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int {
            return configurationIdList.size
        }

        private val updated = HashSet<ProxyEntity>()

        fun filter(name: String) {
            query = name
            if (name.isEmpty()) {
                configurationIdList.clear()
                configurationIdList.addAll(configurationList.orderedIds(proxyGroup.order))
                notifyDataSetChanged()
                val selectedIndex = if (selected) {
                    configurationIdList.indexOf(selectedItem?.id ?: DataStore.selectedProxy)
                } else -1
                if (selectedIndex >= 0) configurationListView.scrollTo(selectedIndex, true)
                else if (configurationIdList.isNotEmpty()) configurationListView.scrollTo(0, true)
                return
            }
            configurationIdList.clear()
            configurationIdList.addAll(configurationList.matchingIds(name))
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            if (from !in configurationIdList.indices || to !in configurationIdList.indices) return
            if ((minOf(from, to)..maxOf(from, to)).any { getItemAt(it) == null }) {
                reloadProfiles()
                return
            }
            val first = requireNotNull(getItemAt(from))
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = requireNotNull(getItemAt(i + step))
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                configurationIdList[i] = next.id
                updated.add(next)
            }
            first.userOrder = previousOrder
            configurationIdList[to] = first.id
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            val pending = updated.map { it.id to it.userOrder }
            updated.clear()
            runOnDefaultDispatcher {
                withContext(Dispatchers.IO) {
                    SagerDatabase.instance.runInTransaction {
                        val profiles = pending.mapNotNull { (id, order) ->
                            SagerDatabase.proxyDao.getById(id)?.apply { userOrder = order }
                        }
                        SagerDatabase.proxyDao.updateProxy(profiles)
                    }
                }
            }
        }

        fun remove(pos: Int) {
            configurationIdList.getOrNull(pos)?.let { hiddenIds.add(it) }
            if (!configurationList.removeVisible(configurationIdList, pos)) return
            notifyItemRemoved(pos)
        }

        override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
            if (!active()) return
            for ((index, item) in actions) {
                configurationListView.post {
                    if (!active()) return@post
                    hiddenIds.remove(item.id)
                    configurationList[item.id] = item
                    configurationIdList.add(index.coerceIn(0, configurationIdList.size), item.id)
                    notifyItemInserted(index)
                }
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
            val profiles = actions.map { it.second }
            runOnDefaultDispatcher {
                for (entity in profiles) {
                    ProfileManager.deleteProfile(entity.groupId, entity.id)
                }
            }
        }

        override suspend fun onAdd(profile: ProxyEntity) = onMainDispatcher {
            if (!active() || profile.groupId != proxyGroup.id || profile.id in hiddenIds) return@onMainDispatcher
            if (::undoManager.isInitialized) undoManager.flush()
            configurationList[profile.id] = profile
            if (profile.id !in configurationIdList && (query.isEmpty() || profile.matchesProfileQuery(query.lowercase()))) {
                configurationIdList.add(profile.id)
                notifyItemInserted(configurationIdList.lastIndex)
            }
            if (reloadJob?.isActive == true) reloadProfiles()
        }

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = onMainDispatcher {
            if (!active() || profile.groupId != proxyGroup.id || profile.id in hiddenIds) return@onMainDispatcher
            val old = configurationList.updateExisting(profile)
            if (old == null && profile.id in configurationIdList) configurationList[profile.id] = profile
            val index = configurationIdList.indexOf(profile.id)
            if (index >= 0) {
                if (::undoManager.isInitialized) undoManager.flush()
                if (noTraffic && old != null) {
                    profile.rx = old.rx
                    profile.tx = old.tx
                }
                notifyItemChanged(index)
            }
            if (reloadJob?.isActive == true) reloadProfiles()
        }

        override suspend fun onUpdated(data: TrafficData) = onMainDispatcher {
            if (!active()) return@onMainDispatcher
            if (reloadJob?.isActive == true) reloadTraffic[data.id] = data
            val entity = configurationList[data.id] ?: return@onMainDispatcher
            entity.rx = data.rx
            entity.tx = data.tx
            val holder = configurationListView.findViewHolderForItemId(data.id) as? ConfigurationHolder
                ?: return@onMainDispatcher
            if (configurationIdList.getOrNull(holder.bindingAdapterPosition) == data.id &&
                holder.itemId == data.id) {
                holder.bindTraffic(data)
            }
        }

        override suspend fun onRemoved(groupId: Long, profileId: Long) = onMainDispatcher {
            if (!active() || groupId != proxyGroup.id) return@onMainDispatcher
            hiddenIds.remove(profileId)
            reloadTraffic.remove(profileId)
            configurationList.remove(profileId)
            val index = configurationIdList.indexOf(profileId)
            if (index >= 0) {
                configurationIdList.removeAt(index)
                notifyItemRemoved(index)
            }
            if (reloadJob?.isActive == true) reloadProfiles()
        }

        override suspend fun groupAdd(group: ProxyGroup) = Unit
        override suspend fun groupRemoved(groupId: Long) = Unit

        override suspend fun groupUpdated(group: ProxyGroup) = onMainDispatcher {
            if (!active() || group.id != proxyGroup.id) return@onMainDispatcher
            proxyGroup = group
            reloadProfiles()
        }

        override suspend fun groupUpdated(groupId: Long) {
            if (!onMainDispatcher { active() && groupId == proxyGroup.id }) return
            val group = withContext(Dispatchers.IO) { SagerDatabase.groupDao.getById(groupId) } ?: return
            groupUpdated(group)
        }

        fun reloadProfiles() = runOnMainDispatcher {
            if (!active()) return@runOnMainDispatcher
            reloadJob?.cancel()
            reloadTraffic.clear()
            val groupId = proxyGroup.id
            val order = proxyGroup.order
            val selectedId = selectedItem?.id
            reloadJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
                        when (order) {
                            GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName() }
                            GroupOrder.BY_DELAY -> profiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                            else -> profiles
                        }
                    }
                    if (!active() || proxyGroup.id != groupId) return@launch
                    configurationList.clear()
                    configurationIdList.clear()
                    val searching = query.isNotEmpty()
                    for (profile in snapshot) {
                        if (profile.id in hiddenIds) continue
                        reloadTraffic[profile.id]?.let { profile.rx = it.rx; profile.tx = it.tx }
                        configurationList[profile.id] = profile
                        if (!searching) configurationIdList.add(profile.id)
                    }
                    reloadTraffic.clear()
                    if (searching) configurationIdList.addAll(configurationList.matchingIds(query))
                    notifyDataSetChanged()
                    val selectedProxy = withContext(Dispatchers.IO) { selectedId ?: DataStore.selectedProxy }
                    if (!active()) return@launch
                    val index = if (selected) configurationIdList.indexOf(selectedProxy) else -1
                    if (index >= 0) configurationListView.scrollTo(index, true)
                    else if (configurationIdList.isNotEmpty()) configurationListView.scrollTo(0, true)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Logs.w(error) }
            }
        }

    }

    val profileAccess = Mutex()
    val reloadAccess = Mutex()

    inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
        PopupMenu.OnMenuItemClickListener {

        lateinit var entity: ProxyEntity

        val profileName: TextView = view.findViewById(R.id.profile_name)
        val profileType: TextView = view.findViewById(R.id.profile_type)
        val profileAddress: TextView = view.findViewById(R.id.profile_address)
        val profileStatus: TextView = view.findViewById(R.id.profile_status)

        val trafficText: TextView = view.findViewById(R.id.traffic_text)
        val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
        val editButton: ImageView = view.findViewById(R.id.edit)
        val jsonButton: ImageView = view.findViewById(R.id.node_json)
        val shareLayout: LinearLayout = view.findViewById(R.id.share)
        val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
        val shareButton: ImageView = view.findViewById(R.id.shareIcon)
        val removeButton: ImageView = view.findViewById(R.id.remove)

        fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
            if (parentFragment !is ConfigurationFragment) return

            entity = proxyEntity
            view.visibility = View.VISIBLE
            bindingGeneration++
            val generation = bindingGeneration
            bindingJob?.cancel()

            if (select) {
                view.setOnClickListener {
                    (requireActivity() as ConfigurationFragment.SelectCallback).returnProfile(proxyEntity.id)
                }
            } else {
                view.setOnClickListener {
                    runOnDefaultDispatcher {
                        var update: Boolean
                        var lastSelected: Long
                        profileAccess.withLock {
                            update = DataStore.selectedProxy != proxyEntity.id
                            lastSelected = DataStore.selectedProxy
                            DataStore.selectedProxy = proxyEntity.id
                            onMainDispatcher {
                                if (isBound(proxyEntity.id)) selectedView.visibility = View.VISIBLE
                            }
                        }

                        if (update) {
                            ProfileManager.postUpdate(lastSelected)
                            if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                                SagerNet.reloadService()
                                reloadAccess.unlock()
                            }
                        } else if (SagerNet.isTv) {
                            if (DataStore.serviceState.started) {
                                SagerNet.stopService()
                            } else {
                                SagerNet.startService()
                            }
                        }
                    }

                }
            }

            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

            renderTraffic(proxyEntity, trafficData)

            editButton.setOnClickListener {
                it.context.startActivity(
                    proxyEntity.settingIntent(
                        it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                    )
                )
            }

            jsonButton.isGone = select
            val jsonEnabled = io.nekohasekai.sagernet.fmt.NodeJson.enabled(proxyEntity.requireBean())
            jsonButton.alpha = if (jsonEnabled) 1f else 0.65f
            jsonButton.contentDescription = getString(if (jsonEnabled) R.string.json_manage_active else R.string.json_manage)
            jsonButton.setOnClickListener {
                io.nekohasekai.sagernet.ui.profile.NodeJsonMenu.show(requireActivity(), it, proxyEntity.id)
            }

            removeButton.setOnClickListener {
                adapter?.let {
                    val index = it.configurationIdList.indexOf(proxyEntity.id)
                    it.remove(index)
                    undoManager.remove(index to proxyEntity)
                }
            }

            val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
            shareLayout.isGone = selectOrChain
            editButton.isGone = select
            removeButton.isGone = select

            proxyEntity.nekoBean?.apply {
                shareLayout.isGone = true
            }

            bindingJob = viewLifecycleOwner.lifecycleScope.launch {
                val selectedId = selectedItem?.id
                val selection = withContext(Dispatchers.IO) {
                    val selected = (selectedId ?: DataStore.selectedProxy) == proxyEntity.id
                    selected to (selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id)
                }
                if (generation != bindingGeneration || !isBound(proxyEntity.id)) return@launch
                editButton.isEnabled = !selection.second
                removeButton.isEnabled = !selection.second
                selectedView.visibility = if (selection.first) View.VISIBLE else View.INVISIBLE
            }

            fun showShare(anchor: View) {
                if (!isBound(proxyEntity.id)) return
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)
                when {
                    !proxyEntity.haveStandardLink() -> {
                        popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                        popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(R.id.action_standard_clipboard)
                    }
                    !proxyEntity.haveLink() -> {
                        popup.menu.removeItem(R.id.action_group_qr)
                        popup.menu.removeItem(R.id.action_group_clipboard)
                    }
                }
                if (proxyEntity.nekoBean != null) popup.menu.removeItem(R.id.action_group_configuration)
                popup.setOnMenuItemClickListener { item ->
                    if (isBound(proxyEntity.id)) onMenuItemClick(item) else false
                }
                popup.show()
            }
            if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                shareLayer.setBackgroundColor(Color.TRANSPARENT)
                shareButton.setImageResource(R.drawable.ic_social_share)
                shareButton.setColorFilter(Color.GRAY)
                shareButton.isVisible = true
                shareLayout.setOnClickListener { showShare(it) }
            }
        }

        fun bindTraffic(data: TrafficData) {
            if (!isBound(data.id)) return
            renderTraffic(entity, data)
        }

        private fun renderTraffic(proxyEntity: ProxyEntity, trafficData: TrafficData?) {
            val pf = parentFragment as? ConfigurationFragment ?: return
            var rx = proxyEntity.rx
            var tx = proxyEntity.tx
            if (trafficData != null) {
                // use new data
                tx = trafficData.tx
                rx = trafficData.rx
            }

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = view.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(view.context, tx),
                    Formatter.formatFileSize(view.context, rx)
                )
            }

            var address = proxyEntity.displayAddress()
            if (showTraffic && address.length >= 30) {
                address = address.substring(0, 27) + "..."
            }

            if (proxyEntity.requireBean().name.isBlank() || !pf.alwaysShowAddress) {
                address = ""
            }

            profileAddress.text = address
            (trafficText.parent as View).isGone =
                (!showTraffic || proxyEntity.status <= 0) && address.isBlank()

            if (proxyEntity.status <= 0) {
                if (showTraffic) {
                    profileStatus.text = trafficText.text
                    profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                    trafficText.text = ""
                } else {
                    profileStatus.text = ""
                }
            } else if (proxyEntity.status == 1) {
                profileStatus.text = getString(R.string.available, proxyEntity.ping)
                profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
            } else {
                profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                if (proxyEntity.status == 2) {
                    profileStatus.text = proxyEntity.error
                }
            }

            if (proxyEntity.status == 3) {
                val err = proxyEntity.error ?: "<?>"
                val msg = Protocols.genFriendlyMsg(err)
                profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
                profileStatus.setOnClickListener {
                    alert(err).tryToShow()
                }
            } else {
                profileStatus.setOnClickListener(null)
            }

        }

        private var bindingJob: Job? = null
        private var bindingGeneration = 0L

        private fun isBound(id: Long) = ::entity.isInitialized && entity.id == id &&
            bindingAdapterPosition != RecyclerView.NO_POSITION &&
            adapter?.configurationIdList?.getOrNull(bindingAdapterPosition) == id

        fun unbind() {
            bindingGeneration++
            bindingJob?.cancel()
            bindingJob = null
            view.visibility = View.INVISIBLE
            view.setOnClickListener(null)
        }

        var currentName = ""
        fun showCode(link: String) {
            QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
        }

        fun export(link: String) {
            val success = SagerNet.trySetPrimaryClip(link)
            (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                .show()
        }

        override fun onMenuItemClick(item: MenuItem): Boolean {
            try {
                currentName = entity.displayName()!!
                when (item.itemId) {
                    R.id.action_standard_qr -> showCode(entity.toStdLink())
                    R.id.action_standard_clipboard -> export(entity.toStdLink())
                    R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                    R.id.action_universal_clipboard -> export(
                        entity.requireBean().toUniversalLink()
                    )

                    R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                    R.id.action_config_export_official_clipboard -> export(entity.exportConfig(official = true).first)
                    R.id.action_config_export_file, R.id.action_config_export_official_file -> {
                        val cfg = entity.exportConfig(official = item.itemId == R.id.action_config_export_official_file)
                        DataStore.serverConfig = cfg.first
                        startFilesForResult(
                            (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                        )
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                (activity as MainActivity).snackbar(e.readableMessage).show()
                return true
            }
            return true
        }
    }

}
