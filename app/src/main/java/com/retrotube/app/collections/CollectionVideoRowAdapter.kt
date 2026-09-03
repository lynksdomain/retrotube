package com.retrotube.app.collections

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemCollectionVideoRowBinding
import com.retrotube.app.library.LibraryVideoEntry

/** Every video in the library as one flat, checkable list -- title plus its full
 *  folder path as a subtitle, so a bare filename is still identifiable. Toggling
 *  a row fires immediately rather than waiting on a save button, since adding or
 *  removing a single video from a collection has no real "undo" need. */
class CollectionVideoRowAdapter(
    private val onToggle: (LibraryVideoEntry, Boolean) -> Unit,
) : RecyclerView.Adapter<CollectionVideoRowAdapter.ViewHolder>() {

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
