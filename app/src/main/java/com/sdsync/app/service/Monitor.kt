package com.sdsync.app.service

import android.os.FileObserver
import com.sdsync.app.data.ConfigStore
import java.io.File

/**
 * Watches configured source roots with FileObserver (fast change detection) and
 * falls back to a lightweight periodic scan. A file is only handed off once its
 * size has been stable for ~10s, so still-writing media is never moved mid-write.
 *
 * Scans are throttled to at least [MIN_SCAN_GAP_MS] apart even when the observer
 * fires constantly, so a busy app (e.g. Telegram receiving) can't cause a
 * scan busy-loop that burns CPU/battery on low-end hardware.
 */
class Monitor(
    private val config: ConfigStore,
    private val onReadyFile: (File, String) -> Unit
) {

    companion object {
        private const val STABLE_MS = 10_000L
        private const val SCAN_INTERVAL_MS = 15_000L
        private const val MIN_SCAN_GAP_MS = 3_000L
        private const val SEEN_PURGE_MS = 60_000L

        /** Files still being written by the OS / download managers are never moved. */
        private val IN_PROGRESS_SUFFIXES = setOf(
            ".tmp", ".crdownload", ".part", ".partial", ".download", ".pending"
        )

        fun isInProgress(name: String): Boolean {
            if (name.startsWith(".")) return true
            val lower = name.lowercase()
            return IN_PROGRESS_SUFFIXES.any { lower.endsWith(it) }
        }
    }

    private data class Seen(val size: Long, val firstSeen: Long)

    private val observers = mutableListOf<FileObserver>()
    private val seen = HashMap<String, Seen>()

    @Volatile
    private var running = false

    @Volatile
    private var dirty = false

    private var lastScan = 0L

    private var scanThread: Thread? = null

    fun start() {
        running = true
        setupObservers()
        scanThread = Thread {
            while (running) {
                val now = System.currentTimeMillis()
                val gap = now - lastScan
                if (gap >= MIN_SCAN_GAP_MS && (dirty || gap >= SCAN_INTERVAL_MS)) {
                    dirty = false
                    lastScan = now
                    safeScan()
                } else {
                    // Sleep until the next scan is due (3s when dirty, 15s when idle).
                    val wait = if (dirty) MIN_SCAN_GAP_MS - gap else SCAN_INTERVAL_MS - gap
                    try {
                        Thread.sleep((wait + 200).coerceAtMost(SCAN_INTERVAL_MS))
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }.apply {
            name = "sdsync-monitor"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
        scanThread?.interrupt()
        scanThread = null
        seen.clear()
    }

    private fun setupObservers() {
        config.getSourceFolders().filter { it.enabled }.forEach { fc ->
            val dir = File(fc.path)
            if (dir.isDirectory) {
                val obs = object : FileObserver(
                    dir.absolutePath,
                    FileObserver.CREATE or FileObserver.MODIFY or FileObserver.MOVED_TO
                ) {
                    override fun onEvent(event: Int, path: String?) {
                        dirty = true
                    }
                }
                runCatching { obs.startWatching() }
                observers.add(obs)
            }
        }
    }

    private fun safeScan() {
        try {
            scanAll()
        } catch (_: Exception) {
        }
    }

    private fun scanAll() {
        val now = System.currentTimeMillis()
        config.getSourceFolders().filter { it.enabled }.forEach { fc ->
            val root = File(fc.path)
            if (root.isDirectory) walk(root, root)
        }
        // Purge entries for files that no longer exist or went stale, so the map
        // stays bounded over months of use.
        seen.entries.removeIf { (key, s) ->
            !File(key).exists() || now - s.firstSeen > SEEN_PURGE_MS
        }
    }

    private fun walk(root: File, dir: File) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                walk(root, f)
            } else if (f.isFile) {
                if (!isInProgress(f.name)) checkFile(root, f)
            }
        }
    }

    private fun checkFile(root: File, f: File) {
        val rel = f.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
        val now = System.currentTimeMillis()
        val sz = f.length()
        val prev = seen[f.absolutePath]
        if (prev == null) {
            seen[f.absolutePath] = Seen(sz, now)
            return
        }
        if (prev.size == sz) {
            if (now - prev.firstSeen >= STABLE_MS) {
                seen.remove(f.absolutePath)
                onReadyFile(f, rel)
            }
        } else {
            seen[f.absolutePath] = Seen(sz, now)
        }
    }
}
