package com.mrkaa.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Foreground service that runs the embedded Python proxy.
 *
 * The proxy's asyncio loop is blocking, so it runs on a dedicated daemon thread.
 * Stop signals upstream's stop_event (via proxy_entry.stop()) so the loop unwinds
 * cleanly and the process survives; a hard process teardown remains only as a
 * timeout backstop if the loop refuses to unwind. See proxy_entry.py for the bridge.
 */
class ProxyService : android.app.Service() {

    @Volatile private var worker: Thread? = null
    @Volatile private var monitor: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setAutoStart(this, false)
            stopProxy()
            return START_NOT_STICKY
        }

        // Promote on every entry — Android requires startForeground() within ~5 s of any
        // service start, including the system-initiated sticky restart (null intent).
        startForegroundCompat()

        // Null intent = the system re-created us after process death (START_STICKY) or a
        // boot trampoline. Honour the user's last choice: if they'd stopped the proxy
        // (e.g. a hard-teardown left the service "started"), fold back down, don't revive.
        if (intent == null && !shouldAutoStart(this)) {
            finishStop(hard = false)
            return START_NOT_STICKY
        }

        // Explicit start persists the intent so reboot / death can bring us back.
        if (intent != null) setAutoStart(this, true)

        // A sticky restart drops the extras, so fall back to the persisted secret —
        // otherwise upstream would generate a fresh one that no longer matches the link.
        val secret = intent?.getStringExtra(EXTRA_SECRET)?.takeIf { it.isNotEmpty() }
            ?: SecretStore.getOrCreate(this)

        if (worker == null) {
            worker = Thread { runProxy(secret) }.apply {
                isDaemon = true
                name = "tg-ws-proxy"
                start()
            }
        }
        startMonitor()
        // REDELIVER over STICKY: if the system kills us for memory, we're restarted
        // with the original start intent (extras intact) instead of a null one —
        // combined with the persisted secret/prefs this resurrects the exact start.
        return START_REDELIVER_INTENT
    }

    /**
     * Background heartbeat: the FGS notification is the status-bar truth, so keep its
     * text in sync with whether the proxy is actually accepting connections — "starting…"
     * until the socket binds, "running" once it does, "not responding" if it dies.
     */
    private fun startMonitor() {
        if (monitor != null) return
        monitor = Thread {
            var last: Boolean? = null
            while (!Thread.currentThread().isInterrupted) {
                val up = ProxyHealth.isUp()
                // Treat "thread gone AND not listening" as a hard failure, not just startup.
                val dead = up.not() && worker?.isAlive == false
                if (up != last) {
                    last = up
                    updateNotification(up, dead)
                }
                try {
                    Thread.sleep(Const.HEARTBEAT_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "proxy-monitor"
            start()
        }
    }

    private fun runProxy(secret: String) {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(applicationContext))
            }
            val py = Python.getInstance()
            val entry = py.getModule("proxy_entry")
            Log.i(TAG, "starting proxy on ${Const.HOST}:${Const.PORT}")
            // dc_ips = null -> upstream defaults; secret "" -> upstream generates one.
            // CF Worker domains come from prefs (persisted, survives sticky restarts);
            // empty list -> upstream's own fallback chain.
            entry.callAttr(
                "start", Const.HOST, Const.PORT, secret, null,
                SecretStore.cfWorkerDomains(this)
            )
        } catch (e: PyException) {
            Log.e(TAG, "python proxy crashed", e)
        } catch (e: Throwable) {
            Log.e(TAG, "proxy thread error", e)
        }
    }

    /**
     * Ask the embedded proxy to unwind its asyncio loop cleanly (close the listener,
     * cancel clients) and only then drop the foreground service — the process stays
     * alive, so the UI no longer vanishes on Stop. If upstream doesn't unwind within
     * the grace window we fall back to the old hard teardown so Stop is never stuck.
     */
    private fun stopProxy() {
        Log.i(TAG, "stopping proxy (graceful)")
        val w = worker
        Thread {
            // Nothing actually running — just drop the service.
            if (w == null || !Python.isStarted()) {
                finishStop(hard = false)
                return@Thread
            }
            var graceful = false
            try {
                val signalled = Python.getInstance()
                    .getModule("proxy_entry").callAttr("stop").toBoolean()
                if (signalled) {
                    w.join(Const.STOP_GRACE_MS)
                    graceful = !w.isAlive
                }
            } catch (e: Throwable) {
                Log.e(TAG, "graceful stop failed", e)
            }
            monitor?.interrupt()
            monitor = null
            worker = null
            if (graceful) {
                Log.i(TAG, "proxy stopped gracefully")
                finishStop(hard = false)
            } else {
                Log.w(TAG, "graceful stop timed out — hard teardown")
                finishStop(hard = true)
            }
        }.apply { isDaemon = true; name = "proxy-stop"; start() }
    }

    private fun finishStop(hard: Boolean) {
        if (hard) {
            // Backstop: the asyncio loop didn't unwind; kill the process so the
            // listener and sockets can't linger.
            stopForeground(STOP_FOREGROUND_REMOVE)
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }
        main.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        ensureChannel(this)
        // Starts in the "coming up" state; the monitor flips it to running once the
        // socket is actually listening.
        val notif = buildNotification(up = false, dead = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Const.NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Const.NOTIF_ID, notif)
        }
    }

    private fun updateNotification(up: Boolean, dead: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(Const.NOTIF_ID, buildNotification(up, dead))
    }

    private fun buildNotification(up: Boolean, dead: Boolean): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        // Stop straight from the shade — same path as the in-app Stop button: it clears
        // auto_start and unwinds the loop gracefully (see onStartCommand / stopProxy).
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val titleRes = when {
            up -> R.string.notif_title_running
            dead -> R.string.notif_title_dead
            else -> R.string.notif_title_starting
        }
        val textRes = when {
            up -> R.string.notif_text_running
            dead -> R.string.notif_text_dead
            else -> R.string.notif_text_starting
        }
        return NotificationCompat.Builder(this, Const.NOTIF_CHANNEL)
            .setContentTitle(getString(titleRes))
            .setContentText(getString(textRes))
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setOngoing(true)
            .setContentIntent(tap)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_action_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "ProxyService"
        const val ACTION_STOP = "com.mrkaa.tgwsproxy.STOP"
        const val EXTRA_SECRET = "secret"

        /** Did the user have the proxy running? Consulted by BootReceiver + sticky restart. */
        fun shouldAutoStart(ctx: Context): Boolean =
            ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Const.KEY_AUTOSTART, false)

        fun setAutoStart(ctx: Context, value: Boolean) {
            ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(Const.KEY_AUTOSTART, value).apply()
        }

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(Const.NOTIF_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        Const.NOTIF_CHANNEL,
                        ctx.getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }
}
