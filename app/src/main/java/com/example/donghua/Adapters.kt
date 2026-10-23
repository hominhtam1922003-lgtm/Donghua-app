package com.example.donghua

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Chieu cao dung cho khung poster trong luoi 3 cot, tinh tu chieu
 * rong man hinh that de poster giu NGUYEN ti le 2:3 cua web (300x450)
 * — khong bi cat tren/duoi nhu khung co dinh xua, va khong dung
 * ConstraintLayout/dimensionRatio (do sai kich thuoc trong
 * RecyclerView, lam cac the chong len nhau o ban 1.0.2).
 * [totalHorizontalPadDp] = tong le ngang cua man hinh chua luoi
 * (padding cua fragment + cua RecyclerView, ca 2 ben).
 */
fun gridPosterHeightPx(context: android.content.Context, totalHorizontalPadDp: Int): Int {
    val dm = context.resources.displayMetrics
    val avail = dm.widthPixels - totalHorizontalPadDp * dm.density
    val colW = avail / 3f
    val posterW = colW - 6 * dm.density // margin the 3dp moi ben
    return (posterW * 1.5f).toInt()
}

/** Gan chieu cao poster 2:3 cho 1 item luoi vua nap. */
fun applyGridPosterHeight(itemView: View, totalHorizontalPadDp: Int) {
    val iv = itemView.findViewById<ImageView>(R.id.ivCover) ?: return
    val h = gridPosterHeightPx(itemView.context, totalHorizontalPadDp)
    if (iv.layoutParams.height != h) {
        iv.layoutParams = iv.layoutParams.apply { height = h }
    }
}

/** Luoi poster phim (dung lai layout item_comic_grid: anh + ten + dong phu). */
class MovieGridAdapter(
    private var items: List<Movie>,
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<MovieGridAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivCover)
        val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        val tvSub: TextView = v.findViewById(R.id.tvLatest)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comic_grid, parent, false)
        // fragment_home: le fragment 10dp x2 + le danh sach 4dp x2
        applyGridPosterHeight(v, 28)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tvTitle.text = m.title
        holder.tvSub.text = m.sub
        ImageLoader.cover(holder.iv, m.poster)
        holder.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<Movie>) {
        items = list
        notifyDataSetChanged()
    }
}

/** Danh sach dang dong (Yeu thich / Lich su). */
class MovieRowAdapter(
    private var items: List<Movie>,
    private val subs: Map<String, String> = emptyMap(),
    private val onClick: (Movie) -> Unit
) : RecyclerView.Adapter<MovieRowAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.ivCoverRow)
        val tvTitle: TextView = v.findViewById(R.id.tvTitleRow)
        val tvSub: TextView = v.findViewById(R.id.tvSubRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comic_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.tvTitle.text = m.title
        holder.tvSub.text = subs[m.url] ?: m.sub
        ImageLoader.cover(holder.iv, m.poster)
        holder.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount(): Int = items.size

    fun submit(list: List<Movie>, newSubs: Map<String, String> = emptyMap()) {
        items = list
        notifyDataSetChanged()
    }
}
