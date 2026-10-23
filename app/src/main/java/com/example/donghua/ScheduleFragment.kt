package com.example.donghua

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import java.util.Calendar
import kotlin.concurrent.thread

/**
 * Tab Lich chieu: phim chieu som + phim chieu trong ngay theo tung
 * ngay trong tuan (du lieu lay tu AJAX dox_schedule_page cua web).
 * Mac dinh chon ngay hom nay.
 */
class ScheduleFragment : Fragment() {

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val movie: Movie) : Row()
    }

    inner class ScheduleAdapter(private var rows: List<Row>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                val v = inf.inflate(R.layout.item_section_header, parent, false)
                object : RecyclerView.ViewHolder(v) {}
            } else {
                val v = inf.inflate(R.layout.item_comic_grid, parent, false)
                object : RecyclerView.ViewHolder(v) {}
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header ->
                    (holder.itemView as TextView).text = row.title
                is Row.Item -> {
                    val m = row.movie
                    holder.itemView.findViewById<TextView>(R.id.tvTitle).text = m.title
                    holder.itemView.findViewById<TextView>(R.id.tvLatest).text = m.sub
                    val iv =
                        holder.itemView.findViewById<android.widget.ImageView>(R.id.ivCover)
                    Glide.with(holder.itemView)
                        .load(m.poster)
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery)
                        .into(iv)
                    holder.itemView.setOnClickListener { openDetail(m) }
                }
            }
        }
    }

    private lateinit var adapter: ScheduleAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private val dayButtons = ArrayList<MaterialButton>()
    private var selectedDay: String = ""
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_schedule, container, false)
        progress = v.findViewById(R.id.progress)
        tvEmpty = v.findViewById(R.id.tvEmpty)
        swipe = v.findViewById(R.id.swipeRefresh)
        val rv = v.findViewById<RecyclerView>(R.id.rvSchedule)
        val lm = GridLayoutManager(requireContext(), 3)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.getItemViewType(position) == 0) 3 else 1
        }
        rv.layoutManager = lm
        adapter = ScheduleAdapter(emptyList())
        rv.adapter = adapter

        // Hang nut ngay: mac dinh chon ngay hom nay
        selectedDay = todayId()
        val ll = v.findViewById<LinearLayout>(R.id.llDays)
        for ((id, label) in HhSource.SCHEDULE_DAYS) {
            val b = MaterialButton(requireContext())
            b.text = if (id == todayId()) "$label •" else label
            b.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            b.setOnClickListener {
                if (selectedDay != id) {
                    selectedDay = id
                    refreshDayButtons()
                    load()
                }
            }
            dayButtons.add(b)
            ll.addView(b)
        }
        refreshDayButtons()
        swipe.setOnRefreshListener { load() }
        load()
        return v
    }

    private fun todayId(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "thu-2"
            Calendar.TUESDAY -> "thu-3"
            Calendar.WEDNESDAY -> "thu-4"
            Calendar.THURSDAY -> "thu-5"
            Calendar.FRIDAY -> "thu-6"
            Calendar.SATURDAY -> "thu-7"
            else -> "chu-nhat"
        }
    }

    private fun refreshDayButtons() {
        for ((idx, b) in dayButtons.withIndex()) {
            val id = HhSource.SCHEDULE_DAYS[idx].first
            b.alpha = if (id == selectedDay) 1.0f else 0.45f
        }
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
        tvEmpty.visibility = View.GONE
        if (!swipe.isRefreshing) progress.visibility = View.VISIBLE
        val day = selectedDay
        thread {
            RemoteConfig.awaitReady(2500)
            var sections: List<HomeSection> = emptyList()
            try {
                sections = HhSource.fetchSchedule(day)
            } catch (e: Exception) {
                // Bao loi o duoi
            }
            activity?.runOnUiThread {
                loading = false
                swipe.isRefreshing = false
                progress.visibility = View.GONE
                if (sections.isNotEmpty()) {
                    val rows = ArrayList<Row>()
                    for (s in sections) {
                        rows.add(Row.Header(s.name))
                        for (m in s.movies) rows.add(Row.Item(m))
                    }
                    adapter.submit(rows)
                } else {
                    adapter.submit(emptyList())
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text =
                        "Chưa tải được lịch chiếu ngày này.\nKiểm tra mạng rồi vuốt xuống thử lại nhé."
                }
            }
        }
    }
}
