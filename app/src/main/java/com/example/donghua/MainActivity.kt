package com.example.donghua

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tabs: List<TextView>
    private var selectedTab: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dung UA THAT cua may cho moi request: dau van tay nhat quan
        // nhu Chrome that thi Cloudflare moi cho qua man hinh xac minh
        try {
            val realUa = android.webkit.WebSettings.getDefaultUserAgent(applicationContext)
            if (!realUa.isNullOrBlank()) HhSource.UA = realUa
        } catch (e: Exception) {
            // Giu UA du phong trong HhSource
        }
        setContentView(R.layout.activity_main)
        // App da co toolbar rieng trong layout; tat action bar mac dinh
        // de khoi bi 2 thanh tieu de chong nhau
        supportActionBar?.hide()

        val tabHome = findViewById<TextView>(R.id.tabHome)
        val tabSingle = findViewById<TextView>(R.id.tabSingle)
        val tabSeries = findViewById<TextView>(R.id.tabSeries)
        val tabCinema = findViewById<TextView>(R.id.tabCinema)
        val tabFilter = findViewById<TextView>(R.id.tabFilter)
        val tabFavorites = findViewById<TextView>(R.id.tabFavorites)
        val tabHistory = findViewById<TextView>(R.id.tabHistory)
        tabs = listOf(
            tabHome, tabSingle, tabSeries, tabCinema, tabFilter,
            tabFavorites, tabHistory
        )

        tabHome.setOnClickListener { select(tabHome); show(HomeFragment()) }
        tabSingle.setOnClickListener {
            select(tabSingle)
            show(BrowseFragment.newInstancePath("Mới cập nhật", "/moi-cap-nhat"))
        }
        tabSeries.setOnClickListener {
            select(tabSeries)
            show(BrowseFragment.newInstancePath("Xem nhiều", "/top-xem-nhieu"))
        }
        tabCinema.setOnClickListener {
            select(tabCinema)
            show(BrowseFragment.newInstancePath("Hoàn thành", "/hoan-thanh"))
        }
        tabFilter.setOnClickListener { select(tabFilter); show(ScheduleFragment()) }
        tabFavorites.setOnClickListener {
            // Thong bao tap moi gan voi phim yeu thich:
            // chua co quyen thi xin lai tai day (Android 13+)
            ensureNotificationPermission()
            select(tabFavorites)
            show(FavoritesFragment())
        }
        tabHistory.setOnClickListener { select(tabHistory); show(HistoryFragment()) }

        if (savedInstanceState == null) {
            select(tabHome)
            show(HomeFragment())
        }

        ensureNotificationPermission()
        ensureBackgroundAllowed()
        scheduleNewEpisodeChecks()
        // Xac dinh ten mien nguon dang song (chong web doi ten mien)
        RemoteConfig.resolveAsync(applicationContext)
    }

    /** To mau the dang chon (dam + mau chinh), cac the con lai ve xam. */
    private fun select(tab: TextView) {
        selectedTab = tab
        val primary = androidx.core.content.ContextCompat.getColor(this, R.color.tab_selected)
        for (t in tabs) {
            if (t == tab) {
                t.setTextColor(primary)
                t.setTypeface(null, Typeface.BOLD)
            } else {
                t.setTextColor(Color.GRAY)
                t.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commitNow()
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    /**
     * Xin mien tru toi uu hoa pin (1 lan duy nhat): cong viec kiem
     * tra tap moi chay nen bang WorkManager se bi mot so may tri
     * hoan rat lau neu app bi toi uu pin. Nguoi dung da yeu cau cho
     * phep chay nen nen mo thang man hinh he thong cho ho quyet.
     */
    fun ensureBackgroundAllowed() {
        try {
            val prefs = getSharedPreferences("app_flags", MODE_PRIVATE)
            if (prefs.getBoolean("battery_asked", false)) return
            prefs.edit().putBoolean("battery_asked", true).apply()
            if (Build.VERSION.SDK_INT >= 23) {
                val pm = getSystemService(
                    android.os.PowerManager::class.java
                )
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings
                                .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // May khong ho tro / da duoc mien tru: bo qua
        }
    }

    /** Kiem tra tap moi moi 2 gio (chi khi co mang). */
    private fun scheduleNewEpisodeChecks() {
        val request = PeriodicWorkRequestBuilder<NewEpisodeWorker>(2, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "new_episode_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
