package moe.matsuri.nb4a.net

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** One test never switches to another network, including after cancellation. */
internal class PinnedTestNetworkState {
    private val failure = AtomicReference<IOException?>()

    fun invalidate(error: IOException): Boolean = failure.compareAndSet(null, error)

    fun check() {
        failure.get()?.let { throw it }
    }

    fun <T> guard(action: () -> T): T {
        check()
        val result = action()
        check()
        return result
    }
}
