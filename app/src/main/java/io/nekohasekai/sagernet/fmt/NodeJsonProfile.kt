package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ktx.app
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.utils.JavaUtil.gson
import java.util.concurrent.Callable

object NodeJsonProfile {
    data class Preview(val text: String, val pluginPorts: Set<Int> = emptySet(), val choices: List<String> = emptyList())
    data class Saved(val id: Long, val runtimeWarning: Boolean = false)

    fun preview(profile: ProxyEntity, mode: Int): Preview {
        val bean = profile.requireBean()
        val saved = NodeJson.saved(bean, mode)
        val legacyDocument = (bean.jsonLegacyMask ?: 0) and mode != 0
        if (saved.isNotBlank() && !legacyDocument && bean.jsonMode != NodeJson.LEGACY) return Preview(saved)
        if (mode == NodeJson.OUTBOUND && bean.jsonMode == NodeJson.FULL) {
            val root = NodeJson.parse(bean.customConfigJson)
            return try { Preview(NodeJson.text(NodeJson.previewOutbound(root))) }
            catch (e: NodeJson.Invalid) {
                if (e.message != "JSON_TEST_TARGET") throw e
                val choices = listOf("outbounds", "endpoints").flatMap { root.getAsJsonArray(it)?.map { out -> NodeJson.text(out.asJsonObject) } ?: emptyList() }
                if (choices.isEmpty()) throw e
                Preview("", choices = choices)
            }
        }
        val ordinary = profile.copy().apply { putBean(bean.clone().apply {
            if (legacyDocument) {
                jsonMode = NodeJson.LEGACY
                if ((jsonLegacyMask ?: 0) and NodeJson.OUTBOUND == 0) customOutboundJson = ""
                if ((jsonLegacyMask ?: 0) and NodeJson.FULL == 0) customConfigJson = ""
            }
        }) }
        val generated = buildConfig(ordinary, forPreview = true)
        val root = NodeJson.parse(generated.config)
        if (mode == NodeJson.FULL) {
            // These policies are normally applied by VpnService outside the core generator.
            root.getAsJsonArray("inbounds")?.forEach { item ->
                val tun = item.asJsonObject
                if (NodeJson.string(tun, "type") == "tun") {
                    if (!tun.has("auto_route")) tun.addProperty("auto_route", true)
                    if (!tun.has("route_address") && DataStore.bypassLan) {
                        val addresses = NodeJson.strings(tun, "address")
                        val routes = mutableListOf<String>()
                        if (addresses.any { ':' !in it }) routes.addAll(app.resources.getStringArray(io.nekohasekai.sagernet.R.array.bypass_private_route))
                        if (addresses.any { ':' in it }) routes.add("2000::/3")
                        if (DataStore.enableFakeDns) { routes.add("198.18.0.0/15"); if (addresses.any { ':' in it }) routes.add("fc00::/18") }
                        for (address in addresses) routes.add(address.substringBefore('/') + if (':' in address) "/128" else "/32")
                        // The virtual DNS address is the next address after the TUN address.
                        val plan = io.nekohasekai.sagernet.bg.VpnTunPlan().apply {
                            ipv4 = addresses.filter { ':' !in it }; ipv6 = addresses.filter { ':' in it }
                        }
                        plan.dnsServers().forEach { routes.add(it + if (':' in it) "/128" else "/32") }
                        tun.add("route_address", gson.toJsonTree(routes))
                    }
                    if (DataStore.proxyApps && !tun.has("include_package") && !tun.has("exclude_package"))
                        tun.add(if (DataStore.bypass) "exclude_package" else "include_package", gson.toJsonTree(DataStore.individual.lines().filter { it.isNotBlank() }.toMutableSet().apply {
                            if (DataStore.bypass) remove(app.packageName) else add(app.packageName)
                        }))
                    if (DataStore.appendHttpProxy && !tun.has("platform")) tun.add("platform", NodeJson.parse(
                        """{"http_proxy":{"enabled":true,"server":"127.0.0.1","server_port":${DataStore.mixedPort}}}"""))
                }
            }
            return Preview(NodeJson.text(root), generated.externalIndex.flatMap { it.chain.keys }.toSet())
        }
        val tag = generated.profileTagMap[profile.id]
            ?: throw NodeJson.Invalid(NodeJson.ErrorKind.REFERENCE, "Cannot identify this profile's outbound")
        val entries = listOf("outbounds", "endpoints").flatMap { root.getAsJsonArray(it)?.toList() ?: emptyList() }
        val outbound = entries.singleOrNull { it.asJsonObject.get("tag")?.asString == tag }
            ?: throw NodeJson.Invalid(NodeJson.ErrorKind.REFERENCE, "Cannot identify this profile's outbound")
        if (generated.externalIndex.any { it.chain.isNotEmpty() })
            return Preview(NodeJson.text(outbound.asJsonObject), generated.externalIndex.flatMap { it.chain.keys }.toSet())
        return Preview(NodeJson.text(outbound.asJsonObject))
    }

    fun validate(profile: ProxyEntity, mode: Int, text: String, pluginPorts: Set<Int> = emptySet()) {
        NodeJson.checkStorage(profile.requireBean().clone().apply { NodeJson.set(this, mode, text) })
        val root = if (mode == NodeJson.FULL) NodeJson.parse(text) else JsonObject().apply {
            val out = NodeJson.outbound(text, TAG_PROXY)
            add(if (NodeJson.string(out, "type") == "wireguard") "endpoints" else "outbounds", JsonArray().apply { add(out) })
        }
        if (mode == NodeJson.FULL) NodeJson.validateReferences(root)
        if (mode == NodeJson.FULL) requireAndroidJsonVpn(root, DataStore.serviceMode == io.nekohasekai.sagernet.Key.MODE_VPN)
        if (mode == NodeJson.FULL && profile.id != 0L && SagerDatabase.groupDao.getById(profile.groupId)?.type != GroupType.SUBSCRIPTION) {
            val groups = SagerDatabase.groupDao.allGroups()
            val referenced = groups.any { it.frontProxy == profile.id || it.landingProxy == profile.id } ||
                SagerDatabase.rulesDao.allRules().any { it.outbound == profile.id } ||
                groups.any { group -> SagerDatabase.proxyDao.getByGroup(group.id).any { it.chainBean?.proxies?.contains(profile.id) == true } }
            if (referenced) throw NodeJson.Invalid(NodeJson.ErrorKind.REFERENCE,
                "This node is referenced by a chain, group or routing rule. Copy it to an independent local node before full configuration takeover")
        }
        if (mode == NodeJson.FULL && android.os.Build.VERSION.SDK_INT < 29 && root.getAsJsonArray("inbounds")?.any {
                it.asJsonObject.getAsJsonObject("platform")?.getAsJsonObject("http_proxy")?.get("enabled")?.asBoolean == true
            } == true) throw NodeJson.Invalid(NodeJson.ErrorKind.ENVIRONMENT, "Android 10 or newer is required for the VPN HTTP proxy")
        if (pluginPorts.isNotEmpty()) {
            val outs = root.getAsJsonArray("outbounds") ?: JsonArray()
            if (outs.any { it.asJsonObject.get("server")?.asString == LOCALHOST &&
                        it.asJsonObject.get("server_port")?.asInt in pluginPorts })
                throw NodeJson.Invalid(NodeJson.ErrorKind.ENVIRONMENT, "JSON_PLUGIN_PREVIEW")
        }
        val candidate = profile.copy().apply { putBean(profile.requireBean().clone().apply { NodeJson.set(this, mode, text) }) }
        val generated = if (mode == NodeJson.FULL) root else NodeJson.parse(buildConfig(candidate, forPreview = true).config)
        NodeJson.validateReferences(generated)
        validateCore(generated)
    }

    fun validateCore(root: JsonObject) {
        val strict = root.deepCopy().apply { remove("_neko_sniff_override_rules") }
        try {
            // Decode all original fields before removing listeners and file-writing services for construction.
            Libcore.exportPortableConfig(strict.toString())
            val construction = root.deepCopy().apply {
                add("log", JsonObject().apply { addProperty("disabled", true) })
                add("inbounds", JsonArray())
                remove("experimental")
                remove("services")
            }
            Libcore.newSingBoxInstance(construction.toString(), LocalResolverImpl).close()
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            val kind = when {
                listOf("permission", "no such file", "asset", "network is", "unavailable").any { message.contains(it, true) } -> NodeJson.ErrorKind.ENVIRONMENT
                message.contains("unknown field", true) || message.contains("unknown type", true) -> NodeJson.ErrorKind.UNSUPPORTED
                message.contains("not found", true) || message.contains("circular", true) -> NodeJson.ErrorKind.REFERENCE
                message.contains("missing", true) || message.contains("required", true) -> NodeJson.ErrorKind.MISSING
                else -> NodeJson.ErrorKind.CORE
            }
            // Never log user JSON or the native error, which can contain credentials.
            val field = Regex("(?:unknown field|missing|decode) [\"']?([a-z_][a-z_0-9.\\[\\]]*)").find(message)?.groupValues?.get(1)
            throw NodeJson.Invalid(kind, field ?: "Native configuration check rejected this configuration")
        }
    }

    suspend fun save(id: Long, expected: NodeJson.State, mode: Int, text: String, copySubscription: Boolean = false, pluginPorts: Set<Int> = emptySet(), beforeCommit: () -> Unit = {}): Saved {
        require(mode in NodeJson.NONE..NodeJson.FULL)
        val source = SagerDatabase.proxyDao.getById(id) ?: error("JSON_NODE_REMOVED")
        if (NodeJson.state(source.requireBean()) != expected) error("JSON_NODE_CHANGED")
        if (mode != NodeJson.NONE) validate(source, mode, text, pluginPorts)
        val subscription = SagerDatabase.groupDao.getById(source.groupId)?.type == GroupType.SUBSCRIPTION
        if (mode == NodeJson.FULL && subscription) {
            check(copySubscription) { "JSON_SUBSCRIPTION_COPY" }
            val targetGroup = SagerDatabase.groupDao.allGroups().firstOrNull { it.type == GroupType.BASIC }?.id
                ?: error("JSON_LOCAL_GROUP_REQUIRED")
            beforeCommit()
            val copy = SagerDatabase.instance.runInTransaction(Callable {
                val current = SagerDatabase.proxyDao.getById(id) ?: error("JSON_NODE_REMOVED")
                check(NodeJson.state(current.requireBean()) == expected) { "JSON_NODE_CHANGED" }
                ProxyEntity(groupId = targetGroup).apply {
                    putBean(current.requireBean().clone().apply { NodeJson.set(this, mode, text) })
                    userOrder = SagerDatabase.proxyDao.nextOrder(targetGroup) ?: 1
                    this.id = SagerDatabase.proxyDao.addProxy(this)
                }
            })
            var warning = false
            try { ProfileManager.iterator { onAdd(copy) } } catch (_: Exception) { warning = true }
            return Saved(copy.id, warning)
        }
        beforeCommit()
        SagerDatabase.instance.runInTransaction(Callable {
            val current = SagerDatabase.proxyDao.getById(id) ?: error("JSON_NODE_REMOVED")
            check(NodeJson.state(current.requireBean()) == expected) { "JSON_NODE_CHANGED" }
            current.putBean(current.requireBean().clone().apply {
                if (mode == NodeJson.NONE) jsonMode = NodeJson.NONE else NodeJson.set(this, mode, text)
            })
            check(SagerDatabase.proxyDao.updateProxy(current) == 1)
        })
        var warning = false
        try { ProfileManager.postUpdate(id) } catch (_: Exception) { warning = true }
        try { SagerNet.reloadService(id) }
        catch (_: Exception) { warning = true }
        return Saved(id, warning)
    }
}
