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
    val addresses: List<String> get() = ipv4.orEmpty() + ipv6.orEmpty()
    val hasIpv4: Boolean get() = !ipv4.isNullOrEmpty()
    val hasIpv6: Boolean get() = !ipv6.isNullOrEmpty()

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
