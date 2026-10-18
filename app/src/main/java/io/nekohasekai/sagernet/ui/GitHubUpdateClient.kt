package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class GitHubUpdateClient(client: OkHttpClient = sharedClient, proxyPort: Int? = null) {
    private val transport = if (proxyPort == null) client else client.newBuilder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))).build()
    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
        private const val maxBodyBytes = 2 * 1024 * 1024
    }

    suspend fun fetch(url: String): ReleasePage = suspendCancellableCoroutine { continuation ->
        require(url == "${SakuraRepository.api}/latest" ||
            Regex(Regex.escape(SakuraRepository.api) + "\\?per_page=100&page=([1-9]|10)").matches(url))
        val call = transport.newCall(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "SakuraBox-update-check").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val page = response.use {
                        var body = ""
                        if (it.code == 200) {
                            val content = requireNotNull(it.body)
                            require(content.contentLength() <= maxBodyBytes)
                            content.byteStream().use { input ->
                                val bytes = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(bytes.size() + count <= maxBodyBytes) { "Release response exceeds size limit" }
                                    bytes.write(buffer, 0, count)
                                }
                                body = bytes.toString(Charsets.UTF_8.name())
                            }
                        }
                        ReleasePage(it.code, body, Regex(""";\s*rel="?next"?""").containsMatchIn(it.header("Link").orEmpty()))
                    }
                    if (continuation.isActive) continuation.resume(page)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
