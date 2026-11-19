package com.sdsync.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists app configuration (source folders, SD tree URI, stats) in SharedPreferences.
 * Folder lists are stored as a small JSON array — no extra table needed.
 */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var sdTreeUri: String?
        get() = prefs.getString("sd_tree_uri", null)
        set(value) {
            prefs.edit().putString("sd_tree_uri", value).apply()
        }

    var bootAutostart: Boolean
        get() = prefs.getBoolean("boot_autostart", true)
        set(value) {
            prefs.edit().putBoolean("boot_autostart", value).apply()
        }

    var totalMovedBytes: Long
        get() = prefs.getLong("total_moved_bytes", 0L)
        set(value) {
            prefs.edit().putLong("total_moved_bytes", value).apply()
        }

    var totalFilesMoved: Long
        get() = prefs.getLong("total_files_moved", 0L)
        set(value) {
            prefs.edit().putLong("total_files_moved", value).apply()
        }

    fun getSourceFolders(): List<FolderConfig> {
        val arr = JSONArray(prefs.getString("source_folders", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FolderConfig(o.getString("path"), o.optBoolean("enabled", true))
        }
    }

    fun saveSourceFolders(list: List<FolderConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("path", it.path).put("enabled", it.enabled)) }
        prefs.edit().putString("source_folders", arr.toString()).apply()
    }
}
