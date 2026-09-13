package com.retrotube.app.bucket

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.R

/** One reusable poster-grid tile shape shared by every new Phase 3 screen
 *  (bucket shelves, show/movie grids, season grids) instead of a bespoke
 *  adapter per screen -- they're all "a poster, a title, sometimes a
 *  subtitle" with the same card treatment as the rest of the library. */
data class PosterTile(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val poster: Bitmap? = null,
    val onClick: () -> Unit,
    val onMenuClick: ((android.view.View) -> Unit)? = null,
)

class PosterTileAdapter(private var tiles: List<PosterTile> = emptyList()) :
    RecyclerView.Adapter<PosterTileAdapter.ViewHolder>() {

    fun submitList(newTiles: List<PosterTile>) {
        tiles = newTiles
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster_tile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tile = tiles[position]
        holder.poster.setImageBitmap(tile.poster)
        holder.title.text = tile.title
        if (tile.subtitle.isNullOrEmpty()) {
            holder.subtitle.visibility = android.view.View.GONE
        } else {
            holder.subtitle.visibility = android.view.View.VISIBLE
            holder.subtitle.text = tile.subtitle
        }
        holder.itemView.setOnClickListener { tile.onClick() }
        if (tile.onMenuClick != null) {
            holder.menuButton.visibility = android.view.View.VISIBLE
            holder.menuButton.setOnClickListener { tile.onMenuClick.invoke(it) }
        } else {
            holder.menuButton.visibility = android.view.View.GONE
            holder.menuButton.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = tiles.size

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val poster: android.widget.ImageView = view.findViewById(R.id.tilePoster)
        val subtitle: android.widget.TextView = view.findViewById(R.id.tileSubtitle)
        val title: android.widget.TextView = view.findViewById(R.id.tileTitle)
        val menuButton: android.widget.ImageView = view.findViewById(R.id.tileMenuButton)
    }
}
