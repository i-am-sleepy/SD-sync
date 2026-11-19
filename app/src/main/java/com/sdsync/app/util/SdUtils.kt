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

    /**
     * Find an existing sub-directory or create it under [parent].
     *
     * Uses a FRESH [listFiles] lookup rather than [DocumentFile.findFile]: on some
     * devices (e.g. ColorOS) the tree's cached children list is not updated after
     * [createDirectory], so findFile() kept returning null for a directory that had
     * just been created — and createDirectory() then auto-suffixed it to " (1)",
     * " (2)", … producing one duplicate folder per file. listFiles() always queries
     * the content resolver, so it reliably sees directories created earlier.
     */
    fun getOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
        // 1) Fresh lookup.
        parent.listFiles()?.firstOrNull { it.name == name && it.isDirectory }?.let { return it }
        // 2) Create it.
        val created = parent.createDirectory(name)
        if (created != null) {
            // Defensive: if the provider auto-suffixed the name (a real collision we
            // didn't see), do NOT use that folder — remove the accidental duplicate and
            // return null so the transfer fails and retries (a fresh listing will then
            // find the real directory).
            if (created.name == name) return created
            runCatching { created.delete() }
            return null
        }
        // 3) createDirectory returned null — it may already exist now; re-check fresh.
        return parent.listFiles()?.firstOrNull { it.name == name && it.isDirectory }
    }

    /** Freshly list the direct children of [parent] (never a stale cache). */
    fun listChildren(parent: DocumentFile): List<DocumentFile> =
        parent.listFiles()?.toList() ?: emptyList()

    private val SUFFIX_RE = Regex("^(.*) \\((\\d+)\\)$")

    /**
     * Repair folders that an earlier bug duplicated as "Name (1)", "Name (2)", …
     * For every group of sibling folders sharing a base name, their contents are
     * merged into the canonical (unsuffixed) folder, then the empty suffixed
     * folders are removed. Runs recursively so nested levels are fixed too.
     *
     * A source file is deleted only AFTER its copy is verified by size; a source
     * directory is deleted only after all of its children have been moved. On any
     * copy failure the source is left untouched (no data loss).
     *
     * Returns the number of duplicate folders merged.
     */
    fun cleanupDuplicates(context: Context, root: DocumentFile): Int {
        var merged = 0
        val children = listChildren(root)
        // Group sibling directories by base name ("Pictures (3)" -> "Pictures").
        val byBase = HashMap<String, MutableList<DocumentFile>>()
        for (c in children) {
            if (!c.isDirectory) continue
            val n = c.name ?: continue
            val m = SUFFIX_RE.matchEntire(n)
            val base = if (m != null) m.groupValues[1] else n
            byBase.getOrPut(base) { mutableListOf() }.add(c)
        }
        // Merge each suffixed folder into its canonical sibling at this level.
        for ((_, dirs) in byBase) {
            if (dirs.size <= 1) continue
            // Canonical = the one whose name has no " (n)" suffix; fall back to first.
            val canonical = dirs.firstOrNull { d -> SUFFIX_RE.matchEntire(d.name ?: "") == null }
                ?: dirs.first()
            for (other in dirs) {
                if (other === canonical) continue
                if (mergeInto(context, other, canonical)) {
                    runCatching { other.delete() }
                    merged++
                }
            }
        }
        // Recurse into every directory to fix nested levels.
        for (c in children) {
            if (c.isDirectory) merged += cleanupDuplicates(context, c)
        }
        return merged
    }

    /** Recursively move every child of [source] into [target], then delete empties. */
    private fun mergeInto(context: Context, source: DocumentFile, target: DocumentFile): Boolean {
        for (child in listChildren(source)) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                val targetSub = getOrCreateDir(target, name) ?: return false
                if (!mergeInto(context, child, targetSub)) return false
                runCatching { child.delete() }
            } else {
                val destName = uniqueNameIn(target, name)
                val destFile = target.createFile("application/octet-stream", destName) ?: return false
                if (!copyDoc(context, child, destFile)) return false
                runCatching { child.delete() }
            }
        }
        return true
    }

    /**
     * Copy bytes from [src] to [dst]; verify by size; true only on a full match.
     * On ANY failure the (possibly partial/empty) [dst] is removed so a later
     * repair run doesn't accumulate junk files.
     */
    private fun copyDoc(context: Context, src: DocumentFile, dst: DocumentFile): Boolean {
        val expected = src.length()
        val resolver = context.contentResolver
        return try {
            val inStream = resolver.openInputStream(src.uri)
            val outStream = if (inStream != null) resolver.openOutputStream(dst.uri, "w") else null
            if (inStream == null || outStream == null) {
                runCatching { dst.delete() }
                return false
            }
            var written = 0L
            val buf = ByteArray(64 * 1024)
            inStream.use { i ->
                outStream.use { o ->
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        written += n
                    }
                    o.flush()
                }
            }
            if (written != expected) {
                runCatching { dst.delete() }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            runCatching { dst.delete() }
            false
        }
    }

    /** A name that does not already exist among [parent]'s children. */
    private fun uniqueNameIn(parent: DocumentFile, name: String): String {
        var candidate = name
        var idx = 1
        while (listChildren(parent).any { it.name == candidate }) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            candidate = "$base ($idx)$ext"
            idx++
        }
        return candidate
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
