package com.sdsync.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdsync.app.data.ConfigStore
import java.io.File

/**
 * Nested tree browser rooted at internal storage (primary shared storage).
 * The "storage/emulated/0" prefix is hidden - the tree starts at its children.
 * Users tick any number of folders; [Confirm] returns all of them at once.
 */
class FolderPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "extra_paths"
    }

    private data class TreeNode(val file: File, val depth: Int) {
        var expanded: Boolean = false
        var checked: Boolean = false
    }

    private val root = File(Environment.getExternalStorageDirectory().absolutePath)
    private val nodes = mutableListOf<TreeNode>()
    private val selected = linkedSetOf<String>()
    private lateinit var adapter: TreeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_picker)

        val recyclerView = findViewById<RecyclerView>(R.id.tree_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TreeAdapter()
        recyclerView.adapter = adapter

        // Pre-check folders already configured in the app.
        ConfigStore(this).getSourceFolders().forEach { fc ->
            if (fc.enabled) selected.add(fc.path)
        }

        // Top level = children of internal storage (the emulated/0 prefix is hidden).
        val children = root.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedWith(compareBy({ it.name.lowercase() }, { it.name }))
            ?: emptyList()
        children.forEach { dir ->
            nodes.add(TreeNode(dir, 0).apply { checked = selected.contains(dir.absolutePath) })
        }
        adapter.notifyDataSetChanged()

        findViewById<Button>(R.id.btn_confirm).setOnClickListener {
            val paths = nodes.filter { it.checked }.map { it.file.absolutePath }
            val result = Intent().putExtra(EXTRA_PATH, HashSet(paths))
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    private fun toggleExpand(node: TreeNode) {
        val idx = nodes.indexOf(node)
        if (idx < 0) return
        if (node.expanded) {
            // Collapse: remove this node's descendants.
            var i = idx + 1
            while (i < nodes.size && nodes[i].depth > node.depth) i++
            nodes.subList(idx + 1, i).clear()
            node.expanded = false
        } else {
            // Expand: load child directories and insert them right after this node.
            val kids = node.file.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedWith(compareBy({ it.name.lowercase() }, { it.name }))
                ?: emptyList()
            nodes.addAll(
                idx + 1,
                kids.map { dir ->
                    TreeNode(dir, node.depth + 1).apply { checked = selected.contains(dir.absolutePath) }
                }
            )
            node.expanded = true
        }
        adapter.notifyDataSetChanged()
    }

    private inner class TreeAdapter : RecyclerView.Adapter<TreeAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val name: TextView = view.findViewById(R.id.folder_name)
            val arrow: TextView = view.findViewById(R.id.expand_arrow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tree_node, parent, false))

        override fun getItemCount(): Int = nodes.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val node = nodes[position]
            holder.name.text = node.file.name
            holder.name.setPadding((node.depth * 24 + 8).toDp(), 0, 0, 0)
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = node.checked
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                node.checked = isChecked
                if (isChecked) selected.add(node.file.absolutePath)
                else selected.remove(node.file.absolutePath)
            }
            holder.arrow.text = if (node.expanded) "▾" else "›"
            holder.itemView.setOnClickListener { toggleExpand(node) }
        }

        private fun Int.toDp(): Int = (this * resources.displayMetrics.density).toInt()
    }
}
