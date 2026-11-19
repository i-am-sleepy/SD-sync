package com.sdsync.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdsync.app.data.FolderConfig

class FolderAdapter(
    private var items: MutableList<FolderConfig>,
    private val onRemove: (FolderConfig) -> Unit
) : RecyclerView.Adapter<FolderAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val path: TextView = view.findViewById(R.id.tvFolderPath)
        val remove: Button = view.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.path.text = item.path
        holder.remove.setOnClickListener { onRemove(item) }
    }

    fun update(newItems: List<FolderConfig>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
