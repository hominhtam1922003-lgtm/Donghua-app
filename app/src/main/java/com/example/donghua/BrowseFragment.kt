package com.example.donghua

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

/**
 * Tab duyet phim theo 1 duong dan danh sach cua web (vd
 * "/moi-cap-nhat", "/top-xem-nhieu", "/hoan-thanh"), phan trang theo
 * so trang doc duoc tu chinh trang (link /page/N cua WordPress).
 */
class BrowseFragment : Fragment() {

    companion object {
        fun newInstancePath(title: String, path: String): BrowseFragment {
            val f = BrowseFragment()
            f.arguments = Bundle().apply {
                putString("title", title)
                putString("path", path)
            }
            return f
        }
    }

    private lateinit var adapter: MovieGridAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var btnLoadMore: MaterialButton
    private lateinit var tvError: android.widget.TextView

    private var title: String = ""
    private var path: String = "/moi-cap-nhat"
    private val items = ArrayList<Movie>()
    private var loading = false
    private var loadingMore = false
    private var currentPage = 1
    private var totalPages = 1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        title = arguments?.getString("title") ?: ""
        path = arguments?.getString("path") ?: "/moi-cap-nhat"
        val v = inflater.inflate(R.layout.fragment_home, container, false)
        v.findViewById<View>(R.id.tilSearch).visibility = View.GONE
        v.findViewById<View>(R.id.btnVerify).visibility = View.GONE
        val tvTitle = v.findViewById<android.widget.TextView>(R.id.tvBrowseTitle)
        if (title.isNotEmpty()) {
            tvTitle.text = title
            tvTitle.visibility = View.VISIBLE
        }
        progress = v.findViewById(R.id.progress)
        swipe = v.findViewById(R.id.swipeRefresh)
        val rv = v.findViewById<RecyclerView>(R.id.rvComics)
        rv.layoutManager = GridLayoutManager(requireContext(), 3)
        rv.itemAnimator = null
        rv.setHasFixedSize(true)
        adapter = MovieGridAdapter(emptyList()) { openDetail(it) }
        rv.adapter = adapter
        btnLoadMore = v.findViewById(R.id.btnLoadMore)
        btnLoadMore.text = "Tải thêm phim"
        btnLoadMore.visibility = View.GONE
        btnLoadMore.setOnClickListener { loadMore() }
        tvError = v.findViewById(R.id.tvLoadError)
        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun openDetail(movie: Movie) {
        val i = Intent(requireContext(), DetailActivity::class.java)
        i.putExtra(DetailActivity.EXTRA_URL, HhSource.rebase(movie.url))
        i.putExtra(DetailActivity.EXTRA_TITLE, movie.title)
        i.putExtra(DetailActivity.EXTRA_POSTER, movie.poster)
        startActivity(i)
    }

    private fun load() {
        if (loading) return
        loading = true
        if (::tvError.isInitialized) tvError.visibility = View.GONE
        if (!swipe.isRefreshing) progress.visibility = View.VISIBLE
        thread {
            RemoteConfig.awaitReady(2500)
            var page: SearchPage? = null
            try {
                page = HhSource.fetchListPage(path, 1)
            } catch (e: Exception) {
                // Bao loi o duoi
            }
            activity?.runOnUiThread {
                loading = false
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                if (page != null && page.movies.isNotEmpty()) {
                    items.clear()
                    items.addAll(page.movies)
                    currentPage = 1
                    totalPages = page.totalPages
                    adapter.submit(items.toList())
                    updateLoadMoreVisibility()
                } else if (items.isEmpty()) {
                    tvError.visibility = View.VISIBLE
                    tvError.text =
                        "Mục này chưa tải được. Kiểm tra kết nối mạng rồi vuốt xuống để thử lại nhé."
                    updateLoadMoreVisibility()
                }
            }
        }
    }

    private fun updateLoadMoreVisibility() {
        if (::btnLoadMore.isInitialized) {
            btnLoadMore.visibility =
                if (currentPage < totalPages && items.isNotEmpty()) View.VISIBLE
                else View.GONE
            btnLoadMore.isEnabled = !loadingMore
        }
    }

    private fun loadMore() {
        if (loading || loadingMore || currentPage >= totalPages) return
        loadingMore = true
        updateLoadMoreVisibility()
        val next = currentPage + 1
        thread {
            var page: SearchPage? = null
            try {
                page = HhSource.fetchListPage(path, next)
            } catch (e: Exception) {
                // Giu nguyen trang thai
            }
            activity?.runOnUiThread {
                loadingMore = false
                if (page != null && page.movies.isNotEmpty()) {
                    val known = items.map { it.url }.toHashSet()
                    val added = page.movies.filter { known.add(it.url) }
                    if (added.isNotEmpty()) {
                        items.addAll(added)
                        currentPage = next
                        totalPages = page.totalPages
                        adapter.submit(items.toList())
                    } else if (page.movies.size >= HhSource.PAGE_SIZE) {
                        // Trang day nhung trung het: van di tiep
                        currentPage = next
                        totalPages = page.totalPages
                    } else {
                        totalPages = currentPage
                    }
                } else {
                    totalPages = currentPage
                    Toast.makeText(
                        requireContext(), "Đã tải hết phim của mục này",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                updateLoadMoreVisibility()
            }
        }
    }
}
