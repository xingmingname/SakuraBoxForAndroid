package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionScheduleTest {
    @Test fun recentlyUpdatedWaitsUntilDue() {
        assertEquals(900L, subscriptionInitialDelay(1000L, listOf(1000L to 15L)))
        assertEquals(0L, subscriptionInitialDelay(2000L, listOf(1000L to 15L)))
    }
    @Test fun eachSubscriptionUsesItsOwnInterval() {
        assertEquals(100L, subscriptionInitialDelay(1000L, listOf(500L to 10L, 100L to 60L)))
        assertEquals(0L, subscriptionInitialDelay(1000L, listOf(0L to 10L, 1000L to 60L)))
    }
}
