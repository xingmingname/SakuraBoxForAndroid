package io.nekohasekai.sagernet.fmt

import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONObject

/** Synthetic parse/config tests only. Execute in an isolated Debug installation.
 * Compiling these tests is not Android runtime verification.
 */
@Suppress("DEPRECATION")
class ProtocolUIIntegrationTest : InstrumentationTestCase() {
    private val node = """{"type":"anytls","tag":"synthetic","server":"127.0.0.1","server_port":443,"password":"synthetic-test","idle_session_timeout":"60s","min_idle_session":2,"tls":{"enabled":true,"server_name":"example.invalid","handshake_timeout":"5s"}}"""

    fun testJsonAndYamlImportKeepSessionOptions() = runBlocking(Dispatchers.Default) {
        val json = RawUpdater.parseRaw("""{"outbounds":[$node]}""")!!.single() as AnyTLSBean
        assertEquals("60s", ProtocolOptions.get(ProtocolOptions.parse(json.protocolOptionsJson), "idle_session_timeout")!!.asString)
        val yaml = """proxies:
 - {name: synthetic, type: anytls, server: 127.0.0.1, port: 443, password: synthetic-test, idle-session-check-interval: 30s, idle-session-timeout: 60s, min-idle-session: 2}
"""
        val fromYaml = RawUpdater.parseRaw(yaml)!!.single() as AnyTLSBean
        assertEquals(2, ProtocolOptions.get(ProtocolOptions.parse(fromYaml.protocolOptionsJson), "min_idle_session")!!.asInt)
        assertEquals(fromYaml.protocolOptionsJson, fromYaml.clone().protocolOptionsJson)
    }

    fun testTypedSingBoxJsonDoesNotEnterLegacyLossyHeuristics() = runBlocking(Dispatchers.Default) {
        for (source in listOf(
            """{"type":"hysteria2","server":"127.0.0.1","server_port":443,"password":"synthetic","up_mbps":10,"hop_interval_max":"30s","tls":{"enabled":true}}""",
            """{"type":"shadowsocks","server":"127.0.0.1","server_port":443,"method":"aes-128-gcm","password":"synthetic","multiplex":{"enabled":true,"max_connections":2}}"""
        )) {
            val bean = RawUpdater.parseRaw(source)!!.single() as ConfigBean
            assertEquals(com.google.gson.JsonParser.parseString(source), com.google.gson.JsonParser.parseString(bean.config))
        }
    }

    fun testFullBuildConfigNativeMigrationAndPortableExportKeepAnyTLSOptions() = runBlocking(Dispatchers.IO) {
        val mode=DataStore.serviceMode; val global=DataStore.globalCustomConfig
        val direct=DataStore.directDns; val remote=DataStore.remoteDns
        try {
            DataStore.serviceMode=Key.MODE_PROXY; DataStore.globalCustomConfig=""
            DataStore.directDns="local"; DataStore.remoteDns="udp://127.0.0.1:15353"
            val bean=RawUpdater.parseRaw(node)!!.single() as AnyTLSBean
            val full=buildConfig(ProxyEntity().putBean(bean), forTest=true).config
            fun check(text: String) {
                val array=JSONObject(text).getJSONArray("outbounds")
                val outbound=(0 until array.length()).map { array.getJSONObject(it) }.first { it.optString("type")=="anytls" }
                assertEquals(2,outbound.getInt("min_idle_session"))
                assertEquals("60s",outbound.getString("idle_session_timeout"))
                assertEquals("5s",outbound.getJSONObject("tls").getString("handshake_timeout"))
            }
            check(full); check(Libcore.migrateConfig(full)); check(Libcore.exportPortableConfig(full))
            Libcore.newSingBoxInstance(full, LocalResolverImpl).close()
        } finally {
            DataStore.serviceMode=mode; DataStore.globalCustomConfig=global
            DataStore.directDns=direct; DataStore.remoteDns=remote
        }
    }
}
