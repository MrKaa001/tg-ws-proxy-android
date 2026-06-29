package com.mrkaa.tgwsproxy

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Quick Settings tile that mirrors and toggles the proxy without opening the app.
 *
 * "Running" is the same honest truth the FGS notification uses — a live listening socket
 * (ProxyHealth) — so the tile can't show ACTIVE for a proxy whose process has died. The
 * probe is TCP I/O, so it runs off the main thread and pushes the result back to the tile.
 *
 * Toggling reuses ProxyService's existing paths: an explicit start persists auto_start
 * (so reboot/death revive it), ACTION_STOP clears it and unwinds the loop gracefully.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ProxyTileService : TileService() {

    private val probeExec = Executors.newSingleThreadExecutor()

    /** Fired whenever the shade opens — show the intent state instantly, then the probe. */
    override fun onStartListening() {
        val tile = qsTile ?: return
        applyState(tile, ProxyService.shouldAutoStart(this))
        probeExec.submit {
            val up = ProxyHealth.isUp()
            qsTile?.let { applyState(it, up) }
        }
    }

    override fun onClick() {
        val running = ProxyService.shouldAutoStart(this)
        if (running) stopProxy() else startProxy()
        // Optimistic flip to the target; the next onStartListening reconciles via a probe.
        // No immediate probe here — a fresh start needs a few seconds before the socket
        // binds, and probing now would briefly bounce the tile back to "off".
        qsTile?.let { applyState(it, active = !running) }
    }

    private fun startProxy() {
        val i = Intent(this, ProxyService::class.java)
            .putExtra(ProxyService.EXTRA_SECRET, SecretStore.getOrCreate(this))
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopProxy() {
        startService(Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
    }

    private fun applyState(tile: Tile, active: Boolean) {
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (active) R.string.tile_sub_running else R.string.tile_sub_stopped
            )
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        probeExec.shutdownNow()
    }
}
