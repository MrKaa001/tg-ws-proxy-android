package com.mrkaa.tgwsproxy

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Honest liveness probe for the embedded proxy.
 *
 * The only process-independent truth about "is the proxy up" is whether something
 * is accepting TCP on 127.0.0.1:1443 — which is exactly the listening socket the
 * Python proxy binds. A successful connect means it's serving; anything else (refused,
 * timeout) means it isn't. We connect and immediately close; the proxy just sees a
 * dropped client, which is harmless.
 *
 * Always call off the main thread — connect() does network I/O.
 */
object ProxyHealth {

    /** true if the proxy is accepting connections on loopback. */
    fun isUp(timeoutMs: Int = Const.HEALTH_TIMEOUT_MS): Boolean =
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(Const.HOST, Const.PORT), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
}
