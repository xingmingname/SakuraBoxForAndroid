package io.nekohasekai.sagernet.fmt

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.nakis.NakisBean
import io.nekohasekai.sagernet.fmt.nakis.NakisOptions
import io.nekohasekai.sagernet.fmt.nakis.NakisShareLink
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import io.nekohasekai.sagernet.group.SubscriptionParser
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLEncoder

class NakisShareLinkTest {
    private val uuid = "6c1c4a52-3b8e-4d9a-9a52-0d4f5f0f7a11"
    private val prefix = "nakis://$uuid@nakis.test:443"
    private val fixtures: List<JsonObject> by lazy {
        requireNotNull(javaClass.getResourceAsStream("/nakis/upstream-links.json")).bufferedReader().use { reader ->
            JsonParser.parseReader(reader).asJsonArray.map { it.asJsonObject }
        }
    }

    private fun outbound(bean: NakisBean): JsonObject {
        val dto = NakisOptions.buildOutbound(bean)
        ProtocolOptions.applyTo(dto, bean)
        return Gson().toJsonTree(dto.asMap()).asJsonObject
    }

    @Test fun officialCliLinksSurviveNativeEntityAndInternalShare() {
        val tlsDefaults = fixtures.single { it["id"].asString == "tls" }.getAsJsonObject("client")
        val keys = listOf("race_count", "pool_lo", "pool_hi", "pool_max_uses", "pool_max_age", "keepalive",
            "congestion", "send_pack", "send_tail", "send_quiet", "shape_wait", "tls")
        for (fixture in fixtures) {
            val id = fixture["id"].asString
            val bean = NakisShareLink.parse(fixture["link"].asString)
            assertEquals(id, fixture["name"].asString, bean.name)
            val entity = ProxyEntity(groupId = 7, userOrder = 3).putBean(bean)
            assertEquals(ProxyEntity.TYPE_NAKIS, entity.type)
            assertFalse(entity.needExternal())
            val restored = KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(entity)).requireBean() as NakisBean
            assertEquals(bean.name, restored.name)
            assertEquals(bean.protocolOptionsJson, restored.protocolOptionsJson)
            assertEquals(bean.protocolOptionsJson, (parseUniversal(restored.toUniversalLink()) as NakisBean).protocolOptionsJson)
            assertTrue(entity.haveStandardLink())
            val reimported = NakisShareLink.parse(entity.toStdLink())
            assertEquals(restored.name, reimported.name)
            assertEquals(outbound(restored), outbound(reimported))
            val config = outbound(restored)
            val expected = fixture.getAsJsonObject("client")
            val addresses = expected["server"].asString.split(',')
            val address = config["server"].asString
            assertEquals(id, addresses.first(), "${if (':' in address) "[$address]" else address}:${config["server_port"].asInt}")
            val alternates = config.getAsJsonArray("server_alternates")?.map { it.asString }.orEmpty()
            assertEquals(id, addresses.drop(1), alternates)
            assertEquals(id, "nakis", config["type"].asString)
            assertEquals(id, expected["uuid"], config["uuid"])
            for (key in keys) assertEquals("$id $key", expected[key], config[key] ?: tlsDefaults[key])
            for (key in listOf("sni", "ech_config")) assertEquals("$id $key", expected[key].asString, config[key]?.asString.orEmpty())
            for (key in listOf("tls_pins", "shape_up")) assertEquals("$id $key",
                expected[key].takeUnless { it.isJsonNull } ?: JsonArray(), config[key] ?: JsonArray())
            assertFalse(config.has("certificate_public_key_sha256"))
            assertFalse(NodeJson.enabled(restored))
            System.getenv("NEKO_CONFIG_FIXTURES")?.let {
                val out = File(it, "nakis-uri/$id.json")
                out.parentFile?.mkdirs()
                out.writeText(config.toString())
                File(out.parentFile, "$id.uri").writeText(entity.toStdLink())
            }
        }
    }

    @Test fun officialV1ExportRetainsZeroAndFallsBackForNewFields() {
        val bean = NakisShareLink.parse("$prefix?tls=0&uses=0&tail=0&quiet=0#A%2BB%20%E4%B8%AD")
        val entity = ProxyEntity().putBean(bean)
        assertTrue(entity.haveStandardLink())
        assertEquals(outbound(bean), outbound(NakisShareLink.parse(entity.toStdLink())))
        for (field in listOf("udp_jitter", "half_close_timeout", "dial_timeout", "handshake_timeout")) {
            val options = ProtocolOptions.parse(bean.protocolOptionsJson)
            options.addProperty(field, "1s")
            val changed = bean.clone().apply { protocolOptionsJson = options.toString() }
            val unrepresentable = ProxyEntity().putBean(changed)
            assertFalse(unrepresentable.haveStandardLink())
            assertEquals(options, ProtocolOptions.parse((parseUniversal(unrepresentable.toStdLink()) as NakisBean).protocolOptionsJson))
        }
        val raw = """{"type":"nakis","server":"nakis.test","server_port":443,"udp_jitter":"0s"}"""
        NodeJson.set(bean, NodeJson.OUTBOUND, raw)
        assertFalse(entity.haveStandardLink())
        assertEquals(raw, NodeJson.saved(parseUniversal(entity.toStdLink()) as NakisBean, NodeJson.OUTBOUND))
    }

    @Test fun officialPoolRangeAndEquivalentClampingSurviveShare() {
        val bean = NakisShareLink.parse("$prefix?tls=0&pool=1024-4096&race=12&age=3h&ka=2h")
        val opts = ProtocolOptions.parse(bean.protocolOptionsJson)
        assertEquals(1024, opts["pool_lo"].asInt)
        assertEquals(4096, opts["pool_hi"].asInt)
        assertEquals("3h", opts["pool_max_age"].asString)
        opts.addProperty("race_count", 99)
        bean.protocolOptionsJson = opts.toString()
        val exported = requireNotNull(NakisShareLink.export(bean))
        val result = ProtocolOptions.parse(NakisShareLink.parse(exported).protocolOptionsJson)
        assertEquals(12, result["race_count"].asInt)
        assertEquals(4096, result["pool_hi"].asInt)
    }

    @Test fun longOfficialLinkUsesSharedImportAndNodeStorageLimits() {
        val nodeName = "a".repeat(140 * 1024)
        val bean = NakisShareLink.parse("$prefix?tls=0#$nodeName")
        assertEquals(nodeName, bean.name)
        assertEquals(nodeName, NakisShareLink.parse(requireNotNull(NakisShareLink.export(bean))).name)
    }

    @Test fun officialCliLinkValuesCanBeOpenedEditedAndSavedWithoutLosingOtherOptions() {
        val lists = setOf("tls_pins", "server_alternates")
        val integers = setOf("race_count", "pool_lo", "pool_hi", "pool_max_uses", "send_pack", "send_tail", "send_quiet")
        val durations = setOf("pool_max_age", "keepalive", "shape_wait")
        for (fixture in fixtures) {
            val bean = NakisShareLink.parse(fixture["link"].asString)
            val original = ProtocolOptions.parse(bean.protocolOptionsJson)
            for ((key, value) in original.entrySet()) {
                val kind = when (key) {
                    "tls" -> ProtocolOptions.Kind.BOOLEAN
                    in lists -> ProtocolOptions.Kind.LIST
                    in integers -> ProtocolOptions.Kind.INTEGER
                    in durations -> ProtocolOptions.Kind.DURATION
                    else -> ProtocolOptions.Kind.TEXT
                }
                assertEquals(key, value, NakisOptions.parseValue(key, kind, NakisOptions.editorText(original, key, kind)))
            }
            assertEquals("", NakisOptions.editorText(original, "dial_timeout", ProtocolOptions.Kind.DURATION))
            val edited = original.deepCopy()
            edited.add("send_quiet", NakisOptions.parseValue("send_quiet", ProtocolOptions.Kind.INTEGER, "0"))
            edited.add("dial_timeout", NakisOptions.parseValue("dial_timeout", ProtocolOptions.Kind.DURATION, "0"))
            edited.add("tls", NakisOptions.parseValue("tls", ProtocolOptions.Kind.BOOLEAN, "false"))
            bean.protocolOptionsJson = edited.toString()
            val restored = bean.clone()
            assertEquals(edited, ProtocolOptions.parse(restored.protocolOptionsJson))
            val config = outbound(restored)
            val inactive = setOf("sni", "tls_pins", "ech_config") +
                if ((edited.getAsJsonArray("shape_up")?.size() ?: 0) > 0) emptySet() else setOf("shape_wait")
            for ((key, value) in edited.entrySet()) {
                if (key in inactive) assertFalse(key, config.has(key))
                else assertEquals(key, value, config[key])
            }

            if (original["tls"]?.asBoolean != false) {
                val restoredOptions = ProtocolOptions.parse(restored.protocolOptionsJson)
                if (original.has("tls")) restoredOptions.add("tls", original["tls"])
                else restoredOptions.remove("tls")
                restored.protocolOptionsJson = restoredOptions.toString()
                val tlsConfig = outbound(restored)
                for (key in listOf("sni", "tls_pins", "ech_config")) {
                    assertEquals(key, original[key], tlsConfig[key])
                }
            }
        }
    }

    @Test fun upstreamDefaultQueryAndEncodingRules() {
        val bean = NakisShareLink.parse("NAKIS://$uuid@nakis.test:443?future=value&sni=first&sni=second&v=%2B1&tls=false#A+B%20%E4%B8%AD")
        val options = ProtocolOptions.parse(bean.protocolOptionsJson)
        assertEquals("A+B 中", bean.name)
        assertEquals("first", options["sni"].asString)
        assertTrue(options["tls"].asBoolean) // Only tls=0 disables TLS.
        assertFalse(options.has("future"))
        val defaults = ProtocolOptions.parse(NakisShareLink.parse("$prefix?sni=nakis.test&v=").protocolOptionsJson)
        assertEquals(setOf("uuid", "sni"), defaults.keySet())
        val values = ProtocolOptions.parse(NakisShareLink.parse(
            "$prefix?tls=0&race=-1&pool=10000--5&uses=0&pack=1&tail=9999&quiet=0&age=-5s&ka=0&wait=.4ns"
        ).protocolOptionsJson)
        assertEquals(2, values["race_count"].asInt)
        assertEquals(1024, values["pool_lo"].asInt)
        assertEquals(1025, values["pool_hi"].asInt)
        assertEquals(0, values["pool_max_uses"].asInt)
        assertEquals(1024, values["send_pack"].asInt)
        assertEquals(1023, values["send_tail"].asInt)
        assertEquals(0, values["send_quiet"].asInt)
        assertEquals("15s", values["pool_max_age"].asString)
        assertEquals("15s", values["keepalive"].asString)
        assertEquals("20ms", values["shape_wait"].asString)
        val cliEch = fixtures.single { it["id"].asString == "ech" }.getAsJsonObject("client")["ech_config"].asString
        val standardUnpadded = cliEch.trimEnd('=')
        val testEncoding = "AAz+DQAI+//+//vvAQI="
        for ((raw, expected) in listOf(
            URLEncoder.encode(cliEch, "UTF-8") to cliEch,
            standardUnpadded to cliEch,
            Base64.encodeToString(Base64.decode(cliEch, Base64.DEFAULT), Base64.URL_SAFE or Base64.NO_WRAP) to cliEch,
            testEncoding to testEncoding,
        )) {
            val link = "$prefix?sni=nakis.test&ech=$raw"
            assertEquals(JsonPrimitive(expected), ProtocolOptions.parse(NakisShareLink.parse(link).protocolOptionsJson)["ech_config"])
        }
    }

    @Test fun upstreamHostNormalizationPreservesUuidAndDeduplicatesEndpoints() {
        val upper = uuid.uppercase(java.util.Locale.ROOT)
        val alt = URLEncoder.encode("primary.test:443,, [2001:0DB8:0:0:0:0:0:1]:008443, " +
            "[2001:db8::1]:8443, [::ffff:C000:0201]:443, [FE80:0::1%Eth0]:8443", "UTF-8")
        val bean = NakisShareLink.parse("nakis://$upper@PRIMARY.TEST.:00443?sni=%20FRONT.TEST.%20&alt=$alt#Node")
        val config = outbound(bean)
        assertEquals("primary.test", config["server"].asString)
        assertEquals(443, config["server_port"].asInt)
        assertEquals(upper, config["uuid"].asString)
        assertEquals("front.test", config["sni"].asString)
        assertEquals("front.test", outbound(NakisShareLink.parse("$prefix?sni=%C2%85FRONT.TEST.%C2%85"))["sni"].asString)
        assertEquals(listOf("[2001:db8::1]:8443", "[::ffff:192.0.2.1]:443", "[fe80::1%Eth0]:8443"),
            config.getAsJsonArray("server_alternates").map { it.asString })
        assertEquals("fe80::1%Eth0", NakisShareLink.parse("nakis://$uuid@[FE80::1%25Eth0]:443?tls=0").serverAddress)
        assertFalse(outbound(NakisShareLink.parse("$prefix?tls=0&alt=NAKIS.TEST.:0443")).has("server_alternates"))
        val fixture = requireNotNull(javaClass.getResourceAsStream("/nakis/upstream-normalized-link.json"))
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val fromCli = NakisShareLink.parse(fixture["link"].asString)
        assertEquals(fixture["name"].asString, fromCli.name)
        val expected = fixture.getAsJsonObject("client")
        val generated = outbound(fromCli)
        val endpoints = listOf("${generated["server"].asString}:${generated["server_port"].asInt}") +
            generated.getAsJsonArray("server_alternates").map { it.asString }
        assertEquals(expected["server"].asString, endpoints.joinToString(","))
        assertEquals(expected["sni"], generated["sni"])
        assertEquals(expected["uuid"], generated["uuid"])
    }

    @Test fun invalidUriIsRejectedWithoutCredentialInErrors() {
        val bad = listOf(
            "nakis://invalid@nakis.test:443?tls=0", "nakis://$uuid@nakis.test?tls=0",
            "nakis://$uuid@nakis.test:0?tls=0", "nakis://$uuid@nakis.test:65536?tls=0",
            "nakis://$uuid@[2001:db8::1:443?tls=0", "$prefix", "$prefix?tls=0&pin=bad",
            "$prefix?sni=n&ech=%25bad", "$prefix?ech=AAz-DQAI-__-__vvAQI",
            "$prefix?pin=abcdef", "$prefix?tls=0&alt=missing-port", "$prefix?tls=0&race=x",
            "$prefix?tls=0&race=9223372036854775808", "$prefix?tls=0&shape=10-2",
            "$prefix?tls=0&pool=1-2,3-4", "$prefix?tls=0&age=5d", "$prefix?tls=0&wait=2562048h",
            "$prefix?v=2&tls=0", "$prefix?v=0&tls=0", "$prefix?v=x&tls=0",
            "$prefix?tls=0&alt=other.test:0", "$prefix?tls=0&alt=other.test:65536",
            "$prefix?tls=0&alt=other.test:%2B443", "$prefix?tls=0&alt=other..test:443",
            "$prefix?sni=%1Cfront.test", "$prefix?sni=%EF%BC%92%EF%BC%90%EF%BC%90%EF%BC%91:db8::1",
            "$prefix?sni=bad%2Fhost", "$prefix?sni=fe80%3A%3A1%25Eth0", "$prefix?sni=%20",
            "nakis://$uuid@bad..host:443?tls=0",
            "$prefix?tls=0&unknown=invalid%00%zz", "$prefix?tls=0&unknown=1;2", "$prefix?tls=0#%ZZ",
        )
        for (link in bad) {
            val error = assertThrows(IllegalArgumentException::class.java) { NakisShareLink.parse(link) }
            assertFalse(error.message.orEmpty().contains(uuid))
            assertNull(error.cause)
        }
    }

    @Test fun existingRawAndMixedImportPathsStillWork() = runBlocking {
        val link = fixtures.first()["link"].asString
        val sudoku = "sudoku://eyJoIjoiMTI3LjAuMC4xIiwicCI6NDQzLCJrIjoic3ludGhldGljIiwibiI6IlN1ZG9rdSJ9"
        val anytls = "anytls://synthetic@127.0.0.1:443#AnyTLS"
        val vless = "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?type=tcp&security=none#VLESS"
        assertTrue(SubscriptionParser.parseRaw(link)!!.single() is NakisBean)
        val mixed = "$link\n$prefix?v=2&tls=0\n$sudoku\n$anytls"
        for (beans in listOf(parseProxies(mixed), SubscriptionParser.parseRaw(mixed)!!)) {
            assertEquals(listOf(NakisBean::class.java, SudokuBean::class.java, AnyTLSBean::class.java),
                beans.map { it.javaClass })
        }
        // The legacy V2Ray dispatcher eagerly initializes Android Socket reflection
        // on the desktop JVM. Exercise its standard URL decoder here.
        val vlessBean = VMessBean().apply {
            alterId = -1
            parseDuckSoft(vless.replaceFirst("vless://", "https://").toHttpUrl())
            initializeDefaultValues()
        }
        assertTrue(vlessBean.isVLESS)
        assertEquals("VLESS", vlessBean.name)
    }
}

