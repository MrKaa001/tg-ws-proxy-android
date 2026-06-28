package com.mrkaa.tgwsproxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mrkaa.tgwsproxy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var secret: String
    private var running = false

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
        b.btnTelegram.setOnClickListener { openInTelegram() }
        b.btnCopy.setOnClickListener { copySecret() }
        b.btnRegen.setOnClickListener {
            secret = SecretStore.regenerate(this)
            renderSecret()
            toast(getString(R.string.toast_regenerated))
        }
        b.btnBattery.setOnClickListener { requestIgnoreBattery() }

        maybeRequestNotifPermission()
        renderState()
    }

    private fun toggle() {
        if (running) stopProxy() else startProxy()
    }

    private fun startProxy() {
        val i = Intent(this, ProxyService::class.java)
            .putExtra(ProxyService.EXTRA_SECRET, secret)
        ContextCompat.startForegroundService(this, i)
        running = true
        renderState()
        toast(getString(R.string.toast_started))
    }

    private fun stopProxy() {
        val i = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
        startService(i)
        running = false
        renderState()
        // NOTE: stop currently tears the process down (see ProxyService) — the
        // activity may be recreated; that's expected for milestone 1.
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

    private fun renderState() {
        b.tvStatus.text = getString(if (running) R.string.status_running else R.string.status_stopped)
        b.btnToggle.text = getString(if (running) R.string.btn_stop else R.string.btn_start)
    }

    private fun clipboard() =
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
