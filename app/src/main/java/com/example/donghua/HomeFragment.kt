package com.example.donghua

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.concurrent.thread

/**
 * Trang chu (ban 2.0 - nguon API): cac MUC dung nhu tren web
 * ("Phim Song Ngu", "Phim Trung Quoc moi", "Phim US-UK moi"...) lay
 * tu /lists/homepageLists, hien dang tieu de muc + luoi phim 3 cot.
 * O tim kiem go den dau tim den do bang API (tham so keyword), ket
 * qua co phan trang "Tai them phim".
 */
class HomeFragment : Fragment() {

    private sealed class HomeItem {
        data class Header(val name: String) : HomeItem()
        data class MovieItem(val movie: Movie) : HomeItem()
    }

    private inner class HomeAdapter(
        private var items: List<HomeItem>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (items[position] is HomeItem.Header) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                object : RecyclerView.ViewHolder(
                    inf.inflate(R.layout.item_section_header, parent, false)
                ) {}
            } else {
                val v = inf.inflate(R.layout.item_comic_grid, parent, false)
                applyGridPosterHeight(v, 28)
                object : RecyclerView.ViewHolder(v) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HomeItem.Header -> {
                    (holder.itemView as TextView).text = item.name
                }
                is HomeItem.MovieItem -> {
                    val m = item.movie
                    holder.itemView.findViewById<TextView>(R.id.tvTitle).text = m.title
                    holder.itemView.findViewById<TextView>(R.id.tvLatest).text = m.sub
                    ImageLoader.cover(
                        holder.itemView.findViewById(R.id.ivCover), m.poster
                    )
                    holder.itemView.setOnClickListener { openDetail(m) }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        fun submit(list: List<HomeItem>) {
            items = list
            notifyDataSetChanged()
        }
    }

    private lateinit var adapter: HomeAdapter
    private var homeItems: List<HomeItem> = emptyList()
    // Phan trang cua muc "Moi cap nhat" ngay o trang chu: nut
    // "Tai them phim" cuon trang chu phai tai tiep duoc nhu cac tab,
    // khong dung lai o 3 muc x trang 1 (~39 phim) nhu truoc
    private var homePage = 1
    private var homeTotalPages = 1
    private var homeLoadingMore = false
    private val searchMovies = ArrayList<Movie>()
    private var loading = false

    // Tim kiem truc tiep khi go: dem thoi gian + so the he chong ket qua cu
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private var searchGeneration = 0
    private var searching = false
    private var searchPage = 0
    private var searchTotalPages = 1
    private var loadingMore = false

    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var etSearch: EditText
    private lateinit var btnLoadMore: Button
    private lateinit var tvLoadError: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        progress = v.findViewById(R.id.progress)
        swipe = v.findViewById(R.id.swipeRefresh)
        etSearch = v.findViewById(R.id.etSearch)
        btnLoadMore = v.findViewById(R.id.btnLoadMore)
        btnLoadMore.text = "Tải thêm phim"
        btnLoadMore.visibility = View.GONE
        btnLoadMore.setOnClickListener {
            if (searching) loadMoreSearch() else loadMoreHome()
        }
        v.findViewById<View>(R.id.btnVerify).visibility = View.GONE
        tvLoadError = v.findViewById(R.id.tvLoadError)

        val rv = v.findViewById<RecyclerView>(R.id.rvComics)
        val glm = GridLayoutManager(requireContext(), 3)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == 0) 3 else 1
        }
        rv.layoutManager = glm
        rv.itemAnimator = null
        adapter = HomeAdapter(emptyList())
        rv.adapter = adapter

        swipe.setOnRefreshListener {
            if (searching) runSearch(etSearch.text.toString().trim())
            else loadHome()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                if (q.isEmpty()) {
                    // Xoa o tim kiem -> quay lai cac muc trang chu
                    searchGeneration++
                    searching = false
                    btnLoadMore.visibility =
                        if (homePage < homeTotalPages) View.VISIBLE
                        else View.GONE
                    adapter.submit(homeItems)
                    return
                }
                val r = Runnable { runSearch(q) }
                searchRunnable = r
                searchHandler.postDelayed(r, 450)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = etSearch.text.toString().trim()
                if (q.isNotEmpty()) {
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }
                    runSearch(q)
                }
                true
            } else {
                false
            }
        }

        loadHome()
        return v
    }

    private fun openDetail(movie: Movie) {
        val i = Intent(requireContext(), DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_URL, HhSource.rebase(movie.url))
        i.putExtra(DetailActivity.EXTRA_TITLE, movie.title)
        i.putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
        startActivity(i)
    }

    private fun loadHome() {
        if (loading) return
        loading = true
        if (!swipe.isRefreshing) progress.visibility = View.VISIBLE
        thread {
            RemoteConfig.awaitReady(2500)
            var sections: List<HomeSection> = emptyList()
            var hot: List<Movie> = emptyList()
            try {
                val res = HhSource.fetchHomeSections()
                sections = res.first
                homeTotalPages = res.second
                homePage = 1
            } catch (e: Exception) {
                // Thu duong du phong phim hot o duoi
            }
            if (sections.isEmpty()) {
                try {
                    hot = HhSource.fetchHot()
                } catch (e: Exception) {
                    // Bao loi o duoi
                }
            }
            activity?.runOnUiThread {
                loading = false
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                val items = ArrayList<HomeItem>()
                if (sections.isNotEmpty()) {
                    for (s in sections) {
                        items.add(HomeItem.Header(s.name))
                        s.movies.forEach { items.add(HomeItem.MovieItem(it)) }
                    }
                } else if (hot.isNotEmpty()) {
                    items.add(HomeItem.Header("Phim hot"))
                    hot.forEach { items.add(HomeItem.MovieItem(it)) }
                }
                if (items.isNotEmpty()) {
                    tvLoadError.visibility = View.GONE
                    homeItems = items
                    if (!searching) {
                        adapter.submit(items)
                        btnLoadMore.visibility =
                            if (homePage < homeTotalPages) View.VISIBLE
                            else View.GONE
                    }
                } else if (homeItems.isEmpty()) {
                    tvLoadError.visibility = View.VISIBLE
                    tvLoadError.text =
                        "Chưa tải được phim. Kiểm tra kết nối mạng rồi vuốt xuống để thử lại nhé."
                }
            }
        }
    }

    /** Nut "Tai them phim" o che do trang chu: tai trang tiep cua
     * muc Moi cap nhat va noi vao cuoi danh sach (loc trung). */
    private fun loadMoreHome() {
        if (homeLoadingMore || searching || homePage >= homeTotalPages) return
        homeLoadingMore = true
        btnLoadMore.isEnabled = false
        thread {
            var page: SearchPage? = null
            try {
                page = HhSource.fetchListPage("/moi-cap-nhat", homePage + 1)
            } catch (e: Exception) {
                // Bao o duoi: giu nguyen danh sach
            }
            activity?.runOnUiThread {
                homeLoadingMore = false
                btnLoadMore.isEnabled = true
                val p = page
                if (p != null && p.movies.isNotEmpty()) {
                    homePage++
                    homeTotalPages = p.totalPages
                    val known = HashSet<String>()
                    homeItems.forEach {
                        if (it is HomeItem.MovieItem) known.add(it.movie.url)
                    }
                    val fresh = p.movies.filter { known.add(it.url) }
                    if (fresh.isNotEmpty()) {
                        val merged = ArrayList(homeItems)
                        fresh.forEach { merged.add(HomeItem.MovieItem(it)) }
                        homeItems = merged
                        if (!searching) adapter.submit(merged)
                    }
                }
                if (!searching) {
                    btnLoadMore.visibility =
                        if (homePage < homeTotalPages) View.VISIBLE
                        else View.GONE
                }
            }
        }
    }

    /** Tim kiem theo tu khoa qua API (tham so keyword), trang 1. */
    private fun runSearch(keyword: String) {
        val gen = ++searchGeneration
        searching = true
        searchPage = 1
        searchTotalPages = 1
        progress.visibility = View.VISIBLE
        btnLoadMore.visibility = View.GONE
        thread {
            RemoteConfig.awaitReady(1500)
            var page: SearchPage? = null
            try {
                page = HhSource.searchKeyword(keyword, 1)
            } catch (e: Exception) {
                // Bao rong o duoi
            }
            activity?.runOnUiThread {
                if (gen != searchGeneration) return@runOnUiThread
                progress.visibility = View.GONE
                swipe.isRefreshing = false
                searchMovies.clear()
                if (page != null && page.movies.isNotEmpty()) {
                    searchMovies.addAll(page.movies)
                    searchTotalPages = page.totalPages
                    adapter.submit(searchMovies.map { HomeItem.MovieItem(it) })
                    updateSearchMoreVisibility()
                } else {
                    adapter.submit(emptyList())
                    Toast.makeText(
                        requireContext(), "Không tìm thấy phim phù hợp",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateSearchMoreVisibility() {
        btnLoadMore.visibility =
            if (searching && searchPage < searchTotalPages && searchMovies.isNotEmpty())
                View.VISIBLE else View.GONE
        btnLoadMore.isEnabled = !loadingMore
    }

    private fun loadMoreSearch() {
        if (!searching || loadingMore || searchPage >= searchTotalPages) return
        val keyword = etSearch.text.toString().trim()
        if (keyword.isEmpty()) return
        val gen = searchGeneration
        loadingMore = true
        updateSearchMoreVisibility()
        val next = searchPage + 1
        thread {
            var page: SearchPage? = null
            try {
                page = HhSource.searchKeyword(keyword, next)
            } catch (e: Exception) {
                // Giu nguyen danh sach hien tai
            }
            activity?.runOnUiThread {
                loadingMore = false
                if (gen != searchGeneration) return@runOnUiThread
                if (page != null && page.movies.isNotEmpty()) {
                    val known = searchMovies.map { it.url }.toHashSet()
                    val added = page.movies.filter { known.add(it.url) }
                    searchMovies.addAll(added)
                    if (added.isNotEmpty() ||
                        page.movies.size >= HhSource.PAGE_SIZE
                    ) {
                        searchPage = next
                        searchTotalPages = page.totalPages
                    } else {
                        searchTotalPages = searchPage
                    }
                    adapter.submit(searchMovies.map { HomeItem.MovieItem(it) })
                } else {
                    searchTotalPages = searchPage
                    Toast.makeText(
                        requireContext(), "Đã tải hết kết quả", Toast.LENGTH_SHORT
                    ).show()
                }
                updateSearchMoreVisibility()
            }
        }
    }
}
