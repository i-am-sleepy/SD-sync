package com.sdsync.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdsync.app.data.TransferRecord
import com.sdsync.app.util.StorageUtils

class TransferAdapter(
    private var items: MutableList<TransferRecord>
) : RecyclerView.Adapter<TransferAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.tvFileName)
        val dest: TextView = view.findViewById(R.id.tvDest)
        val meta: TextView = view.findViewById(R.id.tvMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_transfer, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.fileName.text = item.fileName
        holder.dest.text = item.destPath
        holder.meta.text = "${StorageUtils.humanSize(item.sizeBytes)} · ${StorageUtils.formatTime(item.timestamp)} · ${item.status}"
    }

    fun update(newItems: List<TransferRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
