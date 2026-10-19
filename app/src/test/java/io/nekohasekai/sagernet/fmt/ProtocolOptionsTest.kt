package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.importAnyTLSOutbound
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class ProtocolOptionsTest {
    private fun any() = AnyTLSBean().apply {
        initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
        password = "synthetic-test-password"; sni = "example.invalid"
    }
    private fun configure(bean: AbstractBean, path: String, input: String) {
        val field = ProtocolOptions.fields(bean).first { it.path == path }
        bean.protocolOptionsJson = ProtocolOptions.set(ProtocolOptions.parse(bean.protocolOptionsJson), field, input).toString()
    }
    private fun fixture(name: String, bean: AbstractBean, outbound: SingBoxOptions.SingBoxOption) {
        ProtocolOptions.validate(bean, ProtocolOptions.parse(bean.protocolOptionsJson))
        // Same existing override boundary as ConfigBuilder's internal & external outbound path.
        ProtocolOptions.applyTo(outbound, bean)
        outbound._hack_custom_config = bean.customOutboundJson
        outbound._hack_config_map["tag"] = "proxy"
        val root = SingBoxOptions.MyOptions().apply {
            if (bean is WireGuardBean) {
                endpoints = listOf(outbound)
                outbounds = listOf(SingBoxOptions.Outbound().apply { type="direct"; tag="direct" })
            } else outbounds = listOf(outbound)
            route = SingBoxOptions.RouteOptions().apply { final_ = "proxy" }
        }.asMap()
        val text = Gson().toJson(root)
        assertEquals(com.google.gson.JsonParser.parseString(text), com.google.gson.JsonParser.parseString(formatExportJson(text)))
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { File(it).mkdirs(); File(it, "protocol-ui-$name.json").writeText(text) }
    }

    @Test fun emptyOptionsDoNotForceZeroOrAlterLegacyNodes() {
        val bean = any()
        val old = buildSingBoxOutboundAnyTLSBean(bean).asMap()
        for (field in ProtocolOptions.fields(bean)) {
            assertNull(ProtocolOptions.value(field, "  "))
            assertEquals("", ProtocolOptions.text(ProtocolOptions.parse(""), field))
        }
        assertFalse(old.containsKey("min_idle_session"))
        assertFalse(old.containsKey("idle_session_timeout"))
        fixture("anytls-default", bean, buildSingBoxOutboundAnyTLSBean(bean))
    }

    @Test fun anyTLSAdvancedOptionsSurviveDatabaseBlobCloneAndFinalSerialization() {
        val bean = any()
        configure(bean, "idle_session_check_interval", "30s")
        configure(bean, "idle_session_timeout", "1m30s")
        configure(bean, "min_idle_session", "3")
        configure(bean, "client_metadata", "SakuraBox synthetic test")
        configure(bean, "tls.handshake_timeout", "5s")
        val restored = KryoConverters.anyTLSDeserialize(KryoConverters.serialize(bean))
        val cloned = restored.clone()
        assertEquals(bean.protocolOptionsJson, cloned.protocolOptionsJson)
        assertEquals(bean.password, cloned.password)
        assertEquals(bean.protocolOptionsJson, parseUniversal(cloned.toUniversalLink()).protocolOptionsJson)
        val outbound = buildSingBoxOutboundAnyTLSBean(cloned).also { ProtocolOptions.applyTo(it, cloned) }
        val map = outbound.asMap()
        assertEquals(3L, (map["min_idle_session"] as Number).toLong())
        assertEquals("1m30s", map["idle_session_timeout"])
        assertEquals("5s", (map["tls"] as Map<*, *>)["handshake_timeout"])
        assertEquals("example.invalid", (map["tls"] as Map<*, *>)["server_name"])
        fixture("anytls-idle", cloned, outbound)
    }

    @Test fun oldVersionZeroAnyTLSBlobIsReadableWithoutChangingNodeValues() {
        val bean = any().apply { name = "legacy"; protocolOptionsJson = "" }
        val stream = java.io.ByteArrayOutputStream()
        val output = com.esotericsoftware.kryo.io.ByteBufferOutput(stream)
        bean.serialize(output) // Unchanged protocol-specific version 0 fields.
        output.writeInt(1) // Actual old common extension format.
        output.writeString(bean.name); output.writeString(bean.customOutboundJson); output.writeString(bean.customConfigJson)
        output.flush(); output.close()
        val old = stream.toByteArray()
        val restored = KryoConverters.anyTLSDeserialize(old)
        assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(restored))
        assertEquals(bean.password, restored.password)
        assertEquals("legacy", restored.name)
        assertEquals("", restored.protocolOptionsJson)
    }

    @Test fun clearOnlyRemovesChosenOverrideAndPreservesCustomKeys() {
        val bean = any().apply { protocolOptionsJson = """{"tls":{"server_name":"override.invalid","custom_tls":{"key":1}},"custom_outbound":true}""" }
        configure(bean, "tls.handshake_timeout", "5s")
        configure(bean, "tls.handshake_timeout", "")
        val root = ProtocolOptions.parse(bean.protocolOptionsJson)
        assertEquals("override.invalid", ProtocolOptions.get(root, "tls.server_name")!!.asString)
        assertTrue(root["custom_outbound"].asBoolean)
        assertEquals(1, ProtocolOptions.get(root, "tls.custom_tls.key")!!.asInt)
        assertNull(ProtocolOptions.get(root, "tls.handshake_timeout"))
        configure(bean, "tls.handshake_timeout", "6s")
        assertEquals("6s", ProtocolOptions.get(ProtocolOptions.parse(bean.protocolOptionsJson), "tls.handshake_timeout")!!.asString)
    }

    @Test fun invalidDurationsAndIntegersAreRejectedBeforeSaving() {
        val duration = ProtocolOptions.fields(any()).first { it.path == "idle_session_timeout" }
        for (v in listOf("-1s", "NaN", "forever", "1d", "1s-secret", "99999999999999999999h", "0.0000000001s")) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.value(duration, v) }
        }
        for (v in listOf("1m30s", ".5s", "500ms", "1h2m3.5s", "1µs")) assertNotNull(ProtocolOptions.value(duration, v))
        val integer = ProtocolOptions.fields(any()).first { it.path == "min_idle_session" }
        for (v in listOf("-1", "3.5", "2147483648", "yes")) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.value(integer, v) }
        }
        assertEquals(0, ProtocolOptions.value(integer, "0")!!.asInt)
    }

    @Test fun explicitBooleanFalseIsPreservedAndEmptyIsOmitted() {
        val bean = TuicBean().apply { initializeDefaultValues() }
        val f = ProtocolOptions.fields(bean).first { it.path == "udp_over_stream" }
        assertFalse(ProtocolOptions.value(f, "false")!!.asBoolean)
        assertNull(ProtocolOptions.value(f, ""))
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.value(f, "yes") }
    }

    @Test fun malformedJsonAndScalarParentsAreNeverDiscarded() {
        assertThrows(Exception::class.java) { ProtocolOptions.parse("[]") }
        assertThrows(Exception::class.java) { ProtocolOptions.parse("{") }
        val root = ProtocolOptions.parse("""{"tls":false,"custom":1}""")
        val before = root.toString()
        val f = ProtocolOptions.fields(any()).first { it.path == "tls.handshake_timeout" }
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.set(root, f, "5s") }
        assertEquals(before, root.toString())
    }

    @Test fun pinLengthAndCertificateConflictsAreChecked() {
        val bean = any()
        val f = ProtocolOptions.fields(bean).first { it.path == "tls.certificate_public_key_sha256" }
        for (v in listOf("not-base64", Base64.getEncoder().encodeToString(ByteArray(31)))) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.value(f, v) }
        }
        configure(bean, f.path, Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        ProtocolOptions.validate(bean, ProtocolOptions.parse(bean.protocolOptionsJson))
        bean.certificates = "synthetic-ca"
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.validate(bean, ProtocolOptions.parse(bean.protocolOptionsJson)) }
        bean.certificates = ""
        fixture("anytls-pins", bean, buildSingBoxOutboundAnyTLSBean(bean))
    }

    @Test fun tlsVersionOrderingAndRealityEchConflictsAreRejected() {
        val bean = any()
        configure(bean, "tls.min_version", "1.3"); configure(bean, "tls.max_version", "1.2")
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.validate(bean, ProtocolOptions.parse(bean.protocolOptionsJson)) }
        val vless = VMessBean().apply { initializeDefaultValues(); alterId = -1; enableECH = true; realityPubKey = "synthetic" }
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.validate(vless, ProtocolOptions.parse("")) }
    }

    @Test fun anyTLSJsonImportRemainsEditableWithoutLosingExtraSettings() {
        val source = """{"type":"anytls","tag":"synthetic","server":"127.0.0.1","server_port":443,"password":"synthetic","idle_session_timeout":"60s","min_idle_session":2,"tls":{"enabled":true,"server_name":"example.invalid","handshake_timeout":"5s","utls":{"enabled":true,"fingerprint":"chrome"}}}"""
        val bean = importAnyTLSOutbound(source)!!
        assertEquals("60s", ProtocolOptions.get(ProtocolOptions.parse(bean.protocolOptionsJson), "idle_session_timeout")!!.asString)
        assertEquals("chrome", bean.utlsFingerprint)
        bean.serverPort = 8443; bean.sni = "changed.invalid"
        val copy = bean.clone()
        val result = buildSingBoxOutboundAnyTLSBean(copy).also { ProtocolOptions.applyTo(it, copy) }.asMap()
        assertEquals(8443L, (result["server_port"] as Number).toLong())
        assertEquals("changed.invalid", (result["tls"] as Map<*, *>)["server_name"])
        assertEquals("60s", result["idle_session_timeout"])
        fixture("anytls-import", copy, buildSingBoxOutboundAnyTLSBean(copy))
    }

    @Test fun explicitDisabledAndDynamicEchAreRetainedOnImport() {
        for (ech in listOf("""{"enabled":false}""", """{"enabled":true,"query_server_name":"example.invalid"}""", """{"enabled":true,"config":[]}""")) {
            val bean = importAnyTLSOutbound("""{"type":"anytls","server":"127.0.0.1","server_port":443,"password":"synthetic","tls":{"enabled":true,"ech":$ech}}""")!!
            assertEquals(com.google.gson.JsonParser.parseString(ech), ProtocolOptions.get(ProtocolOptions.parse(bean.protocolOptionsJson), "tls.ech"))
        }
        assertNull(importAnyTLSOutbound("""{"type":"anytls","tls":{"enabled":false}}"""))
    }

    @Test fun advancedSharingUsesExistingUniversalFormatInsteadOfInventedUriParameters() {
        val bean = any()
        configure(bean, "min_idle_session", "2")
        val entity = ProxyEntity().putBean(bean)
        assertFalse(entity.haveStandardLink())
        val link = entity.toStdLink()
        assertTrue(link.startsWith("sn://"))
        assertEquals(bean.protocolOptionsJson, parseUniversal(link).protocolOptionsJson)
    }

    @Test fun hysteria2AlpnIsNoLongerIgnoredAndDefaultsRemainH3() {
        val bean = HysteriaBean().apply { initializeDefaultValues(); sni = "example.invalid"; authPayload = "synthetic" }
        assertEquals(listOf("h3"), (buildSingBoxOutboundHysteriaBean(bean, false).asMap()["tls"] as Map<*, *>)["alpn"])
        bean.alpn = "synthetic-h3"
        configure(bean, "hop_interval_max", "30s")
        val out = buildSingBoxOutboundHysteriaBean(bean, false)
        assertEquals(listOf("synthetic-h3"), (out.asMap()["tls"] as Map<*, *>)["alpn"])
        fixture("hysteria2", bean, out)
    }

    @Test fun tuicUdpOverStreamConflictDoesNotSilentlyOverrideRelay() {
        val bean = TuicBean().apply { initializeDefaultValues(); uuid = "00000000-0000-4000-8000-000000000001"; token = "synthetic"; sni = "example.invalid" }
        configure(bean, "udp_over_stream", "true"); configure(bean, "heartbeat", "10s")
        ProtocolOptions.validate(bean, ProtocolOptions.parse(bean.protocolOptionsJson))
        bean.udpRelayMode = "quic"
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.validate(bean, ProtocolOptions.parse(bean.protocolOptionsJson)) }
        bean.udpRelayMode = "native"
        val out = buildSingBoxOutboundTuicBean(bean, false)
        fixture("tuic", bean, out)
    }

    @Test fun vmessFlagsAndTlsSurviveFinalSerialization() {
        val bean = VMessBean().apply { initializeDefaultValues(); uuid="00000000-0000-4000-8000-000000000001"; security="tls"; sni="example.invalid" }
        configure(bean, "global_padding", "true"); configure(bean, "authenticated_length", "true")
        configure(bean, "tls.min_version", "1.2"); configure(bean, "tls.max_version", "1.3")
        fixture("vmess", bean, buildSingBoxOutboundStandardV2RayBean(bean, false))
    }

    @Test fun transportOptionsFollowSelectedTransportAndRemainSerializable() {
        val bean = VMessBean().apply { initializeDefaultValues(); uuid="00000000-0000-4000-8000-000000000001"; type="grpc"; security="tls"; sni="example.invalid" }
        configure(bean, "transport.idle_timeout", "30s"); configure(bean, "transport.ping_timeout", "10s")
        configure(bean, "transport.permit_without_stream", "true")
        fixture("vmess-grpc", bean, buildSingBoxOutboundStandardV2RayBean(bean, false))
        bean.type="ws"
        assertFalse(ProtocolOptions.fields(bean).any { it.path == "transport.ping_timeout" })
    }

    @Test fun geckoObfuscationRequiresPasswordAndConsistentPacketSizes() {
        val bean = HysteriaBean().apply { initializeDefaultValues(); sni="example.invalid"; authPayload="synthetic" }
        configure(bean,"obfs.type","gecko"); configure(bean,"obfs.min_packet_size","20"); configure(bean,"obfs.max_packet_size","80")
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.validate(bean,ProtocolOptions.parse(bean.protocolOptionsJson)) }
        bean.obfuscation="synthetic-obfs"
        ProtocolOptions.validate(bean,ProtocolOptions.parse(bean.protocolOptionsJson))
        fixture("hysteria2-gecko",bean,buildSingBoxOutboundHysteriaBean(bean,false))
        configure(bean,"obfs.max_packet_size","10")
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.validate(bean,ProtocolOptions.parse(bean.protocolOptionsJson)) }
    }

    @Test fun tlsCurvesUseOnlyValuesSupportedByLockedCore() {
        val bean=any()
        configure(bean,"tls.curve_preferences","X25519\nP256")
        configure(bean,"tls.min_version","1.2")
        fixture("anytls-curves",bean,buildSingBoxOutboundAnyTLSBean(bean))
        assertThrows(IllegalArgumentException::class.java) { configure(bean,"tls.curve_preferences","invented-curve") }
    }

    @Test fun metadataCannotInjectSettingsOrOverflowTheWireLength() {
        val bean=any()
        for (value in listOf("test\nversion=999", "test\u0000value", "x".repeat(1025), "樱".repeat(400))) {
            assertThrows(IllegalArgumentException::class.java) { configure(bean,"client_metadata",value) }
        }
        configure(bean,"client_metadata","SakuraBox synthetic")
        assertEquals("SakuraBox synthetic",ProtocolOptions.get(ProtocolOptions.parse(bean.protocolOptionsJson),"client_metadata")!!.asString)
    }

    @Test fun explicitManualOverrideRetainsPriorityOverImportedProtocolOptions() {
        val bean=any()
        configure(bean,"min_idle_session","2")
        bean.customOutboundJson="""{"min_idle_session":7,"tls":{"server_name":"manual.invalid"}}"""
        val outbound=buildSingBoxOutboundAnyTLSBean(bean)
        ProtocolOptions.applyTo(outbound,bean)
        outbound._hack_custom_config=bean.customOutboundJson
        assertEquals(7L,(outbound.asMap()["min_idle_session"] as Number).toLong())
        assertEquals("manual.invalid",(outbound.asMap()["tls"] as Map<*,*>)["server_name"])
        val restored=bean.clone()
        assertEquals(bean.protocolOptionsJson,restored.protocolOptionsJson)
        assertEquals(bean.customOutboundJson,restored.customOutboundJson)
    }

    @Test fun subscriptionRefreshCanReplaceImportedOptionsWithoutErasingUserOverrides() {
        val old=importAnyTLSOutbound("""{"type":"anytls","server":"127.0.0.1","server_port":443,"password":"synthetic","idle_session_timeout":"30s","tls":{"enabled":true}}""")!!
        old.customOutboundJson="""{"min_idle_session":9}"""
        val updated=importAnyTLSOutbound("""{"type":"anytls","server":"127.0.0.1","server_port":443,"password":"synthetic","idle_session_timeout":"60s","tls":{"enabled":true}}""")!!
        updated.customOutboundJson=old.customOutboundJson // Actual RawUpdater policy, unchanged.
        assertEquals("60s",ProtocolOptions.get(ProtocolOptions.parse(updated.protocolOptionsJson),"idle_session_timeout")!!.asString)
        assertEquals(9,ProtocolOptions.parse(updated.customOutboundJson)["min_idle_session"].asInt)
        assertNotEquals(old,updated)
    }

    @Test fun remainingNativeProtocolsPreserveNewFieldsInFinalJson() {
        val ss=ShadowsocksBean().apply { initializeDefaultValues(); method="aes-128-gcm"; password="synthetic-test-password" }
        configure(ss,"multiplex.enabled","true");configure(ss,"multiplex.protocol","smux");configure(ss,"multiplex.max_connections","2")
        fixture("shadowsocks-mux",ss,buildSingBoxOutboundShadowsocksBean(ss))
        val ssh=SSHBean().apply { initializeDefaultValues(); password="synthetic-test-password" }
        configure(ssh,"host_key_algorithms","ssh-ed25519");configure(ssh,"client_version","SSH-2.0-SakuraBoxSynthetic")
        fixture("ssh",ssh,buildSingBoxOutboundSSHBean(ssh))
        val wg=WireGuardBean().apply { initializeDefaultValues(); serverAddress="127.0.0.1"; serverPort=51820; localAddress="10.0.0.2/32"; privateKey="BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=";peerPublicKey="CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg=" }
        configure(wg,"workers","2");configure(wg,"udp_timeout","1m")
        fixture("wireguard",wg,buildSingBoxEndpointWireguardBean(wg))
    }

    @Test fun legacyCommonExtensionReadsAcrossExistingProtocolBeans() {
        val beans=listOf<AbstractBean>(any(), VMessBean(), io.nekohasekai.sagernet.fmt.trojan.TrojanBean(),
            io.nekohasekai.sagernet.fmt.http.HttpBean(), io.nekohasekai.sagernet.fmt.socks.SOCKSBean(),
            HysteriaBean(), TuicBean(), ShadowsocksBean(), SSHBean(), WireGuardBean(),
            moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean(), moe.matsuri.nb4a.proxy.config.ConfigBean())
        for (bean in beans) {
            bean.initializeDefaultValues(); bean.name="legacy-synthetic"; bean.customOutboundJson="""{"connect_timeout":"15s"}"""
            val stream=java.io.ByteArrayOutputStream()
            val output=com.esotericsoftware.kryo.io.ByteBufferOutput(stream)
            bean.serialize(output);output.writeInt(1);output.writeString(bean.name)
            output.writeString(bean.customOutboundJson);output.writeString(bean.customConfigJson);output.flush();output.close()
            val restored=KryoConverters.deserialize(bean.javaClass.getDeclaredConstructor().newInstance(),stream.toByteArray())
            assertEquals(bean.name,restored.name);assertEquals(bean.customOutboundJson,restored.customOutboundJson)
            assertEquals("",restored.protocolOptionsJson)
            assertArrayEquals(KryoConverters.serialize(bean),KryoConverters.serialize(restored))
        }
    }

    @Test fun nonRepresentableAnyTLSJsonIsNotSilentlyCoercedIntoDifferentNodeValues() {
        for (port in listOf("443.5", "\"443\"", "0", "65536")) {
            assertNull(importAnyTLSOutbound("""{"type":"anytls","server":"127.0.0.1","server_port":$port,"tls":{"enabled":true}}"""))
        }
        assertNull(importAnyTLSOutbound("""{"type":"anytls","server":"127.0.0.1","server_port":443,"tls":{"enabled":true,"alpn":["single,protocol"]}}"""))
    }

    @Test fun nativeOptionsAreNeverAppliedToTheExternalPluginLoopbackSocket() {
        val bean=HysteriaBean().apply { initializeDefaultValues() }
        configure(bean,"connect_timeout","5s")
        val outbound=SingBoxOptions.Outbound_SocksOptions().apply { type="socks"; server="127.0.0.1";server_port=1080 }
        assertThrows(IllegalArgumentException::class.java) { ProtocolOptions.applyTo(outbound,bean,true) }
        assertTrue(outbound._hack_config_map.isEmpty())
        val ss=ShadowsocksBean().apply { initializeDefaultValues(); plugin="obfs-local;obfs=http" }
        assertTrue(ProtocolOptions.fields(ss).any { it.path=="multiplex.enabled" })
    }
}
