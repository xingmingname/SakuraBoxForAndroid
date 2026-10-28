package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.utils.toBytesString
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LintCompatibilityTest {
    @Test fun protocolTokenNormalizationIsIndependentOfTurkishLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val bean = VMessBean().apply { type = "QUIC"; initializeDefaultValues() }
            assertEquals("quic", bean.type)
            assertEquals("none", bean.security)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun trafficDisplayRetainsTheUsersLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("1,00 KiB", 1024L.toBytesString())
            assertEquals("1,00 MiB", (1024L * 1024).toBytesString())
            Locale.setDefault(Locale.US)
            assertEquals("1.00 GiB", (1024L * 1024 * 1024).toBytesString())
        } finally { Locale.setDefault(previous) }
    }
}
