package com.retrotube.app.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemCollectionVideoRowBinding

/** Every video in the library as one flat, checkable list -- title plus its full
 *  folder path as a subtitle, so a bare filename is still identifiable. Toggling
 *  a row fires immediately rather than waiting on a save button. Used by the TV
 *  Mode "pick individual videos" source picker. */
class VideoCheckboxRowAdapter(
    private val onToggle: (LibraryVideoEntry, Boolean) -> Unit,
) : RecyclerView.Adapter<VideoCheckboxRowAdapter.ViewHolder>() {

    data class Row(val entry: LibraryVideoEntry, val title: String, var checked: Boolean)

    private var rows: List<Row> = emptyList()

    fun submitList(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemCollectionVideoRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.binding.rowTitle.text = row.title
        holder.binding.rowPath.text = row.entry.pathLabel
        holder.binding.rowCheckbox.isChecked = row.checked
        holder.binding.rowRoot.setOnClickListener {
            row.checked = !row.checked
            holder.binding.rowCheckbox.isChecked = row.checked
            onToggle(row.entry, row.checked)
        }
    }

    override fun getItemCount(): Int = rows.size

    class ViewHolder(val binding: ItemCollectionVideoRowBinding) : RecyclerView.ViewHolder(binding.root)
}
