package com.mrkaa.tgwsproxy

object Const {
    /** Loopback only — Telegram on this same device connects here; never exposed to the network. */
    const val HOST = "127.0.0.1"
    const val PORT = 1443

    const val PREFS = "tgwsproxy"
    const val KEY_SECRET = "secret"

    const val NOTIF_CHANNEL = "proxy"
    const val NOTIF_ID = 1

    /** MTProto "secure mode" marker the connect link expects in front of the raw hex secret. */
    const val LINK_SECRET_PREFIX = "dd"

    fun tgLink(secret: String): String =
        "tg://proxy?server=$HOST&port=$PORT&secret=$LINK_SECRET_PREFIX$secret"
}
