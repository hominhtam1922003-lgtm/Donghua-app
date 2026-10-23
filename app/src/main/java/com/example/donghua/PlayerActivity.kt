package com.example.donghua

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.LayoutInflater
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

/**
 * Trinh phat video — ban 2.0 (nguon cobephim qua API).
 * Dau vao la trang phat CHINH THUC cua web: {host API}/embed/{tap}.
 * Thu tu uu tien:
 * 1) Bo do chay trang embed trong WebView ngam va BAT MOI REQUEST
 *    .m3u8/.mp4 o tang mang; luong bat duoc phai qua kiem tra thoi
 *    luong (chong quang cao mo dau) roi phat bang player sach noi bo
 *    cua app (HLS con qua PlaylistCleaner loc quang cao ghep).
 * 2) Khong bat duoc luong thang: phat bang chinh trang embed chinh
 *    thuc do trong khung da khoa (cam popup, khoa dieu huong sang
 *    host la, AdBlock o tang mang). Day la player cua chinh web nguon
 *    (khong phai iframe quang cao cua ben thu ba nhu playembed xua).
 * Nut "Doi ban" chuyen giua cac luong bat duoc va cac BAN CHIEU khac
 * (Phu de / Thuyet Minh / Long tieng) cua cung 1 tap.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WATCH_URL = "watch_url"
        const val EXTRA_TITLE = "movie_title"
        const val EXTRA_EPISODE = "episode_name"
        // Cac ban chieu khac cua cung 1 tap (nhan + trang phat embed),
        // DetailActivity gui kem de doi ban ngay trong player
        const val EXTRA_SERVER_NAMES = "server_names"
        const val EXTRA_SERVER_URLS = "server_urls"
        // Danh sach tap cua nhom ban chieu dang xem (DetailActivity gui
        // kem): player dung de chuyen tap truoc/sau + chon tap nhanh
        const val EXTRA_EP_NAMES = "ep_names"
        const val EXTRA_EP_URLS = "ep_urls"
        const val EXTRA_EP_KEYS = "ep_keys"
        const val EXTRA_EP_POSTIDS = "ep_postids"
        const val EXTRA_EP_SVS = "ep_svs"
        const val EXTRA_EP_INDEX = "ep_index"
        const val EXTRA_GROUP_NAME = "group_name"
        const val EXTRA_MOVIE_URL = "movie_url"
        const val EXTRA_MOVIE_POSTER = "movie_poster"

        // Do tim luong phat tren trang xem dang chay that: dia chi + THOI
        // LUONG cua the <video> + moi tai nguyen .m3u8/.mp4 trang da tai
        // + iframe (neu co). Thoi luong la chìa khoa phan biet phim that
        // (hang gio) voi quang cao mo dau (chi ~50 giay) - ban 1.4 bat
        // nham quang cao co bac Hi88 phat cho nguoi dung vi thieu dau nay.
        private const val PROBE_JS = """
            (function(){
              var out = {src:'', dur:0, res:[], ifr:''};
              try {
                var v = document.querySelector('video');
                if (v) {
                  out.src = v.currentSrc || v.src || '';
                  out.dur = (v.duration && isFinite(v.duration)) ? v.duration : 0;
                }
                var es = performance.getEntriesByType('resource');
                for (var i=0;i<es.length;i++){
                  var n = es[i].name || '';
                  if (n.indexOf('.m3u8')>=0 || n.indexOf('.mp4')>=0) out.res.push(n);
                }
                var f = document.querySelector('iframe');
                if (f) out.ifr = f.src || '';
              } catch(e){}
              return JSON.stringify(out);
            })()
        """

        // Bam nut "Bo qua" quang cao trong player cua web (neu co)
        private const val SKIP_AD_JS = """
            (function(){
              var els = document.querySelectorAll('a,button,div,span');
              for (var i=0;i<els.length;i++){
                var t = (els[i].textContent||'').trim().toLowerCase();
                if (t.length < 40 && (t.indexOf('bỏ qua')>=0 || t.indexOf('skip')>=0)){
                  try{ els[i].click(); return 'clicked'; }catch(e){}
                }
              }
              return 'none';
            })()
        """

        // Bam nut hien player cua trang xem (mot so trang chi tao
        // iframe/player sau khi nguoi dung bam nut nay) de player ben
        // trong chiu tai luong video that -> bo do bat duoc o tang mang
        private const val PLAY_TRIGGER_JS = """
            (function(){
              var b = document.querySelector(
                '#btnShowPlayer,[id*=showPlayer],[id*=show-player],' + '#hrm-player,.jw-icon-display,.jw-display,' +
                '[class*=showPlayer],[class*=show-player]');
              if (b){ try{ b.click(); return 'clicked'; }catch(e){} }
              return 'none';
            })()
        """

        // Tat tieng VINH VIEN moi the video/audio trong WebView do nguon
        // (ke ca the moi tao sau nay): ban 1.4.1/1.4.2 de WebView ngam tu
        // phat ca TIENG cua quang cao co bac trong luc do - nguoi dung
        // thay man hinh den ma nghe tieng quang cao rat phan cam
        private const val MUTE_JS = """
            (function(){
              function muteAll(){
                var ms = document.querySelectorAll('video,audio');
                for (var i=0;i<ms.length;i++){
                  try{ ms[i].muted = true; ms[i].volume = 0; }catch(e){}
                }
              }
              muteAll();
              if (!window.__museMute){
                window.__museMute = true;
                setInterval(muteAll, 300);
              }
              return 'ok';
            })()
        """
    }

    /**
     * An thanh tren cung cua trang phat embed (#player_top: ten phim +
     * thong tin tap + nut danh sach tap) sau ~3 giay khong cham; cham
     * vao man hinh thi hien lai. Thanh nay cua web chi tu an theo
     * chuot may tinh nen trong WebView dien thoai no dinh vinh vien.
     */
    private val CHROME_JS = """
        (function(){
          if (window.__donghuaChrome) return 'ok';
          window.__donghuaChrome = true;
          var timer = null;
          function bar(){ return document.getElementById('player_top'); }
          function hide(){
            timer = null;
            var el = bar(); if (!el) return;
            el.style.transition = 'opacity .35s ease';
            el.style.opacity = '0';
            el.style.pointerEvents = 'none';
          }
          function show(){
            var el = bar(); if (!el) return;
            el.style.transition = 'opacity .35s ease';
            el.style.opacity = '1';
            el.style.pointerEvents = 'auto';
            if (timer) clearTimeout(timer);
            timer = setTimeout(hide, 3000);
          }
          document.addEventListener('touchstart', show, true);
          document.addEventListener('click', show, true);
          setTimeout(show, 800);
          setInterval(function(){
            var el = bar();
            if (el && el.style.opacity !== '0' && !timer) show();
          }, 4000);
          return 'ok';
        })()
    """.trimIndent()

    private enum class Kind { HLS, DIRECT, EMBEDPAGE, WATCHPAGE }
    private data class Candidate(val kind: Kind, val url: String, val label: String = "")

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var btnServer: Button
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var targetHost: String = ""
    private var watchUrl: String = ""
    // Cac ban chieu khac cua cung tap (trang embed + nhan ban chieu)
    private var versionUrls: List<String> = emptyList()
    private var versionNames: List<String> = emptyList()
    private var candidates: List<Candidate> = emptyList()
    private var candidateIndex = 0
    private var errorToastShown = false

    // Ban chieu nguoi dung da chon o man hinh chi tiet (vd "Vietsub").
    // Viec tu nhay server khi loi chi duoc phep trong CUNG ban chieu
    // nay — khong bao gio tu y dua nguoi xem sang ban chieu khac.
    private var primaryVersion: String = ""

    // Dieu huong tap ngay trong player (DetailActivity gui kem danh
    // sach tap cua nhom ban chieu dang xem)
    private lateinit var epBar: android.widget.LinearLayout
    private lateinit var btnPrevEp: Button
    private lateinit var btnNextEp: Button
    private lateinit var btnEpList: Button
    private var epNames: List<String> = emptyList()
    private var epWatchUrls: List<String> = emptyList()
    private var epKeys: List<String> = emptyList()
    private var epPostIds: LongArray = LongArray(0)
    private var epSvs: IntArray = IntArray(0)
    private var epIndex: Int = 0
    private var groupName: String = ""
    private var movieTitle: String = ""
    private var moviePageUrl: String = ""
    private var moviePoster: String = ""
    private val db by lazy { DbRepo(this) }
    // Danh dau moi lan doi ung vien: ket qua loc playlist (chay nen)
    // chi duoc ap dung neu chua doi sang ung vien khac
    private var playToken = 0
    private val chromeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hideServerRunnable: Runnable? = null

    /** Nut doi server: hien roi tu an sau 3,5 giay; cham man hinh hien lai. */
    private fun showServerButton() {
        hideServerRunnable?.let { chromeHandler.removeCallbacks(it) }
        if (customView != null || !::epBar.isInitialized) {
            btnServer.visibility = View.GONE
            if (::epBar.isInitialized) epBar.visibility = View.GONE
            return
        }
        btnServer.visibility =
            if (candidates.size > 1) View.VISIBLE else View.GONE
        epBar.visibility =
            if (epNames.size > 1) View.VISIBLE else View.GONE
        val r = Runnable {
            btnServer.visibility = View.GONE
            epBar.visibility = View.GONE
        }
        hideServerRunnable = r
        chromeHandler.postDelayed(r, 3500)
    }

    /** Cap nhat trang thai 2 nut chuyen tap (mo di o 2 dau danh sach). */
    private fun refreshEpButtons() {
        if (!::btnPrevEp.isInitialized) return
        btnPrevEp.isEnabled = epIndex > 0
        btnPrevEp.alpha = if (epIndex > 0) 0.85f else 0.35f
        btnNextEp.isEnabled = epIndex < epNames.size - 1
        btnNextEp.alpha = if (epIndex < epNames.size - 1) 0.85f else 0.35f
    }

    /**
     * Chuyen sang tap khac NGAY trong player, giu nguyen ban chieu
     * dang xem: lay nguon phat cua tap moi (ca 2 server V1/V2 cua ban
     * do) roi phat lai tu dau. Khong phai quay ve man hinh chi tiet.
     */
    private fun loadEpisode(index: Int) {
        if (index < 0 || index >= epNames.size || index == epIndex) return
        epIndex = index
        refreshEpButtons()
        val ep = Episode(
            epNames[index],
            epWatchUrls.getOrElse(index) { "" },
            epPostIds.getOrElse(index) { 0L },
            epKeys.getOrElse(index) { "" },
            epSvs.getOrElse(index) { 0 }
        )
        if (moviePageUrl.isNotEmpty()) {
            try {
                db.addHistory(
                    Movie(movieTitle, moviePageUrl, moviePoster, ""),
                    ep.name, ep.url
                )
            } catch (e: Exception) {
                // Lich su la phu: loi thi bo qua, phat phim la chinh
            }
        }
        title = if (ep.name.isNotEmpty()) "$movieTitle - ${ep.name}" else movieTitle
        Toast.makeText(this, "Đang chuyển sang ${ep.name}...", Toast.LENGTH_SHORT).show()
        progress.visibility = View.VISIBLE
        thread {
            val resolved = ArrayList<Pair<String, String>>()
            for ((type, label) in HhSource.PLAYER_TYPES) {
                val u = try {
                    HhSource.resolveEmbed(ep, type)
                } catch (e: Exception) {
                    ""
                }
                if (u.isNotEmpty()) resolved.add("$groupName • $label" to u)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                errorToastShown = false
                candidates = emptyList()
                candidateIndex = 0
                if (resolved.isNotEmpty()) {
                    versionNames = resolved.map { it.first }
                    versionUrls = resolved.map { it.second }
                    watchUrl = resolved[0].second
                } else {
                    versionNames = listOf(groupName)
                    versionUrls = listOf(ep.url)
                    watchUrl = ep.url
                }
                try { webView.stopLoading() } catch (e: Exception) { }
                resolveAndPlay()
                showServerButton()
            }
        }
    }

    /** Hop chon tap nhanh: luoi cac tap cua ban chieu dang xem. */
    private fun showEpisodePicker() {
        if (epNames.isEmpty()) return
        val rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, 4)
        rv.itemAnimator = null
        rv.setPadding(24, 24, 24, 24)
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Chọn tập")
            .setView(rv)
            .setNegativeButton("Đóng", null)
            .create()
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(
                parent: ViewGroup, viewType: Int
            ): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_episode, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun getItemCount(): Int = epNames.size

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val tv = h.itemView.findViewById<TextView>(R.id.tvEpisode)
                tv.text = epNames[pos]
                if (pos == epIndex) {
                    tv.setBackgroundColor(0xFFE50914.toInt())
                    tv.setTextColor(0xFFFFFFFF.toInt())
                } else {
                    tv.setBackgroundColor(0x00000000)
                    tv.setTextColor(0xFFF4F4F6.toInt())
                }
                h.itemView.setOnClickListener {
                    dlg.dismiss()
                    loadEpisode(pos)
                }
            }
        }
        dlg.show()
        (rv.layoutManager as? GridLayoutManager)?.scrollToPosition(epIndex)
    }

    private fun cancelServerHide() {
        hideServerRunnable?.let { chromeHandler.removeCallbacks(it) }
        hideServerRunnable = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        // Full man hinh that: tat thanh tieu de cua app + an thanh trang
        // thai/dieu huong cua he thong (vuot canh de hien lai tam thoi).
        // Ban <=1.3 de sot action bar o man nay nen video bi mat khong gian.
        supportActionBar?.hide()
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller =
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            // May cu khong ho tro: bo qua
        }

        watchUrl = HhSource.rebase(
            intent.getStringExtra(EXTRA_WATCH_URL) ?: run { finish(); return }
        )
        versionUrls = intent.getStringArrayListExtra(EXTRA_SERVER_URLS) ?: emptyList()
        versionNames = intent.getStringArrayListExtra(EXTRA_SERVER_NAMES) ?: emptyList()
        movieTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        epNames = intent.getStringArrayListExtra(EXTRA_EP_NAMES) ?: emptyList()
        epWatchUrls = intent.getStringArrayListExtra(EXTRA_EP_URLS) ?: emptyList()
        epKeys = intent.getStringArrayListExtra(EXTRA_EP_KEYS) ?: emptyList()
        epPostIds = intent.getLongArrayExtra(EXTRA_EP_POSTIDS) ?: LongArray(0)
        epSvs = intent.getIntArrayExtra(EXTRA_EP_SVS) ?: IntArray(0)
        epIndex = intent.getIntExtra(EXTRA_EP_INDEX, 0)
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: ""
        moviePageUrl = intent.getStringExtra(EXTRA_MOVIE_URL) ?: ""
        moviePoster = intent.getStringExtra(EXTRA_MOVIE_POSTER) ?: ""
        val epName = intent.getStringExtra(EXTRA_EPISODE) ?: ""
        title = if (epName.isNotEmpty()) "$movieTitle - $epName" else movieTitle

        progress = findViewById(R.id.playerProgress)
        btnServer = findViewById(R.id.btnServer)
        btnServer.setOnClickListener { nextCandidate(auto = false) }
        epBar = findViewById(R.id.epBar)
        btnPrevEp = findViewById(R.id.btnPrevEp)
        btnNextEp = findViewById(R.id.btnNextEp)
        btnEpList = findViewById(R.id.btnEpList)
        btnPrevEp.setOnClickListener { loadEpisode(epIndex - 1) }
        btnNextEp.setOnClickListener { loadEpisode(epIndex + 1) }
        btnEpList.setOnClickListener { showEpisodePicker() }
        refreshEpButtons()
        webView = findViewById(R.id.wvPlayer)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = HhSource.UA
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // Cham vao man hinh phat -> hien lai nut doi server (no se tu
        // an sau vai giay de khoi dinh man hinh). Tra false de khong
        // chan su kien cham cua chinh trang phat (tam dung/phat tiep).
        webView.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                showServerButton()
            }
            false
        }
        // Trang HLS noi bo bao loi phat ve day de tu doi server
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onError() {
                runOnUiThread { nextCandidate(auto = true) }
            }
        }, "PlayerBridge")

        webView.webChromeClient = object : WebChromeClient() {
            // Cam popup: quang cao khong mo duoc tab/trang moi
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customView = view
                customCallback = callback
                if (view != null) {
                    findViewById<FrameLayout>(R.id.playerRoot).addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                webView.visibility = View.GONE
                cancelServerHide()
                btnServer.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let {
                    findViewById<FrameLayout>(R.id.playerRoot).removeView(it)
                }
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                webView.visibility = View.VISIBLE
                showServerButton()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                return if (AdBlock.isAd(url)) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    null
                }
            }

            // Khoa khung chinh: khong cho dieu huong sang host la (trang QC)
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                if (request.isForMainFrame) {
                    val host = url.host ?: ""
                    if (targetHost.isNotEmpty() && host != targetHost) return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                // Cai co che tu an cho thanh ten phim cua trang embed
                // (trang noi bo cua app khong co #player_top nen no-op)
                view?.evaluateJavascript(CHROME_JS, null)
                // EMBED gio nam trong iframe khac goc (wrapper) nen khong
                // cham duoc DOM ben trong nua; chan quang cao o tang mang
                // (AdBlock) van ap dung cho moi request cua player.
                Unit
            }
        }

        resolveAndPlay()
    }

    /** Trang noi bo phat file video truc tiep (.mp4) bang the video chuan. */
    private fun directHtml(src0: String): String {
        val src = src0.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;background:#000;height:100%}video{width:100%;height:100%}</style>
            </head><body>
            <video id="v" controls autoplay playsinline src="$src"></video>
            <script>
              var v = document.getElementById('v');
              v.addEventListener('error', function(){
                if (window.PlayerBridge) { PlayerBridge.onError(); }
              });
            </script>
            </body></html>
        """.trimIndent()
    }

    /**
     * Diem vao phat (ban 2.0): watchUrl la trang phat embed chinh thuc
     * cua web ({host API}/embed/{id tap}) — chay bo do tren chinh trang
     * do de bat luong thang. Neu dau vao la file video thang thi phat
     * ngay; URL la (lich su cu cua nguon xua) thi bao ro, khong do nua.
     */
    private fun resolveAndPlay() {
        primaryVersion = versionNameOf(watchUrl)
            ?.substringBefore(" • ")?.trim() ?: ""
        progress.visibility = View.VISIBLE
        val path = watchUrl.substringBefore("?").lowercase()
        when {
            path.endsWith(".m3u8") -> {
                candidates = listOf(Candidate(Kind.HLS, watchUrl, "Luồng trực tiếp"))
                playCandidate(0)
            }
            path.endsWith(".mp4") -> {
                candidates = listOf(Candidate(Kind.DIRECT, watchUrl, "Luồng trực tiếp"))
                playCandidate(0)
            }
            watchUrl.contains("/embed/") -> probeRuntime()
            watchUrl.contains("/watch-") -> {
                // Du phong cuoi (DetailActivity khong lay duoc nguon
                // embed nao): phat bang CHINH trang xem cua web —
                // player cua web nam san trong iframe cua no nen phat
                // duoc; khung chinh bi khoa theo host cua web, popup
                // quang cao bi cam, AdBlock chan o tang mang.
                candidates = listOf(
                    Candidate(
                        Kind.WATCHPAGE, watchUrl,
                        versionNameOf(watchUrl) ?: "Trang web"
                    )
                )
                playCandidate(0)
            }
            else -> showNoSource()
        }
    }

    /**
     * Boc trang embed trong 1 iframe toan man hinh. BAT BUOC voi host
     * phat streamfree cua hhkungfu: player cua no CHI cho phat khi
     * trang embed nam ben trong iframe cua mot trang khac — mo thang
     * trang embed o cap cao nhat se bi bao loi "video nay chi co the
     * phat trong iframe (-2)" (loi da gap tren may that o ban 1.0).
     * Base URL cua trang boc dat la goc web hhkungfu nen iframe thua
     * huong dung ngu canh (Referer/origin) cua web goc.
     */
    private fun embedWrapperHtml(embedUrl: String): String {
        val src = embedUrl.replace("&", "&amp;").replace("\"", "&quot;")
        return """
            <!DOCTYPE html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            <style>
              html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
              iframe{position:absolute;inset:0;width:100%;height:100%;border:0}
            </style>
            </head><body>
            <iframe src="$src" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen></iframe>
            </body></html>
        """.trimIndent()
    }

    /**
     * Do luong phat TRUC TIEP: chay trang xem trong WebView ngam, player
     * rieng cua web se tu nap video (ke ca quang cao mo dau cua no bi
     * AdBlock chan); minh bat dia chi luong that qua the <video> va danh
     * sach tai nguyen trang da tai, roi phat bang player sach cua app.
     * Ap dung cho nhom phim nhu "Ca Voi Nuot Chung" - khong iframe,
     * khong data-embed, nguon chi lo ra luc trang dang chay.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun probeRuntime() {
        progress.visibility = View.VISIBLE
        // Dong chu trang thai giua man hinh: giai doan do nguon co the
        // keo dai vai giay, de man hinh den thui + vong xoay khong thi
        // nguoi dung tuong app treo (kem tieng quang cao ro ri cua ban cu)
        val statusTv = android.widget.TextView(this)
        statusTv.text =
            "Đang tìm nguồn phát sạch…\n" +
                "Đang tự bỏ qua quảng cáo mở đầu của web, chờ chút nhé"
        statusTv.setTextColor(android.graphics.Color.WHITE)
        statusTv.textSize = 15f
        statusTv.gravity = android.view.Gravity.CENTER
        statusTv.setPadding(56, 56, 56, 56)
        try {
            findViewById<FrameLayout>(R.id.playerRoot).addView(
                statusTv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
        } catch (e: Exception) {
            // bo qua
        }
        val probe = WebView(applicationContext)
        try {
            android.webkit.CookieManager.getInstance()
                .setAcceptThirdPartyCookies(probe, true)
        } catch (e: Exception) {
            // bo qua
        }
        with(probe.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = HhSource.UA
        }
        // BAT LUONG O TANG MANG (moi o ban 1.5): moi request tai file
        // video (.m3u8/.mp4) tu BAT KY frame nao trong trang - ke ca
        // tu ben trong iframe playembed - deu bi ghi lai o day.
        // shouldInterceptRequest chay tren luong nen nen dung tap hop
        // dong bo; chi QUAN SAT roi cho request di tiep, khong chan.
        val netHls = java.util.Collections.synchronizedSet(
            java.util.LinkedHashSet<String>()
        )
        val netMp4 = java.util.Collections.synchronizedSet(
            java.util.LinkedHashSet<String>()
        )
        val lastNetAt = java.util.concurrent.atomic.AtomicLong(0)
        val watchHost = hostOf(watchUrl)
        probe.webChromeClient = object : WebChromeClient() {
            // Cam popup trong WebView do (QC khong mo trang moi)
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false
        }
        probe.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val u = request?.url?.toString() ?: return null
                if (AdBlock.isAd(u)) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                val path = u.substringBefore("?").lowercase()
                if (path.endsWith(".m3u8")) {
                    netHls.add(u)
                    lastNetAt.set(System.currentTimeMillis())
                } else if (path.endsWith(".mp4")) {
                    netMp4.add(u)
                    lastNetAt.set(System.currentTimeMillis())
                }
                return null
            }

            // Khoa khung chinh cua WebView do: chi o lai host trang
            // phat + cac host luong/phat cua web, khong bi popunder
            // cuop trang sang trang quang cao
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                if (request.isForMainFrame) {
                    val h = url.host ?: ""
                    val ok = h == watchHost || h.endsWith("streamfree.vip") ||
                        h.endsWith("hhkungfu.ee") ||
                        h.endsWith("googleapis.com") ||
                        h.endsWith("cloudflare.com") || h.isEmpty()
                    if (!ok) return true
                }
                return false
            }

            // Trang vua tai xong la tat tieng ngay (va lap lai lien tuc)
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(MUTE_JS, null)
            }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var probeDone = false
        val probeStart = System.currentTimeMillis()
        // Trang thai do duoc sau moi vong:
        // - adUrls: cac luong da xac nhan la QUANG CAO (video dang phat
        //   chung co thoi luong ngan < 180 giay) -> khong bao gio phat
        // - movieUrl: luong cua the video co thoi luong >= 180 giay
        // - resHls/resMp4: luong thay trong danh sach tai nguyen
        val adUrls = HashSet<String>()
        var movieUrl = ""
        var resHls = ""
        var resMp4 = ""
        var lastSrc = ""
        var triggerTries = 0

        fun finishProbe() {
            if (probeDone) return
            probeDone = true
            try {
                (statusTv.parent as? ViewGroup)?.removeView(statusTv)
            } catch (e: Exception) {
                // bo qua
            }
            try {
                probe.destroy()
            } catch (e: Exception) {
                // bo qua
            }
            val list = ArrayList<Candidate>()
            fun addStream(u: String) {
                if (u.isEmpty() || adUrls.contains(u)) return
                if (list.any { it.url == u }) return
                if (u.contains(".m3u8")) {
                    list.add(Candidate(Kind.HLS, u))
                } else if (u.contains(".mp4")) {
                    list.add(Candidate(Kind.DIRECT, u))
                }
            }
            if (movieUrl.isNotEmpty()) {
                // Da xac nhan bang thoi luong >= 180 giay: phat dung no
                addStream(movieUrl)
            } else {
                // Uu tien: luong bat o tang mang MUON nhat (player
                // thuong tai quang cao truoc roi moi chot luong phim),
                // luong the video gan nhat, playlist trang da tai san,
                // luong mang som nhat lam du phong doi server, MP4 cuoi
                val nets = synchronized(netHls) { ArrayList(netHls) }
                    .filter { !adUrls.contains(it) }
                if (nets.isNotEmpty()) addStream(nets.last())
                addStream(lastSrc)
                addStream(resHls)
                if (nets.size > 1) addStream(nets.first())
                addStream(resMp4)
                val mp4First = synchronized(netMp4) { netMp4.firstOrNull() } ?: ""
                addStream(mp4First)
            }
            // Ban 2.0: co luong thang thi phat luong (sach nhat); kem
            // theo trang embed cua ban dang xem + cac ban chieu khac
            // lam ung vien doi ban. Khong bat duoc luong nao -> phat
            // bang chinh trang embed chinh thuc (da khoa popup/dieu
            // huong/chan quang cao o tang mang) chu khong con bao
            // "khong co nguon" nhu thoi nguon cu.
            val out = ArrayList<Candidate>()
            for (c in list) out.add(Candidate(c.kind, c.url, "Luồng trực tiếp"))
            out.add(
                Candidate(
                    Kind.EMBEDPAGE, watchUrl,
                    versionNameOf(watchUrl) ?: "Bản đang xem"
                )
            )
            for (u in versionUrls) {
                if (u == watchUrl) continue
                if (out.any { it.url == u }) continue
                out.add(Candidate(Kind.EMBEDPAGE, u, versionNameOf(u) ?: "Bản khác"))
            }
            candidates = out
            playCandidate(0)
        }

        fun pollProbe() {
            if (probeDone) return
            // Moi vong deu tat tieng lai cho chac (the video moi tao)
            probe.evaluateJavascript(MUTE_JS, null)
            probe.evaluateJavascript(PROBE_JS) { result ->
                if (probeDone) return@evaluateJavascript
                try {
                    val raw = org.json.JSONTokener(result ?: "null")
                        .nextValue()?.toString() ?: ""
                    val o = org.json.JSONObject(raw)
                    val src = o.optString("src")
                    val dur = o.optDouble("dur", 0.0)
                    if (src.startsWith("http") &&
                        (src.contains(".m3u8") || src.contains(".mp4"))
                    ) {
                        lastSrc = src
                        if (dur >= 180) {
                            movieUrl = src
                        } else if (dur > 0) {
                            // Luong ngan dang phat = quang cao mo dau:
                            // ghi nho de tranh, thu bam nut bo qua QC.
                            // (Ban 1.4.3 co tua nhanh QC - da BO o 1.4.4
                            // vi mot so player phat hien tua se phat lai
                            // QC tu dau, do do cang lau; tra ve dung
                            // hanh vi ban 1.4.1 xem muot.)
                            adUrls.add(src)
                            probe.evaluateJavascript(SKIP_AD_JS, null)
                        }
                    }
                    val res = o.optJSONArray("res")
                    if (res != null) {
                        for (i in 0 until res.length()) {
                            val u = res.optString(i)
                            if (AdBlock.isAd(u) || adUrls.contains(u)) continue
                            if (u.contains(".m3u8") && resHls.isEmpty()) resHls = u
                            else if (u.contains(".mp4") && resMp4.isEmpty()) resMp4 = u
                        }
                    }
                } catch (e: Exception) {
                    // Chua doc duoc: thu lai sau
                }
                val elapsed = System.currentTimeMillis() - probeStart
                // Trang chua tu tao player thi bam nut hien player cua
                // no (toi da 2 lan) de luong that lo ra o tang mang
                if (triggerTries < 2 &&
                    ((elapsed > 4000 && triggerTries == 0) ||
                        (elapsed > 16000 && triggerTries == 1))
                ) {
                    triggerTries++
                    probe.evaluateJavascript(PLAY_TRIGGER_JS, null)
                }
                val netCount = synchronized(netHls) { netHls.size }
                val netQuiet = lastNetAt.get() > 0 &&
                    System.currentTimeMillis() - lastNetAt.get() > 8000
                when {
                    // Da thay phim that (dai >= 180 giay): phat ngay
                    movieUrl.isNotEmpty() -> finishProbe()
                    // Player da tai san playlist phim (khac luong quang
                    // cao dang phat) va da quan sat du lau: dung luon
                    resHls.isNotEmpty() && !adUrls.contains(resHls) &&
                        adUrls.isNotEmpty() && elapsed > 15000 -> finishProbe()
                    // Luong o tang mang da on dinh (8 giay khong co
                    // luong moi): player da chot nguon -> phat
                    netCount > 0 && netQuiet && elapsed > 12000 -> finishProbe()
                    // 20 giay khong thay bat ky luong nao: trang nay
                    // khong lo nguon thang -> dung do som, phat bang
                    // trang embed ngay khoi phai cho 45-50 giay
                    elapsed > 20000 && netCount == 0 &&
                        resHls.isEmpty() && resMp4.isEmpty() -> finishProbe()
                    elapsed > 45000 -> finishProbe()
                    else -> handler.postDelayed({ pollProbe() }, 2000)
                }
            }
        }

        // Nap trang embed QUA trang boc iframe (ly do o embedWrapperHtml):
        // nap thang thi player cua host phat tu choi khoi tao, khong
        // bao gio lo luong that ra tang mang.
        probe.loadDataWithBaseURL(
            HhSource.BASE + "/", embedWrapperHtml(watchUrl),
            "text/html", "utf-8", null
        )
        handler.postDelayed({ pollProbe() }, 2500)
        handler.postDelayed({ finishProbe() }, 50000)
    }

    /** Khong boc duoc nguon sach o moi duong -> bao ro, khong mo trang web. */
    private fun showNoSource() {
        progress.visibility = View.GONE
        Toast.makeText(
            this,
            "Chưa lấy được nguồn phát sạch cho tập này. Thử bản chiếu hoặc tập khác nhé.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    /** Nhan ban chieu cua 1 trang phat (neu DetailActivity co gui kem). */
    private fun versionNameOf(url: String): String? {
        val idx = versionUrls.indexOf(url)
        return if (idx >= 0) versionNames.getOrNull(idx) else null
    }

    private fun playCandidate(index: Int) {
        if (index !in candidates.indices) return
        candidateIndex = index
        val c = candidates[index]
        btnServer.text = if (c.label.isNotEmpty()) {
            "${c.label} • ${index + 1}/${candidates.size} ⟳"
        } else {
            "Server ${index + 1}/${candidates.size} ⟳"
        }
        showServerButton()
        progress.visibility = View.VISIBLE
        when (c.kind) {
            Kind.EMBEDPAGE -> {
                // Phat trang embed cua web NHUNG boc trong iframe cua
                // trang noi bo (xem embedWrapperHtml — mo thang trang
                // embed se bi host phat bao loi -2). Iframe ke thua
                // ngu canh web goc nho base URL; popup quang cao bi
                // cam, AdBlock chan o tang mang.
                targetHost = ""
                webView.loadDataWithBaseURL(
                    HhSource.BASE + "/", embedWrapperHtml(c.url),
                    "text/html", "utf-8", null
                )
                // Cai lai co che an thanh ten phim sau khi trang on
                // dinh (DOM cua trang embed co the dung lai muon)
                chromeHandler.postDelayed({
                    if (!isFinishing) webView.evaluateJavascript(CHROME_JS, null)
                }, 2000)
            }
            Kind.WATCHPAGE -> {
                // Trang xem chinh thuc cua web trong khung da khoa:
                // khoa khung chinh theo host cua web (khong bi dan
                // sang trang quang cao), cam popup, AdBlock tang mang
                targetHost = hostOf(c.url)
                webView.loadUrl(
                    c.url,
                    mapOf(
                        "User-Agent" to HhSource.UA,
                        "Referer" to HhSource.BASE + "/"
                    )
                )
            }
            Kind.HLS -> {
                targetHost = ""
                // Loc quang cao ghep trong playlist truoc khi phat
                // (chay nen; playlist sach/khong loc duoc thi phat
                // thang nhu cu - xem PlaylistCleaner)
                val token = ++playToken
                thread {
                    val res = PlaylistCleaner.clean(c.url, watchUrl)
                    runOnUiThread {
                        if (token == playToken && !isFinishing) {
                            webView.loadDataWithBaseURL(
                                HhSource.BASE + "/",
                                hlsHtml(res.playUrl, res.overrideText),
                                "text/html", "utf-8", null
                            )
                        }
                    }
                }
            }
            Kind.DIRECT -> {
                targetHost = ""
                webView.loadDataWithBaseURL(
                    HhSource.BASE + "/", directHtml(c.url), "text/html", "utf-8", null
                )
            }
        }
    }

    /** Khoa ban chieu cua 1 nguon phat: luong thang ke thua ban
     * chieu chinh nguoi dung da chon; nguon embed lay tu nhan. */
    private fun versionKeyOf(c: Candidate): String {
        if (c.kind == Kind.HLS || c.kind == Kind.DIRECT) return primaryVersion
        return c.label.substringBefore(" • ").trim()
    }

    private fun nextCandidate(auto: Boolean) {
        if (auto) {
            // Tu nhay CHI trong cung ban chieu: V1 -> V2 cua ban do,
            // hoac luong thang -> trang embed cua chinh ban do. Het
            // server trong ban thi bao loi, khong tu nhay sang ban khac.
            if (candidates.size > 1) {
                val curKey = versionKeyOf(candidates[candidateIndex])
                val next = (1 until candidates.size)
                    .map { (candidateIndex + it) % candidates.size }
                    .firstOrNull { versionKeyOf(candidates[it]) == curKey }
                if (next != null) {
                    Toast.makeText(
                        this, "Server lỗi, đang đổi server khác...",
                        Toast.LENGTH_SHORT
                    ).show()
                    playCandidate(next)
                    return
                }
            }
            if (!errorToastShown) {
                errorToastShown = true
                Toast.makeText(
                    this,
                    "Bản chiếu này đang lỗi. Bấm nút đổi bản để chọn bản chiếu khác nhé.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        if (candidates.size <= 1) return
        playCandidate((candidateIndex + 1) % candidates.size)
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * Trang HTML noi bo phat HLS bang hls.js; loi thi bao ve
     * PlayerBridge. Neu overrideText co gia tri (playlist da duoc
     * PlaylistCleaner cat quang cao ghep), nap playlist tu blob noi
     * dung do thay vi tu URL goc.
     */
    private fun hlsHtml(m3u8: String, overrideText: String?): String {
        val src = m3u8.replace("\\", "\\\\").replace("\"", "\\\"")
        val overrideJs = if (overrideText != null) {
            "var pl = " + org.json.JSONObject.quote(overrideText) + ";" +
                "src = URL.createObjectURL(" +
                "new Blob([pl], {type:'application/vnd.apple.mpegurl'}));"
        } else {
            ""
        }
        return """
            <!DOCTYPE html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;background:#000;height:100%}video{width:100%;height:100%}</style>
            <script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js"></script>
            </head><body>
            <video id="v" controls autoplay playsinline></video>
            <script>
              var v = document.getElementById('v');
              var src = "$src";
              $overrideJs
              function fail(){ if (window.PlayerBridge) { PlayerBridge.onError(); } }
              if (window.Hls && Hls.isSupported()) {
                var h = new Hls();
                h.loadSource(src);
                h.attachMedia(v);
                h.on(Hls.Events.ERROR, function(e, d){ if (d && d.fatal) { fail(); } });
              } else if (v.canPlayType('application/vnd.apple.mpegurl')) {
                v.src = src;
                v.addEventListener('error', fail);
              } else {
                fail();
              }
            </script>
            </body></html>
        """.trimIndent()
    }

    override fun onBackPressed() {
        if (customView != null) {
            webView.webChromeClient?.onHideCustomView()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            webView.onPause()
        } catch (e: Exception) {
            // bo qua
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            webView.onResume()
        } catch (e: Exception) {
            // bo qua
        }
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (e: Exception) {
            // bo qua
        }
        super.onDestroy()
    }
}
