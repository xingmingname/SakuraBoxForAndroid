package moe.matsuri.nb4a

/** A failed Android protect call must fail the dial, before any traffic is sent. */
internal fun requireSocketProtection(protect: () -> Boolean) {
    check(protect()) { "VpnService.protect failed" }
}
