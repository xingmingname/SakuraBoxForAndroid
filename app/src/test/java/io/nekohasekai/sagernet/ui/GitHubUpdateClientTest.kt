package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class GitHubUpdateClientTest {
    private fun client(status: Int, body: String, link: String? = null) = GitHubUpdateClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("synthetic").body(body.toResponseBody())
                .apply { if (link != null) header("Link", link) }.build()
        }.build())
    @Test fun statusAndPaginationAreRetained() = runBlocking {
        assertEquals(404, client(404, "").fetch("${SakuraRepository.api}/latest").status)
        assertTrue(client(200, "[]", "<https://evil.invalid/>; rel=\"next\"")
            .fetch("${SakuraRepository.api}?per_page=100&page=1").hasNext)
    }
    @Test fun foreignAndCleartextUrlsAreRejectedBeforeTransport() = runBlocking {
        for (url in listOf("http://api.github.com/", "https://evil.invalid/", SakuraRepository.homepage,
            "${SakuraRepository.api}?per_page=100&page=11")) {
            try { client(200, "[]").fetch(url); fail("Accepted $url") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun oversizedResponsesAreRejected() = runBlocking {
        try {
            client(200, "x".repeat(2 * 1024 * 1024 + 1)).fetch("${SakuraRepository.api}/latest")
            fail("Oversized response accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
