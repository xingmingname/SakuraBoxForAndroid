package moe.matsuri.nb4a.net

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.SagerNet
import libcore.TestNetworkControl
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(Build.VERSION_CODES.M)
internal class TestNetworkContext private constructor(
    private val network: Network,
    private val onFailure: (IOException) -> Unit,
) : TestNetworkControl, Closeable {
    private val state = PinnedTestNetworkState()
    private val closed = AtomicBoolean(false)
    private val connectivity = SagerNet.connectivity
    private var registered = false
    // Never return zero or re-read the application's mutable underlyingNetwork.
    private val handle = network.networkHandle

    val resolver = object : NetworkLocalResolver({
        checkNetwork()
        network
    }, allowDefaultFallback = false) {
        override fun networkHandle(): Long = handle
    }

    private fun fail(message: String) {
        val error = IOException(message)
        if (!closed.get() && state.invalidate(error)) onFailure(error)
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) {
            if (lost == network) fail("URL Test physical network was lost")
        }

        override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
            if (changed == network && !isPhysical(capabilities)) {
                fail("URL Test physical network is no longer eligible")
            }
        }
    }

    override fun checkNetwork() {
        state.check()
        if (!isPhysical(connectivity.getNetworkCapabilities(network)) ||
            connectivity.getLinkProperties(network) == null
        ) {
            fail("URL Test physical network is unavailable")
        }
        state.check()
    }

    override fun bindSocket(fd: Int) {
        state.guard {
            checkNetwork()
            // fromFd duplicates the descriptor; closing it must not close Go's fd.
            ParcelFileDescriptor.fromFd(fd).use { duplicate ->
                network.bindSocket(duplicate.fileDescriptor)
            }
            checkNetwork()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        state.invalidate(IOException("URL Test network context closed"))
        if (registered) {
            registered = false
            connectivity.unregisterNetworkCallback(callback)
        }
    }

    companion object {
        private fun isPhysical(capabilities: NetworkCapabilities?): Boolean =
            capabilities != null &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        fun acquire(onFailure: (IOException) -> Unit): TestNetworkContext {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                throw IOException("Independent URL Test network binding requires Android API 23 or later; API 21/22 has no supported FileDescriptor binding API")
            }
            val connectivity = SagerNet.connectivity
            val candidates = listOfNotNull(SagerNet.underlyingNetwork, connectivity.activeNetwork) +
                connectivity.allNetworks.toList()
            val selected = candidates.distinct().firstOrNull {
                isPhysical(connectivity.getNetworkCapabilities(it)) &&
                    connectivity.getLinkProperties(it) != null
            } ?: throw IOException("No usable non-VPN network for independent URL Test")
            val context = TestNetworkContext(selected, onFailure)
            try {
                if (context.handle == 0L) throw IOException("URL Test network handle is unspecified")
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(),
                    context.callback,
                )
                context.registered = true
                context.checkNetwork()
                return context
            } catch (error: Exception) {
                context.close()
                throw error
            }
        }
    }
}
