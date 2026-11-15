package io.nekohasekai.sagernet.fmt

import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.fmt.nakis.NakisBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.group.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

@Suppress("DEPRECATION")
class NakisMixedImportTest : InstrumentationTestCase() {
    fun testExistingImportDispatcherAcceptsMixedProtocols() = runBlocking(Dispatchers.Default) {
        val links = listOf(
            "nakis://6c1c4a52-3b8e-4d9a-9a52-0d4f5f0f7a11@127.0.0.1:8443?v=1&tls=0#Nakis",
            "sudoku://eyJoIjoiMTI3LjAuMC4xIiwicCI6NDQzLCJrIjoic3ludGhldGljIiwibiI6IlN1ZG9rdSJ9",
            "anytls://synthetic@127.0.0.1:443#AnyTLS",
            "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?type=tcp&security=none#VLESS",
        ).joinToString("\n")
        val beans = requireNotNull(SubscriptionParser.parseRaw(links))
        assertEquals(listOf(NakisBean::class.java, SudokuBean::class.java, AnyTLSBean::class.java, VMessBean::class.java),
            beans.map { it.javaClass })
        assertTrue((beans[3] as VMessBean).isVLESS)
    }
}

