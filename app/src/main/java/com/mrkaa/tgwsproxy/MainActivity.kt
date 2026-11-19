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
import android.view.View
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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

    /** Self-update state: the newer release we found (if any) and its downloaded APK. */
    private var pendingUpdate: Updater.UpdateInfo? = null
    private var downloadedApk: File? = null
    private val updateExec = Executors.newSingleThreadExecutor()

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

        // Optional CF Worker fallback domains — persisted on focus loss / IME done;
        // picked up by ProxyService on the next proxy start.
        b.etCfWorkers.setText(SecretStore.cfWorkerDomainsRaw(this))
        b.etCfWorkers.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) SecretStore.saveCfWorkerDomainsRaw(this, b.etCfWorkers.text.toString())
        }
        b.etCfWorkers.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                SecretStore.saveCfWorkerDomainsRaw(this, b.etCfWorkers.text.toString())
                v.clearFocus()
            }
            false
        }

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

        initUpdateSection()

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
        updateExec.shutdownNow()
    }

    private fun toggle() {
        if (userWantsRunning) stopProxy() else startProxy()
    }

    private fun startProxy() {
        // Pre-check the port off the main thread — if something already owns
        // 127.0.0.1:1443 the Python core would fail to bind; tell the user instead.
        b.btnToggle.isEnabled = false
        healthExec.submit {
            val free = ProxyHealth.portFree()
            mainHandler.post {
                b.btnToggle.isEnabled = true
                if (!free) {
                    toast(getString(R.string.toast_port_busy))
                    return@post
                }
                val i = Intent(this, ProxyService::class.java)
                    .putExtra(ProxyService.EXTRA_SECRET, secret)
                ContextCompat.startForegroundService(this, i)
                userWantsRunning = true
                renderHealth(false) // optimistic "starting…" until the next probe confirms
                toast(getString(R.string.toast_started))
            }
        }
    }

    private fun stopProxy() {
        val i = Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
        startService(i)
        userWantsRunning = false
        renderHealth(false)
        // Stop now unwinds the proxy loop gracefully (see ProxyService), so the process
        // and this activity survive; the heartbeat will confirm the socket going down.
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

    // ── Self-update (private GitHub Releases channel) ─────────────────────────

    /** Wire the update card; hidden entirely on builds without repo config. */
    private fun initUpdateSection() {
        if (Updater.unconfigured()) {
            b.updateSection.visibility = View.GONE
            return
        }
        b.btnUpdate.setOnClickListener { onUpdateAction() }
        checkForUpdate()
    }

    /** The single button walks the state machine: check → download → install. */
    private fun onUpdateAction() {
        when {
            pendingUpdate == null -> checkForUpdate(manual = true)
            downloadedApk == null -> pendingUpdate?.let { downloadUpdate(it) }
            else -> installUpdate()
        }
    }

    /** Silent check on open; manual re-check on tap. UI always lands in a definite state. */
    private fun checkForUpdate(manual: Boolean = false) {
        if (manual) {
            b.btnUpdate.isEnabled = false
            b.tvUpdate.text = getString(R.string.update_checking)
        }
        updateExec.submit {
            val result = runCatching { Updater.checkLatest() }
            mainHandler.post {
                b.btnUpdate.isEnabled = true
                val info = result.getOrNull()
                when {
                    result.isFailure -> {
                        b.tvUpdate.text = getString(R.string.update_check_failed)
                        b.btnUpdate.setText(R.string.btn_update_check)
                    }
                    info == null || !Updater.isNewer(info.tag, BuildConfig.VERSION_NAME) -> {
                        pendingUpdate = null
                        b.tvUpdate.text = getString(R.string.update_uptodate, currentTag())
                        b.btnUpdate.setText(R.string.btn_update_check)
                    }
                    else -> {
                        pendingUpdate = info
                        b.tvUpdate.text =
                            getString(R.string.update_available, info.tag, fmtMiB(info.sizeBytes))
                        b.btnUpdate.text = getString(R.string.btn_update_download, info.tag)
                    }
                }
            }
        }
    }

    private fun downloadUpdate(info: Updater.UpdateInfo) {
        val target = Updater.targetFile(this, info.assetName)
        b.btnUpdate.isEnabled = false
        updateExec.submit {
            val result = runCatching {
                Updater.downloadAsset(info, target) { pct ->
                    mainHandler.post { b.tvUpdate.text = getString(R.string.update_downloading, pct) }
                }
            }
            mainHandler.post {
                b.btnUpdate.isEnabled = true
                if (result.isSuccess) {
                    downloadedApk = target
                    b.tvUpdate.text = getString(R.string.update_downloaded, info.tag)
                    b.btnUpdate.setText(R.string.btn_update_install)
                } else {
                    b.tvUpdate.text = getString(R.string.update_download_failed)
                    b.btnUpdate.text = getString(R.string.btn_update_download, info.tag)
                }
            }
        }
    }

    /** Hand the cached APK to the system installer; Android walks the user through the
     *  "install unknown apps" consent on the first try. */
    private fun installUpdate() {
        val apk = downloadedApk ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) {
            toast(getString(R.string.toast_install_fail))
        }
    }

    private fun currentTag(): String = "v" + BuildConfig.VERSION_NAME.substringBefore('-')

    private fun fmtMiB(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

    private fun renderSecret() {
        b.tvSecret.text = secret
        b.tvLink.text = Const.tgLink(secret)
    }

    private fun clipboard() =
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
