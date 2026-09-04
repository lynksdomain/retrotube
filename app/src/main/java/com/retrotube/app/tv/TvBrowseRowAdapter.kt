package com.retrotube.app.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemTvBrowseRowBinding

/** One row in a folder browser -- a subfolder (tapping the label navigates
 *  into it) or a video (tapping the label toggles it as a source directly,
 *  since there's nothing to navigate into). Either kind can also carry its
 *  own add/remove button on the right, so a folder can be added as a whole
 *  source without having to drill into it first. */
data class TvBrowseRow(
    val label: String,
    val onLabelClick: () -> Unit,
    val addButtonLabel: String? = null,
    val onAddClick: (() -> Unit)? = null,
)

class TvBrowseRowAdapter : RecyclerView.Adapter<TvBrowseRowAdapter.ViewHolder>() {

    private var rows: List<TvBrowseRow> = emptyList()

    fun submitList(newRows: List<TvBrowseRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemTvBrowseRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.binding.rowLabel.text = row.label
        holder.binding.rowLabel.setOnClickListener { row.onLabelClick() }
        if (row.addButtonLabel != null && row.onAddClick != null) {
            holder.binding.rowAddButton.visibility = android.view.View.VISIBLE
            holder.binding.rowAddButton.text = row.addButtonLabel
            holder.binding.rowAddButton.setOnClickListener { row.onAddClick.invoke() }
        } else {
            holder.binding.rowAddButton.visibility = android.view.View.GONE
        }
    }

    override fun getItemCount(): Int = rows.size

    class ViewHolder(val binding: ItemTvBrowseRowBinding) : RecyclerView.ViewHolder(binding.root)
}
