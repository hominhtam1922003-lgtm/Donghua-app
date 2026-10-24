package com.example.donghua

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dinh ky chay nen kiem tra tap moi, 2 nguon:
 * 1) Phim YEU THICH: so sanh tong so tap tren web voi moc da luu —
 *    co tap moi thi bao RIENG tung phim (nhu truoc).
 * 2) TOAN BO web: theo doi danh sach "Moi cap nhat" (trang 1-2) —
 *    phim nao vua doi nhan tap (hoac vua xuat hien trong danh sach,
 *    tuc vua duoc cap nhat) thi gom vao 1 thong bao tong hop liet ke
 *    tat ca. Lan chay dau chi luu moc, khong bao (tranh bao loan).
 * Thong bao dung kenh uu tien cao + hien ca tren man hinh khoa.
 */
class NewEpisodeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        try {
            val realUa = android.webkit.WebSettings.getDefaultUserAgent(applicationContext)
            if (!realUa.isNullOrBlank()) HhSource.UA = realUa
        } catch (e: Exception) {
            // Giu UA du phong
        }
        RemoteConfig.resolveBlocking(applicationContext)
        val db = DbRepo(applicationContext)
        val favUrls = HashSet<String>()
        for ((movie, knownCount) in db.favorites()) {
            favUrls.add(movie.url)
            try {
                val detail = HhSource.fetchDetail(movie.url)
                val total = detail.totalEpisodes
                if (total <= 0) continue
                if (knownCount > 0 && total > knownCount) {
                    notifyNewEpisode(movie, total)
                }
                if (total != knownCount) {
                    db.updateFavoriteEpisodeCount(movie.url, total)
                }
            } catch (e: Exception) {
                // Phim loi / bi chan tam thoi: bo qua, lan sau kiem tra lai
                continue
            }
        }
        try {
            checkSiteUpdates(favUrls)
        } catch (e: Exception) {
            // Loi rieng phan nay: khong lam hong ca lan chay
        }
        return Result.success()
    }

    // ---------------- Theo doi toan bo web ----------------

    private fun checkSiteUpdates(favUrls: Set<String>) {
        val movies = ArrayList<Movie>()
        try {
            movies.addAll(HhSource.fetchListPage("/moi-cap-nhat", 1).movies)
        } catch (e: Exception) {
            return
        }
        try {
            movies.addAll(HhSource.fetchListPage("/moi-cap-nhat", 2).movies)
        } catch (e: Exception) {
            // Trang 2 loi thi chi dung trang 1
        }
        // Trang thai hien tai: url -> (tieu de, nhan tap tren the phim)
        val now = LinkedHashMap<String, Pair<String, String>>()
        for (m in movies) {
            if (m.url.isEmpty() || now.containsKey(m.url)) continue
            now[m.url] = m.title to m.sub.substringBefore(" • ").trim()
        }
        if (now.isEmpty()) return

        val prefs = applicationContext.getSharedPreferences(
            "ep_watch", Context.MODE_PRIVATE
        )
        val old = HashMap<String, Pair<String, String>>()
        val oldJson = prefs.getString("baseline", null)
        if (oldJson != null) {
            try {
                val arr = JSONArray(oldJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    old[o.getString("u")] =
                        o.getString("t") to o.getString("e")
                }
            } catch (e: Exception) {
                // Moc hong: coi nhu lan dau
            }
        }
        if (oldJson == null) {
            // Lan chay dau tien: chi luu moc, khong bao
            saveBaseline(prefs, now, old)
            return
        }
        val changed = ArrayList<Pair<String, String>>()
        for ((url, te) in now) {
            if (favUrls.contains(url)) continue
            val prev = old[url]
            if (prev == null || prev != te) changed.add(te)
        }
        if (changed.isNotEmpty()) notifySiteUpdates(changed)
        saveBaseline(prefs, now, old)
    }

    private fun saveBaseline(
        prefs: android.content.SharedPreferences,
        now: LinkedHashMap<String, Pair<String, String>>,
        old: Map<String, Pair<String, String>>
    ) {
        val merged = LinkedHashMap<String, Pair<String, String>>()
        for ((u, te) in now) merged[u] = te
        for ((u, te) in old) {
            if (!merged.containsKey(u) && merged.size < 300) merged[u] = te
        }
        val arr = JSONArray()
        for ((u, te) in merged) {
            arr.put(
                JSONObject()
                    .put("u", u)
                    .put("t", te.first)
                    .put("e", te.second)
            )
        }
        prefs.edit().putString("baseline", arr.toString()).apply()
    }

    private fun notifySiteUpdates(changed: List<Pair<String, String>>) {
        val ctx = applicationContext
        val manager =
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, SUMMARY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lines = changed.joinToString("\n") { "• ${it.first} — ${it.second}" }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📺 ${changed.size} phim vừa có tập mới")
            .setContentText("${changed.first().first} — ${changed.first().second}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(SUMMARY_ID, notification)
    }

    // ---------------- Phim yeu thich ----------------

    private fun notifyNewEpisode(movie: Movie, total: Int) {
        val ctx = applicationContext
        val manager =
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        val intent = Intent(ctx, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_URL, movie.url)
            putExtra(DetailActivity.EXTRA_TITLE, movie.title)
            putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, movie.url.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tập mới: ${movie.title}")
            .setContentText("Đã có $total tập. Chạm để xem ngay.")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(movie.url.hashCode(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Tập mới", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi có tập phim mới"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        // Kênh mới (v2): kenh cu da khoa muc do uu tien thap tren may
        // nguoi dung, khong nang len duoc nen phai doi id kenh
        const val CHANNEL_ID = "tap_moi_v2"
        private const val SUMMARY_ID = 2001
    }
}
