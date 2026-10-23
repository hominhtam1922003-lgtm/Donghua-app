package com.example.donghua

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Nguon du lieu: hhkungfu.ee — app "Donghua" (hoat hinh 3D Trung Quoc).
 *
 * KET QUA KHAO SAT TRUC TIEP (23/10/2026): web chay WordPress (theme
 * Halim), HTML render san o server, khong Cloudflare, tai nhanh:
 *   - Trang danh sach: / (trang chu), /moi-cap-nhat, /top-xem-nhieu,
 *     /hoan-thanh; the phim la ".halim-item" (16 the/trang), phan
 *     trang kieu /page/N (doc so trang lon nhat tu link phan trang).
 *   - Tim kiem: /?s={tu khoa} (trang sau: /page/N/?s=...).
 *   - Chi tiet: /{slug} — trang nay CHUA SAN nhom ban chieu
 *     ".halim-server" (nhan "#Vietsub", "#Thuyết Minh:"...) va toan bo
 *     tap (the <a> co data-post-id / data-ep / data-sv / href trang xem
 *     /watch-{slug}/tap-{n}-sv{k}.html). Mo ta o ".entry-content",
 *     poster o og:image.
 *   - Phat: GET /player/player.php?action=dox_ajax_player&post_id=
 *     {id}&chapter_st={data-ep}&sv={k}&type={tiktik|pro} tra ve the
 *     iframe streamfree.vip/embed/... (tiktik = "1080P V1", pro =
 *     "1080P V2"). PlayerActivity bat luong thang tu trang embed do,
 *     khong bat duoc thi phat chinh trang embed/trang xem da khoa.
 *   - Lich chieu: POST wp-admin/admin-ajax.php voi
 *     action=dox_schedule_page&date={thu-2..thu-7|chu-nhat}&type=page
 *     tra manh HTML gom muc "Phim Chieu Som" (.schedule-items.early)
 *     va luoi chinh (.schedule-grid), moi phim la "a.schedule-item".
 */
object HhSource {

    const val DEFAULT_BASE = "https://hhkungfu.ee"

    /** Ten mien dang dung (RemoteConfig cap nhat khi web doi ten mien). */
    @Volatile
    var activeBase: String = DEFAULT_BASE

    val BASE: String
        get() = activeBase

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36"

    /** UA dung chung (MainActivity ghi de bang UA that cua may). */
    @Volatile
    var UA: String = FALLBACK_UA

    /** So the phim tren 1 trang danh sach cua web. */
    const val PAGE_SIZE = 16

    /** Cac kieu server phat cua web (tham so "type" cua player.php). */
    val PLAYER_TYPES: List<Pair<String, String>> = listOf(
        "tiktik" to "1080P V1",
        "pro" to "1080P V2"
    )

    /** Cac ngay cho tab Lich chieu: ma ngay cua web + nhan tieng Viet. */
    val SCHEDULE_DAYS: List<Pair<String, String>> = listOf(
        "thu-2" to "Thứ Hai",
        "thu-3" to "Thứ Ba",
        "thu-4" to "Thứ Tư",
        "thu-5" to "Thứ Năm",
        "thu-6" to "Thứ Sáu",
        "thu-7" to "Thứ Bảy",
        "chu-nhat" to "Chủ Nhật"
    )

    /**
     * URL da luu (yeu thich/lich su) co the con o ten mien cu: doi host
     * ve ten mien dang dung. Chi dung vao URL thuoc cac ten mien
     * hhkungfu; URL la (streamfree...) giu nguyen.
     */
    fun rebase(url: String): String {
        if (!url.contains("hhkungfu")) return url
        val path = Regex("""^https?://[^/]+""").replace(url, "")
        return activeBase + path
    }

    // ---------------- HTTP ----------------

    private fun httpText(url: String, postBody: String? = null, referer: String = ""): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 20000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9")
                if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
                if (postBody != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty(
                        "Content-Type", "application/x-www-form-urlencoded"
                    )
                }
            }
            if (postBody != null) {
                conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code")
            return text
        } finally {
            conn?.disconnect()
        }
    }

    // Cache ngan 90 giay: chuyen tab/di lai chi tiet thi dung lai
    // trang vua tai (mo gan nhu tuc thi), du lieu van du tuoi de
    // phim moi cap nhat khong bi cham qua 1,5 phut
    private val docCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Document>>()

    private fun getDoc(url: String): Document {
        val hit = docCache[url]
        if (hit != null &&
            System.currentTimeMillis() - hit.first < 90_000
        ) {
            return hit.second
        }
        val doc = Jsoup.parse(httpText(url), url)
        if (docCache.size > 60) docCache.clear()
        docCache[url] = System.currentTimeMillis() to doc
        return doc
    }

    // ---------------- The phim ----------------

    private fun parseCards(doc: Document): List<Movie> {
        val out = ArrayList<Movie>()
        val seen = HashSet<String>()
        for (item in doc.select(".halim-item")) {
            val a = item.selectFirst("a.halim-thumb") ?: item.selectFirst("a[href]")
                ?: continue
            val url = a.absUrl("href")
            if (url.isEmpty() || !seen.add(url)) continue
            val title = item.selectFirst(".entry-title")?.text()?.trim()
                ?: a.attr("title").trim()
            if (title.isEmpty()) continue
            val poster = item.selectFirst("img")?.let {
                it.absUrl("src").ifEmpty { it.absUrl("data-src") }
            } ?: ""
            val ep = item.selectFirst(".episode")?.text()?.trim().orEmpty()
            val status = item.selectFirst(".status")?.text()?.trim().orEmpty()
            val sub = when {
                ep.isNotEmpty() && status.isNotEmpty() -> "$ep • $status"
                ep.isNotEmpty() -> ep
                else -> status
            }
            out.add(Movie(title, url, poster, sub))
        }
        return out
    }

    /** Tong so trang: doc so trang lon nhat trong link phan trang. */
    private fun totalPagesOf(doc: Document, count: Int, page: Int): Int {
        var max = 0
        for (a in doc.select("a[href]")) {
            val m = Regex("""/page/(\d+)""").find(a.attr("href")) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n > max) max = n
        }
        if (max > 0) return max
        // Khong thay link phan trang: trang day the thi co the con trang sau
        return if (count >= PAGE_SIZE) page + 1 else page
    }

    // ---------------- Danh sach / tim kiem ----------------

    /** 1 trang danh sach theo duong dan cua web (vd "/moi-cap-nhat"). */
    fun fetchListPage(path: String, page: Int): SearchPage {
        val clean = path.trimEnd('/')
        val url = if (page <= 1) BASE + clean else "$BASE$clean/page/$page"
        val doc = getDoc(url)
        val movies = parseCards(doc)
        return SearchPage(movies, totalPagesOf(doc, movies.size, page), movies.size)
    }

    /** Tim kiem theo tu khoa (trang web dung tham so ?s= cua WordPress). */
    fun searchKeyword(keyword: String, page: Int): SearchPage {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val url = if (page <= 1) "$BASE/?s=$q" else "$BASE/page/$page/?s=$q"
        val doc = getDoc(url)
        val movies = parseCards(doc)
        return SearchPage(movies, totalPagesOf(doc, movies.size, page), movies.size)
    }

    /** Du phong khi cac muc trang chu deu loi: muc Xem nhieu. */
    fun fetchHot(): List<Movie> = fetchListPage("/top-xem-nhieu", 1).movies

    /**
     * Trang chu: 3 muc chinh cua web, tai SONG SONG (moi trang ~1 giay;
     * chay tuan tu se cham gap 3) + loc trung xuyen muc.
     */
    fun fetchHomeSections(): Pair<List<HomeSection>, Int> {
        val defs = listOf(
            "Mới cập nhật" to "/moi-cap-nhat",
            "Xem nhiều" to "/top-xem-nhieu",
            "Hoàn thành" to "/hoan-thanh"
        )
        val raw = arrayOfNulls<List<Movie>>(defs.size)
        val totals = IntArray(defs.size) { 1 }
        val threads = defs.mapIndexed { idx, def ->
            thread {
                raw[idx] = try {
                    val pg = fetchListPage(def.second, 1)
                    totals[idx] = pg.totalPages
                    pg.movies
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        threads.forEach { it.join() }
        val seen = HashSet<String>()
        val out = ArrayList<HomeSection>()
        for ((idx, def) in defs.withIndex()) {
            val fresh = (raw[idx] ?: emptyList())
                .filter { seen.add(it.url) }.take(18)
            if (fresh.isNotEmpty()) out.add(HomeSection(def.first, fresh))
        }
        return out to totals[0].coerceAtLeast(1)
    }

    // ---------------- Chi tiet + danh sach tap ----------------

    fun fetchDetail(detailUrl: String): MovieDetail {
        val url = rebase(detailUrl)
        val doc = getDoc(url)

        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val ogTitle = doc.selectFirst("meta[property=og:title]")
            ?.attr("content")?.substringBefore("|")?.substringBefore("–")?.trim().orEmpty()
        val title = h1.ifEmpty { ogTitle }
        val poster = doc.selectFirst("meta[property=og:image]")?.absUrl("content") ?: ""

        var desc = doc.selectFirst(".entry-content")?.text()?.trim().orEmpty()
        if (desc.startsWith("Nội dung phim")) {
            desc = desc.removePrefix("Nội dung phim").trim()
        }

        val servers = ArrayList<ServerGroup>()
        for (block in doc.select(".halim-server")) {
            val rawLabel = block.selectFirst(".halim-server-name")?.text().orEmpty()
            val label = rawLabel.replace("#", "").trim().trimEnd(':').trim()
                .ifEmpty { "Mặc định" }
            val eps = ArrayList<Episode>()
            for (a in block.select(".halim-episode a[href]")) {
                val name = a.attr("title").trim()
                    .ifEmpty { a.text().trim() }
                if (name.isEmpty()) continue
                eps.add(
                    Episode(
                        name = name,
                        url = a.absUrl("href"),
                        postId = a.attr("data-post-id").toLongOrNull() ?: 0,
                        epKey = a.attr("data-ep"),
                        sv = a.attr("data-sv").toIntOrNull() ?: 0
                    )
                )
            }
            if (eps.isNotEmpty()) {
                eps.sortWith(compareBy({ epNum(it) ?: Double.MAX_VALUE }, { it.name }))
                servers.add(ServerGroup(label, eps))
            }
        }

        val total = servers.maxOfOrNull { it.episodes.size } ?: 0
        val bodyText = doc.body()?.text().orEmpty()
        val status = when {
            bodyText.contains("Đang chiếu") -> "Đang chiếu"
            bodyText.contains("Hoàn thành") -> "Hoàn thành"
            else -> ""
        }
        val metaParts = ArrayList<String>()
        if (status.isNotEmpty()) metaParts.add("Trạng thái: $status")
        if (total > 0) metaParts.add("Số tập: $total tập")
        doc.selectFirst(".status")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { metaParts.add("Chất lượng: $it") }

        return MovieDetail(
            movie = Movie(title, url, poster),
            meta = metaParts.joinToString("\n"),
            description = desc,
            servers = servers
        )
    }

    private fun epNum(ep: Episode): Double? {
        val fromKey = Regex("""(\d+(?:\.\d+)?)""").findAll(ep.epKey).lastOrNull()
            ?.groupValues?.get(1)?.toDoubleOrNull()
        if (fromKey != null) return fromKey
        return Regex("""(\d+(?:\.\d+)?)""").findAll(ep.name).firstOrNull()
            ?.groupValues?.get(1)?.toDoubleOrNull()
    }

    // ---------------- Phat phim ----------------

    /**
     * Goi player.php cua web de lay URL trang phat embed (streamfree)
     * cho 1 tap + 1 kieu server. Tra "" neu web bao khong co nguon
     * (iframe not-found) hoac loi mang.
     */
    fun resolveEmbed(ep: Episode, type: String): String {
        if (ep.postId <= 0 || ep.epKey.isEmpty()) return ""
        val u = "$BASE/player/player.php?action=dox_ajax_player" +
            "&post_id=${ep.postId}&chapter_st=${ep.epKey}&sv=${ep.sv}&type=$type"
        return try {
            val html = httpText(u, referer = ep.url.ifEmpty { "$BASE/" })
            if (html.contains("not-found")) return ""
            val m = Regex("""<iframe[^>]+src="([^"]+)"""").find(html) ?: return ""
            m.groupValues[1].replace("&amp;", "&")
        } catch (e: Exception) {
            ""
        }
    }

    // ---------------- Lich chieu ----------------

    /** Lich chieu 1 ngay (ma ngay cua web, vd "thu-6", "chu-nhat"). */
    fun fetchSchedule(dayId: String): List<HomeSection> {
        val body = "action=dox_schedule_page&date=" +
            URLEncoder.encode(dayId, "UTF-8") + "&type=page"
        val html = httpText(
            "$BASE/wp-admin/admin-ajax.php", postBody = body, referer = "$BASE/lich-chieu"
        )
        val doc = Jsoup.parse(html, BASE)
        val out = ArrayList<HomeSection>()

        fun parseItems(container: org.jsoup.nodes.Element?): List<Movie> {
            if (container == null) return emptyList()
            val list = ArrayList<Movie>()
            for (a in container.select("a.schedule-item[href]")) {
                val url = a.absUrl("href")
                if (url.isEmpty()) continue
                val t = a.selectFirst(".schedule-title")?.text()?.trim()
                    ?: a.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                if (t.isEmpty()) continue
                val poster = a.selectFirst("img")?.absUrl("src") ?: ""
                val epText = a.selectFirst(".schedule-episode")?.text()?.trim().orEmpty()
                val timeText = a.selectFirst(".schedule-time")?.text()?.trim().orEmpty()
                val sub = listOf(timeText, epText).filter { it.isNotEmpty() }
                    .joinToString(" • ")
                list.add(Movie(t, url, poster, sub))
            }
            return list
        }

        val earlyTitle = doc.selectFirst(".early-schedule h2")?.text()?.trim()
            ?: "Phim Chiếu Sớm"
        val early = parseItems(doc.selectFirst(".schedule-items.early"))
        if (early.isNotEmpty()) out.add(HomeSection(earlyTitle, early))
        val grid = parseItems(doc.selectFirst(".schedule-grid"))
        if (grid.isNotEmpty()) out.add(HomeSection("Lịch chiếu trong ngày", grid))
        return out
    }
}
