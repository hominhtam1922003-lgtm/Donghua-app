package com.example.donghua

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Chong doi ten mien cho nguon hhkungfu:
 * 1) Doc config.json tren repo GitHub cua app (sua file do la moi ban
 *    app dang cai tu cap nhat, khong can cai lai):
 *      {"base": "https://hhkungfu.ee", "mirrors": [...]}
 * 2) Thu lan luot: base trong config -> cac ten mien guong -> ten mien
 *    tot gan nhat (luu trong may) -> ten mien mac dinh trong code.
 * 3) Ten mien "song" = trang chu tra 200 va co dau van tay cua web
 *    (chuoi "halim" cua theme / ten web HHKUNGFU).
 */
object RemoteConfig {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/hominhtam1922003-lgtm/Donghua-app/main/config.json"
    private const val PREFS = "remote_config"
    private const val KEY_LAST_GOOD = "last_good_base"

    private val latch = CountDownLatch(1)
    @Volatile private var started = false

    /** Goi tu MainActivity: chay ngam, khong chan giao dien. */
    fun resolveAsync(context: Context) {
        if (started) return
        started = true
        val appCtx = context.applicationContext
        thread { resolveBlocking(appCtx) }
    }

    /** Cac man hinh cho toi da [timeoutMs] de biet ten mien truoc khi tai. */
    fun awaitReady(timeoutMs: Long) {
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Bo qua: dung ten mien hien tai
        }
    }

    fun resolveBlocking(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var base = HhSource.DEFAULT_BASE
            val candidates = ArrayList<String>()
            fetchConfig()?.let { json ->
                json.optString("base").trim().trimEnd('/')
                    .takeIf { it.startsWith("http") }?.let {
                        base = it
                        candidates.add(it)
                    }
                json.optJSONArray("mirrors")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optString(i).trim().trimEnd('/')
                            .takeIf { it.startsWith("http") }?.let { candidates.add(it) }
                    }
                }
            }
            candidates.add(base)
            prefs.getString(KEY_LAST_GOOD, null)?.let { candidates.add(it) }
            candidates.add(HhSource.DEFAULT_BASE)
            for (candidate in candidates.distinct()) {
                if (probeWeb(candidate)) {
                    HhSource.activeBase = candidate
                    prefs.edit().putString(KEY_LAST_GOOD, candidate).apply()
                    break
                }
            }
        } catch (e: Exception) {
            // Giu nguyen ten mien dang dung
        } finally {
            latch.countDown()
        }
    }

    private fun fetchConfig(): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", HhSource.UA)
            }
            if (conn.responseCode != 200) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Web dung duoc = trang chu 200 va co dau van tay cua theme Halim. */
    private fun probeWeb(base: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", HhSource.UA)
            }
            if (conn.responseCode !in 200..299) return false
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            text.contains("halim", ignoreCase = true) ||
                text.contains("HHKUNGFU", ignoreCase = true)
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
