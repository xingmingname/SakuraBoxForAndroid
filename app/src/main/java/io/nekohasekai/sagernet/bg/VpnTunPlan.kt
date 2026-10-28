package io.nekohasekai.sagernet.bg

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.math.BigInteger
import java.net.InetAddress

/** JSON is the actual sing-tun.Options passed through JNI, after all overrides. */
internal class VpnTunPlan {
    @SerializedName("MTU") var mtu: Int = 0
    @SerializedName("Inet4Address") var ipv4: List<String>? = null
    @SerializedName("Inet6Address") var ipv6: List<String>? = null
    @SerializedName("DNSAddress") var dns: List<String>? = null
    @SerializedName("DNSMode") var dnsMode: String? = null
    @SerializedName("AutoRoute") var autoRoute: Boolean = false
    @SerializedName("Inet4RouteAddress") var routes4: List<String>? = null
    @SerializedName("Inet6RouteAddress") var routes6: List<String>? = null
    @SerializedName("Inet4RouteExcludeAddress") var exclude4: List<String>? = null
    @SerializedName("Inet6RouteExcludeAddress") var exclude6: List<String>? = null
    @SerializedName("IncludePackage") var includePackages: List<String>? = null
    @SerializedName("ExcludePackage") var excludePackages: List<String>? = null
    val addresses: List<String> get() = ipv4.orEmpty() + ipv6.orEmpty()
    val hasIpv4: Boolean get() = !ipv4.isNullOrEmpty()
    val hasIpv6: Boolean get() = !ipv6.isNullOrEmpty()

    /** Subtract exclusions before Builder.addRoute, including Android versions without excludeRoute. */
    fun jsonRoutes(): List<Pair<String, Int>> {
        if (!autoRoute) return emptyList()
        data class Prefix(val start: BigInteger, val bits: Int, val length: Int) {
            val end get() = start + BigInteger.ONE.shiftLeft(bits - length) - BigInteger.ONE
        }
        fun parse(value: String): Prefix {
            val parts = value.split('/')
            require(parts.size == 2 && parts[0].matches(Regex("[0-9a-fA-F:.]+"))) { "Expected numeric route prefix" }
            val bytes = InetAddress.getByName(parts[0]).address
            val bits = bytes.size * 8; val length = parts[1].toInt()
            require(length in 0..bits)
            return Prefix(BigInteger(1, bytes).shiftRight(bits - length).shiftLeft(bits - length), bits, length)
        }
        fun subtract(p: Prefix, excluded: Prefix): List<Prefix> {
            if (p.bits != excluded.bits || p.end < excluded.start || p.start > excluded.end) return listOf(p)
            if (excluded.start <= p.start && excluded.end >= p.end) return emptyList()
            val a = Prefix(p.start, p.bits, p.length + 1)
            val b = Prefix(p.start + BigInteger.ONE.shiftLeft(p.bits - a.length), p.bits, a.length)
            return subtract(a, excluded) + subtract(b, excluded)
        }
        var routes = (routes4.orEmpty() + routes6.orEmpty()).ifEmpty {
            listOfNotNull(if (hasIpv4) "0.0.0.0/0" else null, if (hasIpv6) "::/0" else null)
        }.map(::parse).distinct()
        for (excluded in exclude4.orEmpty() + exclude6.orEmpty()) {
            routes = routes.flatMap { subtract(it, parse(excluded)) }
            require(routes.size <= 4096) { "Too many Android VPN routes" }
        }
        return routes.map { p ->
            val bytes = p.start.toByteArray().takeLast(p.bits / 8).toByteArray()
            InetAddress.getByAddress(ByteArray(p.bits / 8 - bytes.size) + bytes).hostAddress!! to p.length
        }
    }

    fun dnsServers(): List<String> {
        if (!dns.isNullOrEmpty()) return dns!!
        return listOfNotNull(ipv4?.firstOrNull(), ipv6?.firstOrNull()).mapNotNull { prefix ->
            val (literal, bits) = prefix.split('/')
            val bytes = InetAddress.getByName(literal).address // validated numeric core prefix
            val hostBits = bytes.size * 8 - bits.toInt()
            val number = BigInteger(1, bytes)
            if (hostBits == 0 || number.and(BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)) == BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)) {
                null // no usable next address in this prefix, matching sing-tun
            } else {
                val next = number.add(BigInteger.ONE).toByteArray().takeLast(bytes.size).toByteArray()
                InetAddress.getByAddress(ByteArray(bytes.size - next.size) + next).hostAddress
            }
        }
    }

    companion object {
        fun parse(json: String): VpnTunPlan = Gson().fromJson(json, VpnTunPlan::class.java).apply {
            require(mtu > 0 && addresses.isNotEmpty()) { "TUN requires MTU and at least one address" }
        }
    }
}
