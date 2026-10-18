package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicationVersionTest {
    @Test fun actualBuildMetadataUsesThePublicationVersion() {
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
        assertEquals(230, BuildConfig.VERSION_CODE)
        val suffix = if (BuildConfig.DEBUG) ".debug" else ""
        assertEquals("app.sakurabox.client$suffix", BuildConfig.APPLICATION_ID)
    }

    @Test fun stableTitleDoesNotReplaceTheSemanticVersionTag() = runBlocking {
        val body = """{"tag_name":"v1.0.0","name":"Sakurabox-1.0.0","draft":false,"prerelease":false}"""
        assertEquals(UpdateResult.Current,
            SakuraReleaseChecker { ReleasePage(200, body) }.check(ReleaseChannel.STABLE, "1.0.0"))
    }

    @Test fun nextStableVersionUsesTheTrustedRepositoryAndNumericTag() = runBlocking {
        val body = """{"tag_name":"v1.0.1","name":"Sakurabox-1.0.1","draft":false,"prerelease":false,"html_url":"https://example.invalid/untrusted"}"""
        assertEquals(UpdateResult.Available("v1.0.1",
            "https://github.com/xingmingname/SakuraBoxForAndroid/releases/tag/v1.0.1"),
            SakuraReleaseChecker { ReleasePage(200, body) }.check(ReleaseChannel.STABLE, "1.0.0"))
    }
}
