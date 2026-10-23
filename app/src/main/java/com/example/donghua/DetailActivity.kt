package com.example.donghua

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "movie_url"
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_POSTER = "movie_poster"
    }

    private lateinit var db: DbRepo
    private var detail: MovieDetail? = null
    private lateinit var movieUrl: String
    private var currentServer = 0

    private lateinit var rvEpisodes: RecyclerView
    private lateinit var epAdapter: EpisodeAdapter

    inner class EpisodeAdapter(private var items: List<Episode>) :
        RecyclerView.Adapter<EpisodeAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv: TextView = v.findViewById(R.id.tvEpisode)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = items[position]
            holder.tv.text = ep.name
            holder.itemView.setOnClickListener { playEpisode(ep) }
        }

        override fun getItemCount(): Int = items.size

        fun submit(list: List<Episode>) {
            items = list
            notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        db = DbRepo(this)

        movieUrl = HhSource.rebase(intent.getStringExtra(EXTRA_URL) ?: run { finish(); return })
        val preTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val prePoster = intent.getStringExtra(EXTRA_POSTER) ?: ""

        title = preTitle
        findViewById<TextView>(R.id.tvDetailTitle).text = preTitle
        if (prePoster.isNotEmpty()) {
            ImageLoader.cover(findViewById(R.id.ivDetailCover), prePoster)
        }

        rvEpisodes = findViewById(R.id.rvChapters)
        rvEpisodes.layoutManager = GridLayoutManager(this, 4)
        rvEpisodes.itemAnimator = null
        rvEpisodes.setHasFixedSize(true)
        epAdapter = EpisodeAdapter(emptyList())
        rvEpisodes.adapter = epAdapter

        updateFavoriteButton()
        findViewById<Button>(R.id.btnFavorite).setOnClickListener {
            val d = detail ?: return@setOnClickListener
            if (db.isFavorite(movieUrl)) {
                db.removeFavorite(movieUrl)
                Toast.makeText(this, "Đã bỏ yêu thích", Toast.LENGTH_SHORT).show()
            } else {
                db.addFavorite(d.movie, d.totalEpisodes)
                Toast.makeText(this, "Đã thêm vào yêu thích", Toast.LENGTH_SHORT).show()
            }
            updateFavoriteButton()
        }
        // Khoa nut Xem ngay cho den khi tai xong danh sach tap: ban cu
        // de bam duoc trong khi chua co tap nao -> player mo ra do
        // nguon tren trang khong phai trang xem, quay rat lau roi bao loi
        val btnWatch = findViewById<Button>(R.id.btnWatchFirst)
        btnWatch.isEnabled = false
        btnWatch.setOnClickListener {
            val first = detail?.servers?.firstOrNull()?.episodes?.firstOrNull()
            if (first != null) {
                playEpisode(first)
            } else {
                Toast.makeText(
                    this,
                    "Danh sách tập chưa tải xong, chờ chút rồi thử lại nhé",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        loadDetail()
    }

    private fun updateFavoriteButton() {
        findViewById<Button>(R.id.btnFavorite).text =
            if (db.isFavorite(movieUrl)) "★ Đã yêu thích" else "☆ Yêu thích"
    }

    private fun loadDetail() {
        findViewById<ProgressBar>(R.id.detailProgress).visibility = View.VISIBLE
        thread {
            RemoteConfig.awaitReady(2500)
            var d: MovieDetail? = null
            try {
                d = HhSource.fetchDetail(movieUrl)
            } catch (e: Exception) {
                // Bao loi o duoi
            }
            runOnUiThread {
                if (d != null && d.servers.isNotEmpty()) {
                    showDetail(d)
                } else {
                    findViewById<ProgressBar>(R.id.detailProgress).visibility = View.GONE
                    Toast.makeText(
                        this,
                        "Không tải được danh sách tập. Kiểm tra mạng rồi mở lại phim nhé.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showDetail(d: MovieDetail) {
        findViewById<ProgressBar>(R.id.detailProgress).visibility = View.GONE
        findViewById<Button>(R.id.btnWatchFirst).isEnabled = true
        detail = d
        title = d.movie.title
        findViewById<TextView>(R.id.tvDetailTitle).text = d.movie.title
        if (d.movie.poster.isNotEmpty()) {
            ImageLoader.cover(findViewById(R.id.ivDetailCover), d.movie.poster)
        }
        findViewById<TextView>(R.id.tvDetailMeta).text = d.meta
        findViewById<TextView>(R.id.tvDetailDesc).text = d.description
        findViewById<TextView>(R.id.tvChapterCount).text =
            "Danh sách tập (${d.totalEpisodes} tập)"
        if (db.isFavorite(movieUrl)) {
            db.updateFavoriteEpisodeCount(movieUrl, d.totalEpisodes)
        }
        buildServerButtons(d)
        selectServer(0)
        refreshResumeButton(d)
    }

    /**
     * Nut "Xem tiep" nam duoi hang Xem ngay: chi hien khi phim nay
     * da xem do dang (co tien do luu trong DbRepo) va tap do van con
     * trong danh sach hien tai. Bam vao la phat lai dung tap do,
     * nhay thang toi phut da xem (player tu seek).
     */
    private fun refreshResumeButton(d: MovieDetail) {
        val btn = findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnResume
        )
        val p = try {
            db.progressFor(movieUrl)
        } catch (e: Exception) {
            null
        }
        if (p == null || p.epUrl.isEmpty() || p.posSec <= 3 ||
            (p.durSec > 0 && p.posSec >= p.durSec * 0.95)
        ) {
            btn.visibility = android.view.View.GONE
            return
        }
        var foundEp: Episode? = null
        var foundGroup = -1
        for ((gi, g) in d.servers.withIndex()) {
            val e = g.episodes.firstOrNull { it.url == p.epUrl }
            if (e != null) {
                foundEp = e
                foundGroup = gi
                break
            }
        }
        val ep = foundEp
        if (ep == null) {
            btn.visibility = android.view.View.GONE
            return
        }
        val mm = (p.posSec / 60).toInt()
        val ss = (p.posSec % 60).toInt()
        btn.text = "▶ Xem tiếp: ${ep.name} • %d:%02d".format(mm, ss)
        btn.visibility = android.view.View.VISIBLE
        btn.setOnClickListener {
            if (foundGroup >= 0 && foundGroup != currentServer) {
                selectServer(foundGroup)
            }
            playEpisode(ep, p.posSec)
        }
    }

    private fun buildServerButtons(d: MovieDetail) {
        if (d.servers.size <= 1) return
        findViewById<TextView>(R.id.tvServerLabel).visibility = View.VISIBLE
        findViewById<View>(R.id.svServers).visibility = View.VISIBLE
        val ll = findViewById<LinearLayout>(R.id.llServers)
        ll.removeAllViews()
        d.servers.forEachIndexed { idx, server ->
            val b = MaterialButton(this)
            b.text = server.name
            b.setOnClickListener { selectServer(idx) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 8
            b.layoutParams = lp
            ll.addView(b)
        }
    }

    private fun selectServer(idx: Int) {
        val d = detail ?: return
        if (idx !in d.servers.indices) return
        currentServer = idx
        epAdapter.submit(d.servers[idx].episodes)
    }

    /**
     * Bam 1 tap: hoi player.php cua web de lay trang phat embed
     * (streamfree) cho MOI ket hop (ban chieu x kieu server) — ban
     * dang chon gom ca 1080P V1 + V2, cac ban con lai lay kieu dau
     * tien. Tat ca chay song song (~1 giay). Lay duoc thi dua thang
     * cho PlayerActivity (nut "Ban chieu" doi qua lai giua cac nguon
     * nay); khong lay duoc nguon nao thi mo chinh trang xem cua web
     * trong trinh phat da khoa quang cao (du phong cuoi).
     */
    private fun playEpisode(ep: Episode, startSec: Double = 0.0) {
        val d = detail ?: return
        db.addHistory(d.movie, ep.name, ep.url)
        findViewById<ProgressBar>(R.id.detailProgress).visibility = View.VISIBLE
        findViewById<android.widget.Button>(R.id.btnWatchFirst).isEnabled = false
        thread {
            val tasks = ArrayList<Triple<String, Episode, String>>()
            val curGroup = d.servers.getOrNull(currentServer)?.name ?: ""
            for ((type, label) in HhSource.PLAYER_TYPES) {
                tasks.add(Triple("$curGroup • $label", ep, type))
            }
            for (g in d.servers) {
                if (g.name == curGroup) continue
                val same = g.episodes.firstOrNull { it.name == ep.name } ?: continue
                val (type, label) = HhSource.PLAYER_TYPES[0]
                tasks.add(Triple("${g.name} • $label", same, type))
            }
            val resolved = arrayOfNulls<String>(tasks.size)
            val jobs = tasks.mapIndexed { idx, t ->
                thread {
                    resolved[idx] = try {
                        HhSource.resolveEmbed(t.second, t.third)
                    } catch (e: Exception) {
                        ""
                    }
                }
            }
            jobs.forEach { it.join() }
            val ok = tasks.mapIndexedNotNull { idx, t ->
                resolved[idx]?.takeIf { it.isNotEmpty() }?.let { t.first to it }
            }
            runOnUiThread {
                findViewById<ProgressBar>(R.id.detailProgress).visibility = View.GONE
                findViewById<android.widget.Button>(R.id.btnWatchFirst).isEnabled = true
                val i = Intent(this, PlayerActivity::class.java)
                i.putExtra(PlayerActivity.EXTRA_TITLE, d.movie.title)
                i.putExtra(PlayerActivity.EXTRA_EPISODE, ep.name)
                if (startSec > 0) {
                    i.putExtra(PlayerActivity.EXTRA_START_SEC, startSec)
                }
                // Danh sach tap cua nhom ban chieu dang chon: player
                // dung de chuyen tap truoc/sau + mo hop chon tap nhanh
                val groupEps = d.servers.getOrNull(currentServer)?.episodes
                    ?: emptyList()
                val epIdx = groupEps.indexOfFirst { it.url == ep.url }
                if (groupEps.size > 1 && epIdx >= 0) {
                    i.putStringArrayListExtra(
                        PlayerActivity.EXTRA_EP_NAMES,
                        ArrayList(groupEps.map { it.name })
                    )
                    i.putStringArrayListExtra(
                        PlayerActivity.EXTRA_EP_URLS,
                        ArrayList(groupEps.map { it.url })
                    )
                    i.putStringArrayListExtra(
                        PlayerActivity.EXTRA_EP_KEYS,
                        ArrayList(groupEps.map { it.epKey })
                    )
                    i.putExtra(
                        PlayerActivity.EXTRA_EP_POSTIDS,
                        groupEps.map { it.postId }.toLongArray()
                    )
                    i.putExtra(
                        PlayerActivity.EXTRA_EP_SVS,
                        groupEps.map { it.sv }.toIntArray()
                    )
                    i.putExtra(PlayerActivity.EXTRA_EP_INDEX, epIdx)
                    i.putExtra(PlayerActivity.EXTRA_GROUP_NAME, curGroup)
                    i.putExtra(PlayerActivity.EXTRA_MOVIE_URL, d.movie.url)
                    i.putExtra(PlayerActivity.EXTRA_MOVIE_POSTER, d.movie.poster)
                }
                if (ok.isNotEmpty()) {
                    // Nhan cua tung nguon nam trong danh sach kem theo;
                    // PlayerActivity tu doi chieu URL dang phat de biet
                    // nhan cua no (nut "Ban chieu" liet ke ca danh sach)
                    i.putExtra(PlayerActivity.EXTRA_WATCH_URL, ok[0].second)
                    i.putStringArrayListExtra(
                        PlayerActivity.EXTRA_SERVER_NAMES,
                        ArrayList(ok.map { it.first })
                    )
                    i.putStringArrayListExtra(
                        PlayerActivity.EXTRA_SERVER_URLS,
                        ArrayList(ok.map { it.second })
                    )
                } else {
                    // Khong lay duoc nguon embed nao: mo trang xem cua web
                    Toast.makeText(
                        this,
                        "Đang mở bằng trình phát của web",
                        Toast.LENGTH_SHORT
                    ).show()
                    i.putExtra(PlayerActivity.EXTRA_WATCH_URL, ep.url)
                    i.putStringArrayListExtra(
                        PlayerActivity.EXTRA_SERVER_NAMES,
                        arrayListOf(curGroup)
                    )
                    i.putStringArrayListExtra(
                        PlayerActivity.EXTRA_SERVER_URLS,
                        arrayListOf(ep.url)
                    )
                }
                startActivity(i)
            }
        }
    }
}
