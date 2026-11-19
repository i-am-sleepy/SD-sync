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

    /**
     * Best-effort free bytes on the removable SD volume, or -1 if unknown.
     * Uses freeBytes (total free) rather than availableBytes (which subtracts
     * the root-reserved block pool and can under-report by 10-20% on some cards).
     */
    fun sdFreeBytes(): Long {
        val mount = findSdMountPath() ?: return -1
        return try {
            StatFs(mount).freeBytes
        } catch (e: Exception) {
            -1
        }
    }

    @Volatile
    private var cachedMount: String? = null

    @Volatile
    private var mountCacheTime = 0L

    private const val MOUNT_CACHE_MS = 60_000L

    /**
     * Locate the removable SD card's real mount path under /storage (skips primary
     * storage). The result is cached for 60s — the mount point rarely changes and
     * this is called on every free-space check.
     */
    private fun findSdMountPath(): String? {
        val now = System.currentTimeMillis()
        cachedMount?.let { if (now - mountCacheTime < MOUNT_CACHE_MS) return it }
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
            if (d.canWrite()) {
                cachedMount = d.absolutePath
                mountCacheTime = now
                return d.absolutePath
            }
        }
        return null
    }
}
