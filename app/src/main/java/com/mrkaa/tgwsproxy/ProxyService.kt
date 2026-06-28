package com.mrkaa.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Foreground service that runs the embedded Python proxy.
 *
 * The proxy's asyncio loop is blocking, so it runs on a dedicated daemon thread.
 * Stopping it cleanly from outside asyncio is non-trivial (the upstream script
 * owns its own loop), so for now STOP tears the whole process down — crude but
 * reliable. A graceful-shutdown hook into the upstream loop is a later milestone.
 */
class ProxyService : android.app.Service() {

    @Volatile private var worker: Thread? = null
    @Volatile private var monitor: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxy()
            return START_NOT_STICKY
        }

        val secret = intent?.getStringExtra(EXTRA_SECRET).orEmpty()
        startForegroundCompat()

        if (worker == null) {
            worker = Thread { runProxy(secret) }.apply {
                isDaemon = true
                name = "tg-ws-proxy"
                start()
            }
        }
        startMonitor()
        return START_STICKY
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
            entry.callAttr("start", Const.HOST, Const.PORT, secret, null)
        } catch (e: PyException) {
            Log.e(TAG, "python proxy crashed", e)
        } catch (e: Throwable) {
            Log.e(TAG, "proxy thread error", e)
        }
    }

    private fun stopProxy() {
        Log.i(TAG, "stopping proxy (process teardown)")
        monitor?.interrupt()
        monitor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // Ensure the asyncio loop / sockets actually die.
        android.os.Process.killProcess(android.os.Process.myPid())
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
            .build()
    }

    companion object {
        private const val TAG = "ProxyService"
        const val ACTION_STOP = "com.mrkaa.tgwsproxy.STOP"
        const val EXTRA_SECRET = "secret"

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
