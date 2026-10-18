package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SakuraReleaseTest {
    private fun release(tag: String, preview: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"$tag","prerelease":$preview,"draft":$draft,"name":"untrusted title 999","html_url":"https://evil.invalid/app.apk"}"""
    private fun check(body: String, preview: Boolean = false, current: String = "1.4.2", status: Int = 200): UpdateResult = runBlocking {
        SakuraReleaseChecker { ReleasePage(status, body) }.check(if (preview) ReleaseChannel.PREVIEW else ReleaseChannel.STABLE, current)
    }
    @Test fun stableNoUpdateAndNewerLocalVersion() {
        assertEquals(UpdateResult.Current, check(release("v1.4.2")))
        assertEquals(UpdateResult.Current, check(release("v1.4.1")))
        assertEquals(UpdateResult.Current, check(release("v1.4.2"), current = "1.5.0"))
    }
    @Test fun stableUpdateUsesNumericTagAndTrustedRepository() {
        val result = check(release("v1.4.10"), current = "1.4.9") as UpdateResult.Available
        assertEquals("v1.4.10", result.tag)
        assertEquals("https://github.com/xingmingname/SakuraBoxForAndroid/releases/tag/v1.4.10", result.url)
    }
    @Test fun previewUpdateChoosesHighestValidVersionRatherThanApiOrder() {
        val body = listOf(release("v1.4.3-rc.2", true), release("v1.4.3-rc.10", true),
            release("v1.4.3-rc.1", true), release("v9.0.0"), release("v99.0.0-rc.1", true, true),
            release("preview", true)).joinToString(",", "[", "]")
        assertEquals("v1.4.3-rc.10", (check(body, true) as UpdateResult.Available).tag)
    }
    @Test fun previewNoUpdateAndStableBeatsItsOwnPrerelease() {
        assertEquals(UpdateResult.Current, check("[${release("v1.4.3-rc.1", true)}]", true, "1.4.3-rc.1"))
        assertEquals(UpdateResult.Current, check("[${release("v1.4.2-rc.99", true)}]", true))
    }
    @Test fun noPublishedReleaseAndNoPreviewAreNotUpdates() {
        assertEquals(UpdateResult.NoRelease, check("", status = 404))
        assertEquals(UpdateResult.NoRelease, check("[]", true))
        assertEquals(UpdateResult.NoRelease, check("[${release("v1.5.0")}]", true))
        assertEquals(UpdateResult.NoRelease, check("", true, status = 404))
    }
    @Test fun apiErrorsRemainErrorsIncludingRateLimits() {
        for (status in listOf(401, 403, 429, 500))
            assertEquals(UpdateResult.Failure(UpdateProblem.API, status), check("", status = status))
    }
    @Test fun invalidJsonCannotProduceUpdate() {
        for (body in listOf("garbage", "{}", "[]", "null", "{", release("v1.4.3") + " trailing",
            "{tag_name:'v1.4.3',prerelease:false,draft:false}")) {
            assertEquals(body, UpdateResult.Failure(UpdateProblem.JSON), check(body))
        }
    }
    @Test fun invalidVersionsAndWrongChannelAreNotAccepted() {
        for (tag in listOf("latest", "preview", "1.04.3", "v1.4.3-rc.01", "1.4", "x".repeat(129)))
            assertEquals(UpdateResult.Failure(UpdateProblem.VERSION), check(release(tag)))
        assertEquals(UpdateResult.NoRelease, check(release("v1.4.3-rc.1", true)))
        assertEquals(UpdateResult.Failure(UpdateProblem.VERSION), check(release("v1.4.3-rc.1")))
    }
    @Test fun currentPreviewAndLegacyCurrentPreviewAreSupported() {
        assertTrue(check(release("v1.4.3"), current = "1.4.3-rc.2") is UpdateResult.Available)
        assertTrue(check("[${release("v1.4.3-rc.1", true)}]", true, "pre-1.4.3-20260202-1") is UpdateResult.Available)
        assertEquals(UpdateResult.Current, check(release("v1.4.2"), current = "pre-1.4.3-20260202-1"))
    }
    @Test fun semverOrderingAndBuildMetadata() {
        val tags = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        tags.zipWithNext().forEach { (a, b) -> assertTrue(ReleaseVersion.parse(a)!! < ReleaseVersion.parse(b)!!) }
        assertEquals(0, ReleaseVersion.parse("1.4.2+one")!!.compareTo(ReleaseVersion.parse("v1.4.2+two")!!))
    }
    @Test fun paginationIsCompleteAndCannotRedirectRequests() = runBlocking {
        val urls = mutableListOf<String>()
        val result = SakuraReleaseChecker { url ->
            urls += url
            if (urls.size == 1) ReleasePage(200, "[${release("v1.4.3-rc.1", true)}]", true)
            else ReleasePage(200, "[${release("v1.4.4-rc.1", true)}]")
        }.check(ReleaseChannel.PREVIEW, "1.4.2")
        assertEquals("v1.4.4-rc.1", (result as UpdateResult.Available).tag)
        assertEquals(listOf("${SakuraRepository.api}?per_page=100&page=1", "${SakuraRepository.api}?per_page=100&page=2"), urls)
    }
    @Test fun incompletePaginationIsExplicitAndNeverClaimsNoUpdate() = runBlocking {
        val result = SakuraReleaseChecker { ReleasePage(200, "[]", true) }.check(ReleaseChannel.PREVIEW, "1.4.2")
        assertEquals(UpdateResult.Failure(UpdateProblem.INCOMPLETE), result)
    }
    @Test fun networkAndTimeoutAreExplicit() = runBlocking {
        assertEquals(UpdateResult.Failure(UpdateProblem.NETWORK), SakuraReleaseChecker { throw IOException("synthetic") }.check(ReleaseChannel.STABLE, "1.4.2"))
        assertEquals(UpdateResult.Failure(UpdateProblem.TIMEOUT), SakuraReleaseChecker { throw SocketTimeoutException("synthetic") }.check(ReleaseChannel.STABLE, "1.4.2"))
    }
    @Test fun cancellationIsNotSwallowed() = runBlocking {
        try {
            SakuraReleaseChecker { throw CancellationException("cancelled") }.check(ReleaseChannel.STABLE, "1.4.2")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun unknownLocalVersionFailsBeforeNetworkRequest() = runBlocking {
        val result = SakuraReleaseChecker { fail("Unexpected network request"); ReleasePage(200) }.check(ReleaseChannel.STABLE, "unknown")
        assertEquals(UpdateResult.Failure(UpdateProblem.VERSION), result)
    }
}
