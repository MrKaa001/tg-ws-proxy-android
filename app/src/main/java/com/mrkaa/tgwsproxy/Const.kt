package com.mrkaa.tgwsproxy

object Const {
    /** Loopback only — Telegram on this same device connects here; never exposed to the network. */
    const val HOST = "127.0.0.1"
    const val PORT = 1443

    const val PREFS = "tgwsproxy"
    const val KEY_SECRET = "secret"

    const val NOTIF_CHANNEL = "proxy"
    const val NOTIF_ID = 1

    /** How often the in-app heartbeat and the service monitor re-probe the proxy. */
    const val HEARTBEAT_MS = 2000L
    /** Socket-connect timeout for a single liveness probe (loopback → very short). */
    const val HEALTH_TIMEOUT_MS = 700

    /** MTProto "secure mode" marker the connect link expects in front of the raw hex secret. */
    const val LINK_SECRET_PREFIX = "dd"

    fun tgLink(secret: String): String =
        "tg://proxy?server=$HOST&port=$PORT&secret=$LINK_SECRET_PREFIX$secret"
}
