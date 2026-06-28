package com.mrkaa.tgwsproxy

import android.content.Context
import java.security.SecureRandom

/** Persists the MTProto secret (32 hex chars = 16 random bytes), generated on first run. */
object SecretStore {

    fun getOrCreate(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE)
        prefs.getString(Const.KEY_SECRET, null)?.let { if (it.length == 32) return it }
        val fresh = generate()
        prefs.edit().putString(Const.KEY_SECRET, fresh).apply()
        return fresh
    }

    fun regenerate(ctx: Context): String {
        val fresh = generate()
        ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE)
            .edit().putString(Const.KEY_SECRET, fresh).apply()
        return fresh
    }

    private fun generate(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
