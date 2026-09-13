package com.retrotube.app.bucket

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.R

/** One row per episode -- thumbnail, episode number, title, air date and a
 *  short overview, matching the iOS app's row-based (not poster-grid) episode
 *  list: an episode is read like a list of things to pick from, not browsed
 *  like a shelf of posters. */
data class EpisodeRow(
    val id: String,
    val episodeLabel: String,
    val title: String,
    val airDate: String? = null,
    val overview: String? = null,
    val thumbnail: Bitmap? = null,
    val onClick: () -> Unit,
)

class EpisodeRowAdapter(private var rows: List<EpisodeRow> = emptyList()) :
    RecyclerView.Adapter<EpisodeRowAdapter.ViewHolder>() {

    fun submitList(newRows: List<EpisodeRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.thumbnail.setImageBitmap(row.thumbnail)
        holder.label.text = row.episodeLabel
        holder.title.text = row.title
        if (row.airDate.isNullOrEmpty()) {
            holder.airDate.visibility = View.GONE
        } else {
            holder.airDate.visibility = View.VISIBLE
            holder.airDate.text = row.airDate
        }
        if (row.overview.isNullOrEmpty()) {
            holder.overview.visibility = View.GONE
        } else {
            holder.overview.visibility = View.VISIBLE
            holder.overview.text = row.overview
        }
        holder.itemView.setOnClickListener { row.onClick() }
    }

    override fun getItemCount(): Int = rows.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: android.widget.ImageView = view.findViewById(R.id.episodeThumbnail)
        val label: android.widget.TextView = view.findViewById(R.id.episodeLabel)
        val title: android.widget.TextView = view.findViewById(R.id.episodeTitle)
        val airDate: android.widget.TextView = view.findViewById(R.id.episodeAirDate)
        val overview: android.widget.TextView = view.findViewById(R.id.episodeOverview)
    }
}
