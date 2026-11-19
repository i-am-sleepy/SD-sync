package com.sdsync.app.service

import android.os.FileObserver
import com.sdsync.app.data.ConfigStore
import java.io.File

/**
 * Watches configured source roots with FileObserver (fast change detection) and
 * falls back to a lightweight periodic scan. A file is only handed off once its
 * size has been stable for ~10s, so still-writing media is never moved mid-write.
 */
class Monitor(
    private val config: ConfigStore,
    private val onReadyFile: (File, String) -> Unit
) {

    private data class Seen(val size: Long, val firstSeen: Long)

    private val observers = mutableListOf<FileObserver>()
    private val seen = HashMap<String, Seen>()

    @Volatile
    private var running = false

    @Volatile
    private var dirty = false

    private var scanThread: Thread? = null

    fun start() {
        running = true
        setupObservers()
        scanThread = Thread {
            while (running) {
                if (dirty) {
                    dirty = false
                } else {
                    try {
                        Thread.sleep(15_000)
                    } catch (e: InterruptedException) {
                        break
                    }
                    if (!running) break
                }
                safeScan()
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
        config.getSourceFolders().filter { it.enabled }.forEach { fc ->
            val root = File(fc.path)
            if (root.isDirectory) walk(root, root)
        }
    }

    private fun walk(root: File, dir: File) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                walk(root, f)
            } else if (f.isFile) {
                checkFile(root, f)
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
            if (now - prev.firstSeen >= 10_000) {
                seen.remove(f.absolutePath)
                onReadyFile(f, rel)
            }
        } else {
            seen[f.absolutePath] = Seen(sz, now)
        }
    }
}
