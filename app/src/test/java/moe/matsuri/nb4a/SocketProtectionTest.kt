package moe.matsuri.nb4a

import org.junit.Assert.assertThrows
import org.junit.Test

class SocketProtectionTest {
    @Test fun protectionFailurePropagatesBeforeDial() {
        requireSocketProtection { true }
        assertThrows(IllegalStateException::class.java) { requireSocketProtection { false } }
        assertThrows(SecurityException::class.java) { requireSocketProtection { throw SecurityException("denied") } }
    }
}
