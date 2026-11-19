package com.sdsync.app.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.sdsync.app.data.ConfigStore
import java.io.File

/** Helpers for SAF-based SD card access and free-space checks. */
object SdUtils {

    /** The persisted, user-granted SD card tree root, or null if none selected. */
    fun getSdRoot(context: Context): DocumentFile? {
        val uriStr = ConfigStore(context).sdTreeUri ?: return null
        return try {
            DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
        } catch (e: Exception) {
            null
        }
    }

    /** Find an existing sub-directory or create it under [parent]. */
    fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        return parent.createDirectory(name)
    }

    /** Best-effort free bytes on the removable SD volume, or -1 if unknown. */
    fun sdFreeBytes(): Long {
        val mount = findSdMountPath() ?: return -1
        return try {
            StatFs(mount).availableBytes
        } catch (e: Exception) {
            -1
        }
    }

    /** Locate the removable SD card's real mount path under /storage (skips primary storage). */
    private fun findSdMountPath(): String? {
        val primary = try {
            Environment.getExternalStorageDirectory().canonicalPath
        } catch (e: Exception) {
            Environment.getExternalStorageDirectory().absolutePath
        }
        val storageDir = File("/storage")
        val dirs = storageDir.listFiles() ?: return null
        for (d in dirs) {
            if (!d.isDirectory) continue
            val p = try {
                d.canonicalPath
            } catch (e: Exception) {
                d.absolutePath
            }
            if (p == primary) continue
            if (d.canWrite()) return d.absolutePath
        }
        return null
    }
}
