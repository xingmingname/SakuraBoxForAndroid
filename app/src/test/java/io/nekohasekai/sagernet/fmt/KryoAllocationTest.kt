package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.readStringList
import io.nekohasekai.sagernet.ktx.readStringSet
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class KryoAllocationTest {
    private fun bytes(write: (ByteBufferOutput) -> Unit): ByteArray = ByteArrayOutputStream().also { stream ->
        ByteBufferOutput(stream).use { write(it); it.flush() }
    }.toByteArray()

    private fun rejected(read: () -> Unit) {
        try { read(); fail("Malformed input must be rejected") } catch (_: KryoException) { }
    }

    @Test fun subscriptionShareAndStoredGroupKeepTheExistingWireFormat() {
        val subscription = SubscriptionBean().apply {
            initializeDefaultValues(); link = "https://example.invalid/subscription"; customUserAgent = "synthetic-test"
            forceResolve = true; deduplication = true
        }
        val group = ProxyGroup(id = 42, userOrder = 7, name = "synthetic subscription", type = GroupType.SUBSCRIPTION,
            subscription = subscription)
        for (share in listOf(false, true)) {
            group.export = share
            val original = KryoConverters.serialize(group)
            val decoded = KryoConverters.deserialize(ProxyGroup().apply { export = share },
                Util.zlibDecompress(Util.zlibCompress(original, 9)))
            assertEquals(group.name, decoded.name)
            assertEquals(subscription.link, decoded.subscription!!.link)
            assertArrayEquals(original, KryoConverters.serialize(decoded))
        }
    }

    @Test fun snNodeAndNestedEntityPreserveNormalFieldsAndJsonDocuments() {
        val bean = AnyTLSBean().apply {
            initializeDefaultValues(); name = "synthetic node"; serverAddress = "example.invalid"; serverPort = 443
            password = "synthetic test only"; sni = "tls.invalid"
            protocolOptionsJson = """{"tls":{"alpn":["h2"],"client_key":"synthetic multiline\nvalue"}}"""
            customOutboundJson = """{"type":"direct"}"""; customConfigJson = """{"outbounds":[{"type":"direct"}]}"""
        }
        for (link in listOf(bean.toUniversalLink(), "sn://anytls:" + Util.b64EncodeUrlSafe(KryoConverters.serialize(bean)))) {
            assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(parseUniversal(link)))
        }
        val entity = ProxyEntity(id = 12, uuid = "synthetic-id").putBean(bean)
        val decoded = KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(entity))
        assertArrayEquals(KryoConverters.serialize(entity), KryoConverters.serialize(decoded))
    }

    @Test fun historicalSubscriptionVersionZeroIsStillReadable() {
        // ProxyGroup version 0 and shared SubscriptionBean version 0, independent of current writers.
        val historical = bytes {
            it.writeInt(0); it.writeString("legacy"); it.writeInt(GroupType.SUBSCRIPTION)
            it.writeInt(0); it.writeInt(0); it.writeString("https://example.invalid/old")
            it.writeBoolean(false); it.writeBoolean(true); it.writeBoolean(false); it.writeString("")
        }
        val group = KryoConverters.deserialize(ProxyGroup().apply { export = true }, historical)
        assertEquals("legacy", group.name)
        assertEquals("https://example.invalid/old", group.subscription!!.link)
        assertArrayEquals(historical, KryoConverters.serialize(group))
    }

    @Test fun allStringEncodingsRemainCompatibleIncludingBufferBoundaries() {
        val values = listOf(null, "", "a", "ascii", "中文与日本語", "emoji \uD83C\uDF38", "x".repeat(8193), "界".repeat(8193))
        val encoded = bytes { out -> values.forEach { out.writeString(it) } }
        BoundedKryoInput(encoded).use { input -> values.forEach { assertEquals(it, input.readString()) } }
        BoundedKryoInput(encoded).use { input -> values.forEach { assertEquals(it, input.readStringBuilder()?.toString()) } }
    }

    @Test fun manySmallFieldsAndCollectionsStayWithinTheBudget() {
        val values = List(50000) { "entry-$it" }
        val encoded = bytes { out -> out.writeInt(values.size); values.forEach { out.writeString(it) } }
        BoundedKryoInput(encoded).use { assertEquals(values, it.readStringList()) }
        BoundedKryoInput(encoded).use { assertEquals(values.toSet(), it.readStringSet()) }
    }

    @Test fun historicalOomPayloadIsRejectedBeforeAllocationInTheRealSubscriptionDecoder() {
        val attack = byteArrayOf(0, 0, 0, 0, 0xc0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 4)
        rejected { KryoConverters.deserialize(ProxyGroup().apply { export = true }, attack) }
    }

    @Test fun truncatedSubscriptionCannotReturnAPartiallyInitializedGroup() {
        val group = ProxyGroup(name = "complete")
        val encoded = KryoConverters.serialize(group)
        for (length in 0 until encoded.size) rejected {
            KryoConverters.deserialize(ProxyGroup(), encoded.copyOf(length))
        }
    }

    @Test fun malformedAndUnterminatedStringsAreRejected() {
        val payloads = listOf(byteArrayOf(0xc0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 4),
            byteArrayOf(0xff.toByte()), byteArrayOf(65, 66, 67),
            byteArrayOf(0xc0.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f))
        for (payload in payloads) BoundedKryoInput(payload).use { input -> rejected { input.readString() } }
        val oversized = bytes { it.writeString("x".repeat(BoundedKryoInput.MAX_STRING_CHARS + 1)) }
        BoundedKryoInput(oversized).use { input -> rejected { input.readString() } }
    }

    @Test fun maliciousSnPayloadsUseTheBoundedProductionEntry() {
        val attack = byteArrayOf(0xc0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 4)
        for (link in listOf("sn://anytls:" + Util.b64EncodeUrlSafe(attack),
            "sn://anytls?" + Util.b64EncodeUrlSafe(Util.zlibCompress(attack, 9)))) {
            rejected { parseUniversal(link) }
        }
    }

    @Test fun nestedEntityByteLengthCannotAllocateBeforeRemainingDataIsChecked() {
        val encoded = bytes {
            it.writeInt(0); repeat(2) { _ -> it.writeLong(0) }; it.writeInt(0)
            repeat(3) { _ -> it.writeLong(0) }; repeat(2) { _ -> it.writeInt(0) }
            it.writeString(""); it.writeString(""); it.writeVarInt(Int.MAX_VALUE, true); it.writeBoolean(false)
        }
        rejected { KryoConverters.deserialize(ProxyEntity(), encoded) }
    }

    @Test fun primitiveArrayLengthsAreCheckedBeforeAllocation() {
        for (length in listOf(-1, Int.MAX_VALUE, 1024)) {
            val readers: List<(BoundedKryoInput) -> Unit> = listOf(
                { it.readBytes(length) }, { it.readInts(length) }, { it.readLongs(length) },
                { it.readFloats(length) }, { it.readDoubles(length) }, { it.readShorts(length) },
                { it.readChars(length) }, { it.readBooleans(length) },
                { it.readInts(length, true) }, { it.readLongs(length, true) })
            for (read in readers) BoundedKryoInput(byteArrayOf(0)).use { input -> rejected { read(input) } }
        }
    }

    @Test fun primitiveArrayValuesAndVariableEncodingRemainCompatible() {
        val encoded = bytes {
            it.writeBytes(byteArrayOf(1, 2)); it.writeInts(intArrayOf(3, 4), 0, 2)
            it.writeLongs(longArrayOf(5, 6), 0, 2); it.writeInts(intArrayOf(7, 8), 0, 2, true)
            it.writeLongs(longArrayOf(9, 10), 0, 2, true)
        }
        BoundedKryoInput(encoded).use {
            assertArrayEquals(byteArrayOf(1, 2), it.readBytes(2)); assertArrayEquals(intArrayOf(3, 4), it.readInts(2))
            assertArrayEquals(longArrayOf(5, 6), it.readLongs(2)); assertArrayEquals(intArrayOf(7, 8), it.readInts(2, true))
            assertArrayEquals(longArrayOf(9, 10), it.readLongs(2, true))
        }
    }

    @Test fun collectionCountsRejectNegativeHugeAndTruncatedInputs() {
        for (count in listOf(-1, Int.MAX_VALUE, BoundedKryoInput.MAX_COLLECTION_ELEMENTS + 1, 2)) {
            val encoded = bytes { it.writeInt(count); it.writeString("only one") }
            BoundedKryoInput(encoded).use { input -> rejected { input.readStringList() } }
            BoundedKryoInput(encoded).use { input -> rejected { input.readStringSet() } }
        }
        val malformedChain = bytes { it.writeInt(1); it.writeInt(Int.MAX_VALUE) }
        rejected { KryoConverters.deserialize(ChainBean(), malformedChain) }
    }

    @Test fun repeatedOrdinaryChainComponentsRemainValid() {
        val chain = ChainBean().apply { initializeDefaultValues(); proxies = listOf(1L, 2L, 1L) }
        assertEquals(chain.proxies, KryoConverters.deserialize(ChainBean(), KryoConverters.serialize(chain)).proxies)
    }

    @Test fun cumulativeBudgetRejectsManyIndividuallyValidStringsAndResetsAfterClose() {
        val encoded = bytes { out -> repeat(80) { out.writeString("x".repeat(65536)) } }
        BoundedKryoInput(encoded).use { input -> rejected { repeat(80) { input.readString() } } }
        BoundedKryoInput(bytes { it.writeString("next decode") }).use { assertEquals("next decode", it.readString()) }
    }

    private class Strings : Serializable() {
        override fun initializeDefaultValues() {}
        override fun serializeToBuffer(output: ByteBufferOutput) {}
        override fun deserializeFromBuffer(input: ByteBufferInput) { input.readString() }
    }

    private class Nested(private val child: ByteArray) : Serializable() {
        override fun initializeDefaultValues() {}
        override fun serializeToBuffer(output: ByteBufferOutput) {}
        override fun deserializeFromBuffer(input: ByteBufferInput) {
            repeat(8) { KryoConverters.deserialize(Strings(), child) }
        }
    }

    @Test fun nestedConverterCallsShareTheCumulativeBudgetAndDoNotLeakItAfterFailure() {
        val child = bytes { it.writeString("x".repeat(600000)) }
        rejected { KryoConverters.deserialize(Nested(child), byteArrayOf()) }
        KryoConverters.deserialize(Strings(), child)
    }

    @Test fun oversizedInputRejectedBeforeAnAdditionalBufferIsAllocated() {
        rejected { BoundedKryoInput(ByteArray(BoundedKryoInput.MAX_INPUT_BYTES + 1)).close() }
    }
}
