package io.nekohasekai.sagernet.ui

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CancellationException
import java.io.InterruptedIOException
import java.io.StringReader
import java.math.BigInteger
import java.net.URI

internal object SakuraRepository {
    const val slug = "xingmingname/SakuraBoxForAndroid"
    const val homepage = "https://github.com/$slug"
    const val api = "https://api.github.com/repos/$slug/releases"
    fun releasePage(tag: String): String = URI("https", "github.com", "/$slug/releases/tag/$tag", null).toASCIIString()
}

internal enum class ReleaseChannel { STABLE, PREVIEW }
internal enum class UpdateProblem { NETWORK, TIMEOUT, API, JSON, VERSION, INCOMPLETE }
internal data class ReleasePage(val status: Int, val body: String = "", val hasNext: Boolean = false)
internal sealed class UpdateResult {
    data class Available(val tag: String, val url: String) : UpdateResult()
    object Current : UpdateResult()
    object NoRelease : UpdateResult()
    data class Failure(val problem: UpdateProblem, val status: Int = 0) : UpdateResult()
}

internal data class ReleaseVersion(val numbers: List<BigInteger>, val preview: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (preview.isEmpty() != other.preview.isEmpty()) return if (preview.isEmpty()) 1 else -1
        preview.zip(other.preview).forEach { (a, b) ->
            if (a != b) {
                val an = a.toBigIntegerOrNull()
                val bn = b.toBigIntegerOrNull()
                return when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
            }
        }
        return preview.size.compareTo(other.preview.size)
    }

    companion object {
        private val pattern = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""")
        fun parse(tag: String): ReleaseVersion? {
            if (tag.length > 128) return null
            val match = pattern.matchEntire(tag) ?: return null
            val preview = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (preview.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return ReleaseVersion((1..3).map { match.groupValues[it].toBigInteger() }, preview)
        }
        fun parseCurrent(tag: String): ReleaseVersion? {
            parse(tag)?.let { return it }
            // Compatibility for the existing preview flavor's dated PRE_VERSION_NAME.
            val legacy = Regex("""pre-(\d+\.\d+\.\d+)-(\d{8})-(\d+)""").matchEntire(tag) ?: return null
            return parse("${legacy.groupValues[1]}-0.${legacy.groupValues[2]}.${legacy.groupValues[3].toBigInteger()}")
        }
    }
}

/** Only the fixed SakuraBox API is requested; remote download/html URLs are never executed. */
internal class SakuraReleaseChecker(private val fetch: suspend (String) -> ReleasePage) {
    private val adapter = Gson().getAdapter(JsonElement::class.java)
    private fun json(body: String): JsonElement = try {
        JsonReader(StringReader(body)).use { reader ->
            reader.isLenient = false
            val value = adapter.read(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            value
        }
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid release JSON", error)
    }
    private fun flag(item: JsonObject, key: String): Boolean {
        val value = item.getAsJsonPrimitive(key)
        require(value != null && value.isBoolean)
        return value.asBoolean
    }

    suspend fun check(channel: ReleaseChannel, currentTag: String): UpdateResult {
        val current = ReleaseVersion.parseCurrent(currentTag) ?: return UpdateResult.Failure(UpdateProblem.VERSION)
        return try {
            var best: Pair<String, ReleaseVersion>? = null
            var invalidVersions = false
            for (page in 1..10) {
                val url = if (channel == ReleaseChannel.STABLE) "${SakuraRepository.api}/latest"
                          else "${SakuraRepository.api}?per_page=100&page=$page"
                val response = fetch(url)
                if (response.status == 404 && page == 1) return UpdateResult.NoRelease
                if (response.status != 200) return UpdateResult.Failure(UpdateProblem.API, response.status)
                val root = json(response.body)
                val items = if (channel == ReleaseChannel.STABLE) {
                    require(root.isJsonObject)
                    listOf(root.asJsonObject)
                } else {
                    require(root.isJsonArray && root.asJsonArray.size() <= 100)
                    root.asJsonArray.map { require(it.isJsonObject); it.asJsonObject }
                }
                for (item in items) {
                    if (flag(item, "draft") || flag(item, "prerelease") != (channel == ReleaseChannel.PREVIEW)) continue
                    val tagValue = item.getAsJsonPrimitive("tag_name")
                    require(tagValue != null && tagValue.isString)
                    val tag = tagValue.asString
                    val version = ReleaseVersion.parse(tag)
                    if (version == null || (version.preview.isNotEmpty() != (channel == ReleaseChannel.PREVIEW))) {
                        invalidVersions = true
                        continue
                    }
                    if (best == null || version > best.second) best = tag to version
                }
                if (channel == ReleaseChannel.STABLE || (!response.hasNext && items.size < 100)) {
                    return when {
                        best != null && best.second > current -> UpdateResult.Available(best.first, SakuraRepository.releasePage(best.first))
                        best != null -> UpdateResult.Current
                        invalidVersions -> UpdateResult.Failure(UpdateProblem.VERSION)
                        else -> UpdateResult.NoRelease
                    }
                }
            }
            UpdateResult.Failure(UpdateProblem.INCOMPLETE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedIOException) {
            UpdateResult.Failure(UpdateProblem.TIMEOUT)
        } catch (error: java.io.IOException) {
            UpdateResult.Failure(UpdateProblem.NETWORK)
        } catch (error: Exception) {
            UpdateResult.Failure(UpdateProblem.JSON)
        }
    }
}
