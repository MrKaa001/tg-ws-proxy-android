package com.mrkaa.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Brings the proxy back "always on" after the two ways the foreground service can die
 * out from under us:
 *
 *  - device reboot (BOOT_COMPLETED) — delivered after the user unlocks, so the default
 *    credential-encrypted prefs are readable here;
 *  - an app update installed over the same signing key (MY_PACKAGE_REPLACED), which
 *    restarts the process.
 *
 * Process death while running is handled separately by START_STICKY in ProxyService.
 * In every case we only restart if the user had it running — shouldAutoStart() gates it,
 * so a deliberate Stop stays stopped across reboots.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON", // some OEMs (HTC/older) use this instead
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        if (!ProxyService.shouldAutoStart(context)) return

        val secret = SecretStore.getOrCreate(context)
        val svc = Intent(context, ProxyService::class.java)
            .putExtra(ProxyService.EXTRA_SECRET, secret)
        // BOOT_COMPLETED / package-replaced are on the allowlist that lets a background
        // app start a foreground service on Android 12+.
        ContextCompat.startForegroundService(context, svc)
        Log.i("BootReceiver", "auto-restarted proxy after ${intent.action}")
    }
}
