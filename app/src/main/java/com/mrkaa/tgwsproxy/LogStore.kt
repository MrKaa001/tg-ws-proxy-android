package com.mrkaa.tgwsproxy

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only run log shown by the in-app "Proxy logs" dialog — the diagnostics
 * channel for setups without adb. Two writers feed the same file:
 *  - Python side: proxy_entry tees sys.stdout/stderr into it (upstream logging,
 *    argparse errors, tracebacks).
 *  - Kotlin side: [append] at service/proxy lifecycle points, plus the uncaught
 *    -exception hook so UI crashes are persisted before the process dies.
 */
object LogStore {

    private const val FILE_NAME = "proxy.log"
    private const val TRIM_BYTES = 256 * 1024
    private const val KEEP_BYTES = 128 * 1024

    @Volatile private var crashHandlerInstalled = false

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun append(ctx: Context, line: String) {
        try {
            val f = file(ctx)
            f.parentFile?.mkdirs()
            if (f.length() > TRIM_BYTES) trim(f)
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$stamp  $line\n")
        } catch (e: Exception) {
            // Logging must never take the app down.
        }
    }

    /** Last [maxBytes] of the log (viewer dialog payload). */
    fun tail(ctx: Context, maxBytes: Int = 64 * 1024): String {
        val f = file(ctx)
        if (!f.exists()) return ""
        val bytes = f.readBytes()
        val from = maxOf(0, bytes.size - maxBytes)
        val s = String(bytes, from, bytes.size - from, Charsets.UTF_8)
        return if (from > 0) "…$s" else s
    }

    /** Persist uncaught exceptions to the log, then chain to the previous handler
     *  (the default one shows the crash dialog / kills the process). */
    fun installCrashHandler(ctx: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { append(ctx, "FATAL (${t.name}): ${Log.getStackTraceString(e)}") }
            prev?.uncaughtException(t, e)
        }
    }

    private fun trim(f: File) {
        try {
            val bytes = f.readBytes()
            if (bytes.size <= KEEP_BYTES) return
            val tmp = File(f.parentFile, FILE_NAME + ".tmp")
            tmp.writeBytes(bytes.copyOfRange(bytes.size - KEEP_BYTES, bytes.size))
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
        }
    }
}
