package com.mrkaa.tgwsproxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.util.concurrent.Executors
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mrkaa.tgwsproxy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var secret: String

    /** What the user asked for (Start/Stop) — drives the "starting…" hint while the
     *  socket isn't up yet. Reconciled to true whenever a probe finds the proxy live. */
    private var userWantsRunning = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthExec = Executors.newSingleThreadExecutor()

    /** Re-probe the proxy on a fixed cadence while the screen is in front. */
    private val heartbeat = object : Runnable {
        override fun run() {
            healthExec.submit {
                val up = ProxyHealth.isUp()
                mainHandler.post { renderHealth(up) }
            }
            mainHandler.postDelayed(this, Const.HEARTBEAT_MS)
        }
    }

    private val askNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — FGS still runs, just no visible notification if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(applicationContext))
        }

        secret = SecretStore.getOrCreate(this)
        renderSecret()

        b.btnToggle.setOnClickListener { toggle() }
        b.btnCheck.setOnClickListener { manualCheck() }
        b.btnTelegram.setOnClickListener { openInTelegram() }
        b.btnCopy.setOnClickListener { copySecret() }
        b.btnRegen.setOnClickListener {
            secret = SecretStore.regenerate(this)
            renderSecret()
            toast(getString(R.string.toast_regenerated))
        }
        b.btnBattery.setOnClickListener { requestIgnoreBattery() }

        maybeRequestNotifPermission()
        renderHealth(false)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(heartbeat)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(heartbeat)
    }

    override fun onDestroy() {
        super.onDestroy()
        healthExec.shutdownNow()
    }

    private fun toggle() {
        if (userWantsRunning) stopProxy() else startProxy()
    }

    private fun startProxy() {
        val i = Intent(this, ProxyService::class.java)
            .putExtra(ProxyService.EXTRA_SECRET, secret)
        ContextCompat.startForegroundService(this, i)
        userWantsRunning = true
        renderHealth(false) // optimistic "starting…" until the next probe confirms
        toast(getString(R.string.toast_started))
    }

    private fun stopProxy() {
        val i = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
        startService(i)
        userWantsRunning = false
        renderHealth(false)
        // NOTE: stop currently tears the process down (see ProxyService) — the
        // activity may be recreated; that's expected for milestone 1.
    }

    /** Manual one-shot probe with a latency readout — the "check" button. */
    private fun manualCheck() {
        b.btnCheck.isEnabled = false
        b.tvStatus.text = getString(R.string.status_checking)
        tintSignal(R.color.amber)
        healthExec.submit {
            val t0 = System.currentTimeMillis()
            val up = ProxyHealth.isUp(1500)
            val ms = System.currentTimeMillis() - t0
            mainHandler.post {
                b.btnCheck.isEnabled = true
                renderHealth(up)
                toast(
                    if (up) getString(R.string.toast_check_up, ms)
                    else getString(R.string.toast_check_down)
                )
            }
        }
    }

    /** Single source of truth for the status line + toggle label, driven by a real probe. */
    private fun renderHealth(up: Boolean) {
        if (up) userWantsRunning = true // a live socket means it's running, reconcile intent
        val statusRes: Int
        val colorRes: Int
        when {
            up -> { statusRes = R.string.status_running; colorRes = R.color.signal }
            userWantsRunning -> { statusRes = R.string.status_starting; colorRes = R.color.amber }
            else -> { statusRes = R.string.status_stopped; colorRes = R.color.signal_dim }
        }
        b.tvStatus.text = getString(statusRes)
        tintSignal(colorRes)
        b.btnToggle.text = getString(if (userWantsRunning) R.string.btn_stop else R.string.btn_start)
    }

    /** Recolour the signal dot to match the current state. */
    private fun tintSignal(colorRes: Int) {
        b.ivDot.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun openInTelegram() {
        val link = Const.tgLink(secret)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: Exception) {
            // No Telegram installed (or it rejects the scheme) — copy the link instead.
            clipboard().setPrimaryClip(ClipData.newPlainText("tg proxy link", link))
            toast(getString(R.string.toast_link_copied))
        }
    }

    private fun copySecret() {
        clipboard().setPrimaryClip(ClipData.newPlainText("mtproto secret", secret))
        toast(getString(R.string.toast_secret_copied))
    }

    private fun requestIgnoreBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        } catch (e: Exception) {
            toast(getString(R.string.toast_battery_unavailable))
        }
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderSecret() {
        b.tvSecret.text = secret
        b.tvLink.text = Const.tgLink(secret)
    }

    private fun clipboard() =
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
