package com.mrkaa.tgwsproxy

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update channel over a private GitHub repo's Releases.
 *
 * The APK embeds a fine-grained PAT (Contents: Read-only, this repo only) — the app is
 * distributed to a friends circle, and the worst a leaked token does is read this repo's
 * source. Both calls (release metadata + asset bytes) carry the Authorization header
 * because the repo is private.
 */
object Updater {

    data class UpdateInfo(
        val tag: String,
        val assetId: Long,
        val assetName: String,
        val sizeBytes: Long,
    )

    /** Build carries no repo/token config (missing update.properties) → UI hides the section. */
    fun unconfigured(): Boolean =
        BuildConfig.UPDATE_OWNER.isEmpty() ||
            BuildConfig.UPDATE_REPO.isEmpty() ||
            BuildConfig.UPDATE_TOKEN.isEmpty()

    private fun api(): String =
        "https://api.github.com/repos/${BuildConfig.UPDATE_OWNER}/${BuildConfig.UPDATE_REPO}"

    /** Latest non-prerelease release; null when there is none or it carries no .apk asset. */
    fun checkLatest(): UpdateInfo? {
        val conn = open("${api()}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) throw IOException("GitHub API HTTP ${conn.responseCode}")
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = root.optString("tag_name")
            if (tag.isEmpty()) return null
            val assets = root.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name.endsWith(".apk") && a.optString("state") == "uploaded") {
                    return UpdateInfo(tag, a.getLong("id"), name, a.optLong("size", -1L))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Where the downloaded APK lands; stale versions from earlier checks are dropped. */
    fun targetFile(context: Context, assetName: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, assetName)
    }

    /**
     * Stream the release asset into [target] via a .part file (a crash mid-download never
     * leaves a half-written APK looking installable). Sizes are verified against both the
     * HTTP Content-Length and the release metadata.
     */
    fun downloadAsset(info: UpdateInfo, target: File, onProgress: (Int) -> Unit) {
        val conn = openAsset(info)
        try {
            val total = conn.contentLengthLong
            var copied = 0L
            val tmp = File(target.absolutePath + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) onProgress((copied * 100 / total).toInt())
                    }
                }
            }
            if (tmp.length() == 0L) throw IOException("empty download")
            if (total > 0 && copied != total) throw IOException("truncated download: $copied/$total")
            if (info.sizeBytes > 0 && copied != info.sizeBytes) {
                throw IOException("size mismatch: $copied != ${info.sizeBytes}")
            }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Private-repo assets answer with a 302 to a short-lived signed URL. Follow it
     * manually so the Authorization header never travels to the redirect host (it is
     * useless there and can break the signature).
     */
    private fun openAsset(info: UpdateInfo): HttpURLConnection {
        val c = open("${api()}/releases/assets/${info.assetId}")
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/octet-stream")
        if (c.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            c.responseCode == HttpURLConnection.HTTP_MOVED_PERM
        ) {
            val loc = c.getHeaderField("Location") ?: throw IOException("302 without Location")
            c.disconnect()
            return open(loc) // signed URL — deliberately no Authorization header
        }
        if (c.responseCode != 200) throw IOException("asset HTTP ${c.responseCode}")
        return c
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
        // GitHub rejects API calls without a User-Agent.
        c.setRequestProperty("User-Agent", "tg-ws-proxy-updater")
        if (!unconfigured()) {
            c.setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
        }
        return c
    }

    /**
     * Numeric per-segment compare: isNewer("v0.10.0", "0.9.1") == true.
     * Non-numeric fragments ("0.6-beta") degrade to their leading digits — fine for
     * the strict "vX.Y.Z" tags this project releases.
     */
    fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val r = remoteTag.trim().removePrefix("v").split('.')
        val l = localVersion.trim().removePrefix("v").substringBefore('-').split('.')
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrNull(i)?.filter(Char::isDigit)?.toIntOrNull() ?: 0
            val b = l.getOrNull(i)?.filter(Char::isDigit)?.toIntOrNull() ?: 0
            if (a != b) return a > b
        }
        return false
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
}
