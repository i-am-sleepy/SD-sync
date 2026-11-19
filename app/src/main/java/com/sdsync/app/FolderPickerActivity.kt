package com.sdsync.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** A lightweight directory browser that returns a real path for a source folder. */
class FolderPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "extra_path"
    }

    private lateinit var tvCurrent: TextView
    private lateinit var listDirs: ListView
    private lateinit var btnSelect: Button
    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var items: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_picker)

        tvCurrent = findViewById(R.id.tvCurrentPath)
        listDirs = findViewById(R.id.listDirs)
        btnSelect = findViewById(R.id.btnSelect)

        btnSelect.setOnClickListener {
            val result = Intent().putExtra(EXTRA_PATH, currentDir.absolutePath)
            setResult(Activity.RESULT_OK, result)
            finish()
        }

        listDirs.setOnItemClickListener { _, _, position, _ ->
            val dir = items[position]
            if (dir.isDirectory) {
                currentDir = dir
                load()
            }
        }

        load()
    }

    private fun load() {
        tvCurrent.text = currentDir.absolutePath
        val children = currentDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        val newItems = mutableListOf<File>()
        currentDir.parentFile?.let { newItems.add(it) }
        newItems.addAll(children)
        items = newItems
        listDirs.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map { it.absolutePath })
    }
}
