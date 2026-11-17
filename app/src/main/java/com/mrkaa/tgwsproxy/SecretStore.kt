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

    // ── Cloudflare Worker fallback domains (optional) ──────────────────────────
    // Raw string as typed by the user; parsed to a clean list when starting the proxy.

    fun cfWorkerDomainsRaw(ctx: Context): String =
        ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE)
            .getString(Const.KEY_CF_WORKER_DOMAINS, "") ?: ""

    fun saveCfWorkerDomainsRaw(ctx: Context, raw: String) {
        ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE)
            .edit().putString(Const.KEY_CF_WORKER_DOMAINS, raw.trim()).apply()
    }

    /** Parse "a.workers.dev, b.workers.dev" (any of , ; space newline) → clean distinct list. */
    fun cfWorkerDomains(ctx: Context): List<String> =
        cfWorkerDomainsRaw(ctx)
            .split(',', ';', ' ', '\n', '\t')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
}
