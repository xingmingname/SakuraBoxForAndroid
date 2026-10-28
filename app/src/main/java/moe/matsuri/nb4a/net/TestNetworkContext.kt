package moe.matsuri.nb4a.net

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import java.net.InetAddress
import java.net.Socket
import io.nekohasekai.sagernet.SagerNet
import libcore.TestNetworkControl
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class TestNetworkContext private constructor(
    private val network: Network,
    private val selection: TestNetworkSelection<Network>,
    private val onFailure: (IOException) -> Unit,
) : TestNetworkControl, PingNetwork {
    private val state = PinnedTestNetworkState()
    private val closed = AtomicBoolean(false)
    private val connectivity = SagerNet.connectivity
    private var registered = false
    // DNS and socket control retain the same Network for the entire test.
    private val handle = if (Build.VERSION.SDK_INT >= 23) network.networkHandle else network.hashCode().toLong()

    val resolver = object : NetworkLocalResolver({
        checkNetwork()
        network
    }, allowDefaultFallback = false) {
        override fun networkHandle(): Long {
            checkNetwork()
            return handle
        }
    }

    private fun fail(message: String) {
        val error = IOException(message)
        if (!closed.get() && state.invalidate(error)) onFailure(error)
    }

    private fun recheck() {
        try { checkNetwork() } catch (_: IOException) { }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(available: Network) = recheck()

        override fun onLost(lost: Network) {
            if (lost == network) fail("Test network was lost") else recheck()
        }

        override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) = recheck()

        override fun onBlockedStatusChanged(changed: Network, blocked: Boolean) {
            if (changed == network && blocked) fail("Test network is blocked for this application")
        }
    }

    override fun checkNetwork() {
        state.check()
        try {
            TestNetworkPolicy.checkPinned(selection, selectNetwork())
            if (!eligible(connectivity.getNetworkCapabilities(network)) ||
                connectivity.getLinkProperties(network) == null
            ) throw IOException("Test network is unavailable")
        } catch (error: IOException) {
            fail(error.message ?: "Test network is unavailable")
        }
        state.check()
    }

    override fun lookup(host: String): Array<InetAddress> = state.guard {
        checkNetwork()
        network.getAllByName(host).also { checkNetwork() }
    }

    override fun createSocket(): Socket {
        val socket = Socket()
        try {
            return state.guard {
                checkNetwork()
                network.bindSocket(socket)
                checkNetwork()
                socket
            }
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    // Only the native FileDescriptor binding path requires API 23; TCP supports API 21/22.
    @TargetApi(Build.VERSION_CODES.M)
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
        state.invalidate(IOException("Test network context closed"))
        if (registered) {
            registered = false
            connectivity.unregisterNetworkCallback(callback)
        }
    }

    companion object {
        private fun eligible(capabilities: NetworkCapabilities?): Boolean = capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        private fun selectNetwork(): TestNetworkSelection<Network> {
            val connectivity = SagerNet.connectivity
            val ownExpected = DataStore.vpnService != null ||
                (DataStore.serviceMode == Key.MODE_VPN &&
                    (DataStore.serviceState.started || DataStore.serviceState == BaseService.State.Stopping))
            val networks = connectivity.allNetworks.toList()
            val active = if (Build.VERSION.SDK_INT >= 23) connectivity.activeNetwork else {
                // API 21/22 have no getActiveNetwork. Ambiguity must fail, not choose an arbitrary exit.
                val info = connectivity.activeNetworkInfo
                networks.filter {
                    val candidate = connectivity.getNetworkInfo(it)
                    info != null && candidate?.isConnected == true && candidate.type == info.type
                }.singleOrNull()
            }
            fun candidate(network: Network): TestNetworkCandidate<Network> {
                val caps = connectivity.getNetworkCapabilities(network)
                val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == false
                val owned = vpn && if (Build.VERSION.SDK_INT >= 30) caps?.ownerUid == Process.myUid()
                    else ownExpected
                return TestNetworkCandidate(network,
                    eligible(caps) && connectivity.getLinkProperties(network) != null, vpn, owned)
            }
            val candidates = (listOfNotNull(active, SagerNet.underlyingNetwork) + networks)
                .distinct().map(::candidate)
            return TestNetworkPolicy.select(active?.let(::candidate), candidates, ownExpected)
        }

        fun acquire(onFailure: (IOException) -> Unit): TestNetworkContext {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                throw IOException("Independent URL Test network binding requires Android API 23 or later; API 21/22 has no supported FileDescriptor binding API")
            }
            return acquireForTcp(onFailure)
        }

        fun acquireForTcp(onFailure: (IOException) -> Unit): TestNetworkContext {
            val connectivity = SagerNet.connectivity
            val selected = selectNetwork()
            val context = TestNetworkContext(selected.network, selected, onFailure)
            try {
                if (context.handle == 0L) throw IOException("Test network handle is unspecified")
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
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
