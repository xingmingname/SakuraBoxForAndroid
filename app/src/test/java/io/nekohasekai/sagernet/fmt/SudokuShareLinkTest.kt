package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import io.nekohasekai.sagernet.fmt.sudoku.SudokuShareLink
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.ImportLimits
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

class SudokuShareLinkTest {
    private val minimal = """{"h":"example.com","p":443,"k":"synthetic-key"}"""
    private fun uri(json: String, standard: Boolean = false, padded: Boolean = false): String {
        val encoder = if (standard) Base64.getEncoder() else Base64.getUrlEncoder()
        return "sudoku://" + (if (padded) encoder else encoder.withoutPadding()).encodeToString(json.toByteArray(Charsets.UTF_8))
    }
    private fun parse(json: String = minimal) = SudokuShareLink.parse(uri(json))
    private fun payload(link: String) = NodeJson.parse(String(Base64.getUrlDecoder().decode(link.substringAfter("://")), Charsets.UTF_8))
    private fun exported(bean: SudokuBean) = SudokuShareLink.export(bean) ?: error("Expected representable node")
    private fun changed(bean: SudokuBean, path: String, text: String?) = bean.clone().apply {
        protocolOptionsJson = SudokuOptions.set(SudokuOptions.root(this), SudokuOptions.fields.single { it.path == path }, text).toString()
    }
    private fun fixture(name: String, bean: SudokuBean) {
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory ->
            val dir = File(directory, "sudoku-uri"); dir.mkdirs()
            val outbound = SudokuOptions.buildOutbound(bean)
            ProtocolOptions.applyTo(outbound, bean)
            File(dir, "$name.json").writeText(com.google.gson.Gson().toJson(outbound.asMap()))
            File(dir, "$name.txt").writeText(exported(bean))
        }
    }

    @Test fun minimalOfficialLinkHasOfficialDefaultsRatherThanOutboundDefaults() {
        val bean = parse()
        val root = SudokuOptions.root(bean)
        assertEquals("none", root["aead"].asString)
        assertEquals("prefer_entropy", root["ascii"].asString)
        assertEquals(5, root["padding_min"].asInt); assertEquals(15, root["padding_max"].asInt)
        assertTrue(root["enable_pure_downlink"].asBoolean)
        assertNull(root["httpmask"]); assertNull(bean.shareLinkLocalPort)
        assertEquals(NodeJson.NONE, bean.jsonMode)
        assertEquals(ProxyEntity.TYPE_SUDOKU, ProxyEntity().putBean(bean).type)
        assertTrue(ProxyEntity().putBean(bean).haveStandardLink())
        assertEquals(root, SudokuOptions.root(SudokuShareLink.parse(exported(bean))))
        fixture("minimal", bean)
    }

    @Test fun originalOfficialGoBuilderVectorsMatchNativeOutboundFields() {
        val document = javaClass.getResourceAsStream("/sudoku/upstream-shortlinks.json")!!.bufferedReader().use { NodeJson.parse(it.readText()) }
        assertEquals(7, document.getAsJsonArray("cases").size())
        for (row in document.getAsJsonArray("cases")) {
            val input = row.asJsonObject
            val expected = input.getAsJsonObject("config")
            val bean = SudokuShareLink.parse(input["link"].asString)
            val options = SudokuOptions.root(bean)
            assertEquals(expected["key"], options["key"]); assertEquals(expected["aead"], options["aead"])
            assertEquals(expected["ascii"], options["ascii"])
            for (field in listOf("padding_min", "padding_max", "enable_pure_downlink")) assertEquals(expected[field], options[field])
            expected["name"]?.let { assertEquals(it.asString, bean.name) }
            val http = options.getAsJsonObject("httpmask") ?: com.google.gson.JsonObject()
            val expectedHttp = expected.getAsJsonObject("httpmask")
            assertEquals(expectedHttp["disable"].asBoolean, http["disable"]?.asBoolean ?: false)
            assertEquals(expectedHttp["tls"].asBoolean, http["tls"]?.asBoolean ?: false)
            assertEquals(expectedHttp["mode"].asString, http["mode"]?.asString ?: "legacy")
            assertEquals(expectedHttp["multiplex"].asString, http["multiplex"]?.asString ?: "off")
            for (field in listOf("host", "path_root")) assertEquals(expectedHttp[field].asString, http[field]?.asString.orEmpty())
            assertEquals(options, SudokuOptions.root(SudokuShareLink.parse(exported(bean))))
        }
    }

    @Test fun allShortFieldsRoundTripThroughNativeStorageCopyAndSharing() {
        val bean = parse("""{"h":"2001:db8::1","p":8443,"n":"香港 日本 + /?#% 😀","k":"  Key+/%?#= 中文\\n  ","a":"up_ascii_down_entropy","e":"aes-128-gcm","m":1081,"x":true,"t":"xpxvvpvv","ts":["xpxvvpvv","vxpvxvvp"],"hd":false,"hm":"auto","ht":true,"hh":"cdn.example.com","hx":"on","hy":"root_01"}""")
        val original = KryoConverters.serialize(bean)
        val entity = ProxyEntity(groupId = 91, userOrder = 3).putBean(bean)
        val restored = KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(entity)).requireBean() as SudokuBean
        assertEquals(91L, entity.groupId); assertEquals(3L, entity.userOrder)
        assertEquals(1081, restored.shareLinkLocalPort)
        val link = entity.toStdLink()
        assertTrue(link.startsWith("sudoku://")); assertFalse(link.contains('#'))
        val next = SudokuShareLink.parse(link)
        assertEquals(bean.name, next.name); assertEquals(bean.serverAddress, next.serverAddress)
        assertEquals(bean.serverPort, next.serverPort)
        assertEquals(SudokuOptions.root(bean), SudokuOptions.root(next))
        assertArrayEquals(original, KryoConverters.serialize(bean))
        assertArrayEquals(original, KryoConverters.serialize(restored.clone()))
        fixture("all-fields", bean)
    }

    @Test fun keyIsRawJsonStringAndNeverDecodedTrimmedOrLowercased() {
        for (key in listOf("  MiXeD + / %2B %ZZ @ : # ? & = 中文  ", "line\nfeed\r\tend", "00".repeat(32),
            "01" + "00".repeat(31) + "02" + "00".repeat(31))) {
            val input = NodeJson.parse(minimal).apply { addProperty("k", key) }
            val bean = parse(input.toString())
            assertEquals(key, SudokuOptions.root(bean)["key"].asString)
            assertEquals(key, payload(exported(bean))["k"].asString)
            assertEquals(key, SudokuOptions.root(SudokuShareLink.parse(exported(bean)))["key"].asString)
        }
    }

    @Test fun officialAndroidBase64VariantsAndFragmentNamesAreAccepted() {
        val json = NodeJson.parse(minimal).apply { addProperty("k", "特殊😀\uFFFF") }.toString()
        for (standard in listOf(false, true)) for (padded in listOf(false, true)) {
            val link = uri(json, standard, padded).replace("sudoku://", "SUDOKU://")
            val bean = SudokuShareLink.parse("  $link#%E9%A6%99%E6%B8%AF%2001+%2B%2F%23%25  ")
            assertEquals("香港 01++/#%", bean.name)
            assertEquals("特殊😀\uFFFF", SudokuOptions.root(bean)["key"].asString)
            assertEquals(bean.name, SudokuShareLink.parse(exported(bean)).name)
        }
        assertEquals("内置名称", SudokuShareLink.parse(uri(NodeJson.parse(minimal).apply { addProperty("n", "内置名称") }.toString()) + "#fragment").name)
    }

    @Test fun localMixedPortIsRetainedButNeverAddedToCoreJson() {
        for (port in listOf(0, 1080, 10233, 65535)) {
            val bean = parse(NodeJson.parse(minimal).apply { addProperty("m", port) }.toString())
            assertEquals(port, bean.clone().shareLinkLocalPort)
            assertEquals(port, payload(exported(bean))["m"].asInt)
            assertFalse(SudokuOptions.outboundJson(bean).has("m"))
            assertFalse(SudokuOptions.outboundJson(bean).has("local_port"))
            assertEquals(SudokuOptions.root(bean), SudokuOptions.root(parse()))
        }
    }

    @Test fun previousVersionZeroDatabaseAndSnPayloadRemainByteCompatible() {
        // Produced by the pre-URI task's compiled SudokuBean, not this serializer.
        val bytes = javaClass.getResourceAsStream("/sudoku/legacy-v0.bin")!!.use { it.readBytes() }
        val restored = KryoConverters.sudokuDeserialize(bytes)!!
        assertEquals("legacy-v0", restored.name); assertEquals("example.com", restored.serverAddress)
        assertEquals(8443, restored.serverPort); assertNull(restored.shareLinkLocalPort)
        assertEquals("Legacy+Key", SudokuOptions.root(restored)["key"].asString)
        assertEquals(0, SudokuOptions.root(restored)["padding_min"].asInt)
        assertFalse(SudokuOptions.root(restored)["enable_pure_downlink"].asBoolean)
        assertArrayEquals(bytes, KryoConverters.serialize(restored))
        assertArrayEquals(bytes, KryoConverters.serialize(parseUniversal(restored.toUniversalLink())))
        assertArrayEquals(bytes, KryoConverters.serialize(restored.clone()))
    }

    @Test fun qrGenerationAndDecodingPreserveTheCompleteNativeLink() {
        val bean = parse(NodeJson.parse(minimal).apply { addProperty("n", "中文二维码 + /%?# 😀") }.toString())
        val link = exported(bean)
        val bits = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 640, 640)
        val pixels = IntArray(bits.width * bits.height) { index -> if (bits[index % bits.width, index / bits.width]) -0x1000000 else -1 }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bits.width, bits.height, pixels)))).text
        assertEquals(link, decoded)
        assertEquals(bean.name, SudokuShareLink.parse(decoded).name)
        assertEquals(SudokuOptions.root(bean), SudokuOptions.root(SudokuShareLink.parse(decoded)))
        val oversizedQr = exported(changed(bean, "key", "K".repeat(6000)))
        assertThrows(com.google.zxing.WriterException::class.java) {
            MultiFormatWriter().encode(oversizedQr, BarcodeFormat.QR_CODE, 640, 640)
        } // QRCodeDialog already reports this error; the clipboard link remains available.
    }

    @Test fun booleansEmptyTablesAndAllHttpMaskModesRetainTheirValues() {
        for (mode in listOf("legacy", "stream", "poll", "auto", "ws")) for (value in listOf(false, true)) {
            val json = NodeJson.parse(minimal).apply {
                addProperty("hm", mode); addProperty("x", value); addProperty("hd", value)
                addProperty("ht", false); addProperty("hh", ""); addProperty("hy", "")
                addProperty("hx", if (value) "on" else "off"); addProperty("t", "")
                add("ts", com.google.gson.JsonArray())
            }
            val bean = parse(json.toString()); val next = SudokuShareLink.parse(exported(bean))
            assertEquals(SudokuOptions.root(bean), SudokuOptions.root(next))
            assertEquals(json["x"], payload(exported(bean))["x"])
            assertFalse(SudokuOptions.root(next).getAsJsonObject("httpmask")["tls"].asBoolean)
            fixture("http-$mode-$value", bean)
        }
    }

    @Test fun asciiAeadAndRotationTablesMapToActualOptions() {
        for (ascii in SudokuOptions.asciiModes) for (encryption in listOf("none", "aes-128-gcm", "chacha20-poly1305")) {
            val json = NodeJson.parse(minimal).apply { addProperty("a", ascii); addProperty("e", encryption) }
            val bean = parse(json.toString())
            assertEquals(encryption, SudokuOptions.root(bean)["aead"].asString)
            assertEquals(SudokuOptions.root(bean), SudokuOptions.root(SudokuShareLink.parse(exported(bean))))
            fixture("$ascii-$encryption", bean)
        }
    }

    @Test fun unrepresentableOptionsAlwaysKeepSnAndNeverMutateSavedData() {
        val baseline = parse()
        val cases = listOf("padding_min" to "0", "padding_max" to "30", "padding_min" to null,
            "detour" to "other", "bind_interface" to "wlan0", "tcp_fast_open" to "false",
            "http_mask_mode" to "ws", "multiplex" to "on", "http_mask_strategy" to "random")
        for ((path, input) in cases) {
            val bean = changed(baseline, path, input)
            val original = KryoConverters.serialize(bean)
            assertNull("Unrepresentable $path", SudokuShareLink.export(bean))
            val entity = ProxyEntity().putBean(bean)
            assertFalse(entity.haveStandardLink())
            assertTrue(entity.toStdLink().startsWith("sn://sudoku?"))
            assertArrayEquals(original, KryoConverters.serialize(parseUniversal(entity.toStdLink())))
            assertArrayEquals(original, KryoConverters.serialize(bean))
        }
        val ordinary = SudokuBean().apply { initializeDefaultValues(); protocolOptionsJson = """{"key":"key"}""" }
        assertNull(SudokuShareLink.export(ordinary)) // Ordinary core padding is 10/30, not URI 5/15.
    }

    @Test fun unknownNullAndConflictingLegacyFieldsCannotBeSilentlyLostOnExport() {
        for (extra in listOf("""{"future":true}""", """{"httpmask":null}""", """{"custom_table":null}""",
            """{"httpmask":{"future":false}}""", """{"httpmask":{"mode":"auto"},"http_mask_mode":"ws"}""")) {
            val bean = parse().apply {
                val root = SudokuOptions.root(this)
                for ((key, value) in NodeJson.parse(extra).entrySet()) root.add(key, value)
                protocolOptionsJson = root.toString()
            }
            assertNull(SudokuShareLink.export(bean))
            assertEquals(SudokuOptions.root(bean), SudokuOptions.root(parseUniversal(ProxyEntity().putBean(bean).toStdLink()) as SudokuBean))
        }
        assertNull(SudokuShareLink.export(parse().apply { protocolOptionsJson = "{" }))
    }

    @Test fun activeAndDormantJsonDocumentsUseExistingUniversalSharing() {
        for (mode in listOf(NodeJson.NONE, NodeJson.OUTBOUND, NodeJson.FULL, NodeJson.LEGACY)) {
            val bean = parse().apply {
                jsonMode = mode; customOutboundJson = """{"type":"sudoku","key":"json-key"}"""
                customConfigJson = """{"outbounds":[{"type":"direct"}]}"""
            }
            val before = KryoConverters.serialize(bean)
            assertFalse(ProxyEntity().putBean(bean).haveStandardLink())
            assertNull(SudokuShareLink.export(bean))
            assertArrayEquals(before, KryoConverters.serialize(parseUniversal(ProxyEntity().putBean(bean).toStdLink())))
            val edited = changed(bean, "key", "ui-key")
            assertEquals(bean.customOutboundJson, edited.customOutboundJson)
            assertEquals(bean.customConfigJson, edited.customConfigJson); assertEquals(mode, edited.jsonMode)
        }
    }

    @Test fun unknownPayloadFieldsAreRejectedRatherThanCreatingPartialNodes() {
        for (field in listOf("padding_min", "server", "inbounds", "future_flag")) {
            val json = NodeJson.parse(minimal).apply { addProperty(field, "unknown") }
            assertThrows(IllegalArgumentException::class.java) { parse(json.toString()) }
        }
    }

    @Test fun malformedEncodingTypesAndMissingFieldsFailSafely() {
        val badLinks = listOf("sudoku://", "sudoku://%%%", "sudoku://abc=def", "sudoku://key@host:443?a=entropy",
            uri(minimal) + "?a=ascii", uri(minimal) + "#%ZZ", uri(minimal) + "#%FF", "sudoku://_w",
            uri("{}"), uri("[]"), uri(minimal + "{}"), uri(minimal.dropLast(1) + ",\"p\":80}"))
        for (link in badLinks) assertThrows("Invalid input", Exception::class.java) { SudokuShareLink.parse(link) }
        for ((field, value) in listOf("h" to "null", "p" to "0", "p" to "-1", "p" to "65536", "p" to "443.0",
            "p" to "\"443\"", "k" to "\"\"", "k" to "null", "x" to "0", "hd" to "\"false\"", "ts" to "[1]",
            "m" to "70000", "a" to "\"unknown\"", "e" to "\"unknown\"", "hm" to "\"unknown\"", "hx" to "true")) {
            val json = NodeJson.parse(minimal).apply { add(field, com.google.gson.JsonParser.parseString(value)) }
            assertThrows("Invalid $field", Exception::class.java) { parse(json.toString()) }
        }
        assertThrows(Exception::class.java) { parse(minimal.replace("synthetic-key", "\\uD800")) }
    }

    @Test fun boundedInputAndJsonDepthAreCheckedBeforeUnboundedParsing() {
        assertThrows(ImportLimits.InputLimitException::class.java) { SudokuShareLink.parse("sudoku://" + "A".repeat(SudokuShareLink.MAX_URI_LENGTH)) }
        val deep = """{"h":"x","p":443,"k":"k","unknown":""" + "[".repeat(150) + "0" + "]".repeat(150) + "}"
        assertThrows(ImportLimits.InputLimitException::class.java) { parse(deep) }
        assertThrows(IllegalArgumentException::class.java) { parse(minimal.dropLast(1) + ",\"p\":1e999999999}") }
        val bean = changed(parse(), "key", "A".repeat(SudokuShareLink.MAX_URI_LENGTH))
        assertNull(SudokuShareLink.export(bean))
        assertNull(SudokuShareLink.export(parse().apply { name = "\uD800" }))
        assertNull(SudokuShareLink.export(parse().apply { serverAddress = "\uD800" }))
    }

    @Test fun mixedTextImportsValidNativeNodesAndPreservesOtherProtocols() = runBlocking {
        val socksLink = "socks5://127.0.0.1:1081#%E6%97%A7%E8%8A%82%E7%82%B9"
        val sn = parse().apply { name = "SN Sudoku"; protocolOptionsJson = protocolOptionsJson.dropLast(1) + ",\"padding_min\":0}" }.toUniversalLink()
        val second = uri(NodeJson.parse(minimal).apply { addProperty("n", "中文 Sudoku") }.toString())
        val text = "节点列表：\n$socksLink\t${uri(minimal)}\n$second sudoku://invalid $sn"
        val beans = parseProxies(text)
        assertEquals(4, beans.size)
        assertTrue(beans[0] is SOCKSBean)
        assertTrue(beans.drop(1).all { it is SudokuBean })
        assertEquals("中文 Sudoku", beans[2].name)
        assertEquals(0, SudokuOptions.root(beans[3] as SudokuBean)["padding_min"].asInt)
        assertEquals(listOf("旧节点", "example.com", "中文 Sudoku", "SN Sudoku"), beans.map { it.name })
    }

    @Test fun clipboardScannerAndSubscriptionCommonParserAcceptPlainAndBase64Lists() = runBlocking {
        val plain = uri(minimal) + "\n" + uri(NodeJson.parse(minimal).apply { addProperty("p", 8443) }.toString())
        for (text in listOf(plain, Base64.getEncoder().encodeToString(plain.toByteArray()))) {
            val beans = RawUpdater.parseRaw(text)!!
            assertEquals(2, beans.size); assertTrue(beans.all { it is SudokuBean })
            assertEquals(listOf(443, 8443), beans.map { it.serverPort })
        }
    }

    @Test fun existingProtocolUriImportAndExportDispatchStillWork() = runBlocking {
        val links = listOf(
            "socks5://user:pass@127.0.0.1:1080",
            "http://user:pass@127.0.0.1:8080",
            "ss://aes-128-gcm:pass@127.0.0.1:8388",
            "hysteria2://pass@127.0.0.1:443?sni=example.com&insecure=1",
            "tuic://11111111-1111-4111-8111-111111111111:pass@127.0.0.1:443?congestion_control=cubic",
            "anytls://pass@127.0.0.1:443?sni=example.com"
        )
        for (link in links) {
            val bean = parseProxies(link).single()
            assertFalse(bean is SudokuBean)
            val entity = ProxyEntity().putBean(bean)
            assertTrue(entity.haveStandardLink())
            val generated = entity.toStdLink()
            assertFalse(generated.startsWith("sudoku://")); assertFalse(generated.startsWith("sn://"))
            val restored = parseProxies(generated).single()
            assertEquals(bean.javaClass, restored.javaClass); assertEquals(bean.serverAddress, restored.serverAddress)
            assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(parseUniversal(bean.toUniversalLink())))
        }
    }
}
