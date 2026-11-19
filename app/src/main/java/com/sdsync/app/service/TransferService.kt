package com.sdsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.sdsync.app.MainActivity
import com.sdsync.app.R
import com.sdsync.app.SdSyncWidget
import com.sdsync.app.data.ConfigStore
import com.sdsync.app.data.DbHelper
import com.sdsync.app.util.SdUtils
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Foreground service that runs monitoring and sequential transfers.
 * All transfers run on a single background worker thread (never the main thread),
 * so large files never block the UI. Start/Pause is controlled from MainActivity,
 * the notification, and the home-screen widget.
 */
class TransferService : Service() {

    companion object {
        const val ACTION_START = "com.sdsync.app.START"
        const val ACTION_PAUSE = "com.sdsync.app.PAUSE"
        private const val CHANNEL_ID = "transfer_channel"
        private const val NOTIF_ID = 1
        private const val MAX_ATTEMPTS = 5

        /** True while the service is running (used by the dashboard and widget). */
        @Volatile
        var isRunning = false
            private set

        /** True while the service is running but the SD card has no room. */
        @Volatile
        var sdFull = false
            private set

        /** True when an SD destination tree is configured and resolvable. */
        @Volatile
        var sdReady = false
            private set

        private fun setSdFull(context: Context, full: Boolean) {
            sdFull = full
            SdSyncWidget.updateAll(context)
            notifyStateChanged()
        }

        // ---------- state-change listeners (dashboard refreshes on change, not polling) ----------

        private val stateListeners = mutableSetOf<() -> Unit>()

        fun addStateListener(listener: () -> Unit) {
            synchronized(stateListeners) { stateListeners.add(listener) }
        }

        fun removeStateListener(listener: () -> Unit) {
            synchronized(stateListeners) { stateListeners.remove(listener) }
        }

        private fun notifyStateChanged() {
            val snapshot = synchronized(stateListeners) { stateListeners.toList() }
            snapshot.forEach { runCatching { it() } }
        }
    }

    private lateinit var config: ConfigStore
    private lateinit var db: DbHelper
    private lateinit var engine: TransferEngine
    private var monitor: Monitor? = null
    private var worker: Thread? = null

    // Retry budget per source path. Bounded (eldest evicted past 500) so a folder
    // full of permanently-failing files can't grow this map without limit.
    // Touched only on the worker thread.
    private val attempts = object : LinkedHashMap<String, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > 500
    }

    // Paths currently being transferred. The monitor re-scans while a transfer is
    // in flight; this set prevents the same file from being enqueued/copied twice.
    // Thread-safe: checked on the monitor thread (enqueue filter) and the worker
    // thread (authoritative check inside handleReadyFile).
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val queue = LinkedBlockingQueue<() -> Unit>()

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        config = ConfigStore(this)
        db = DbHelper(this)
        engine = TransferEngine(this, db)
        createChannel()
        isRunning = true
        sdReady = SdUtils.getSdRoot(this) != null
        startForeground(NOTIF_ID, buildNotification("Monitoring…"))
        SdSyncWidget.updateAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (running) return
        val sdRoot = SdUtils.getSdRoot(this)
        sdReady = sdRoot != null
        if (sdRoot == null) {
            updateNotification("SD card not selected")
            SdSyncWidget.updateAll(this)
            stopSelf()
            return
        }
        running = true
        startWorker()
        // Recover interrupted transfers first (on the worker thread, not main).
        enqueue { recoverPending(sdRoot) }
        monitor = Monitor(config) { file, rel ->
            // Skip files that are already being transferred — the monitor re-scans
            // while a transfer is in flight; without this, a large file would be
            // enqueued (and re-processed) on every scan until it finishes.
            if (!inFlight.contains(file.absolutePath)) {
                enqueue { handleReadyFile(file, rel, sdRoot) }
            }
        }
        monitor?.start()
        updateNotification("Monitoring active")
        SdSyncWidget.updateAll(this)
        notifyStateChanged()
    }

    private fun stopMonitoring() {
        running = false
        monitor?.stop()
        monitor = null
        worker?.interrupt()
        worker = null
    }

    private fun startWorker() {
        worker = Thread {
            while (true) {
                val task: (() -> Unit)? = try {
                    queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                }
                if (task == null) {
                    if (!running) break
                    continue
                }
                try {
                    task()
                } catch (_: Exception) {
                }
            }
        }.apply {
            name = "sdsync-worker"
            isDaemon = true
            start()
        }
    }

    private fun enqueue(task: () -> Unit) {
        queue.add(task)
    }

    /** Retry interrupted/failed transfers recorded in the pending table. */
    private fun recoverPending(sdRoot: DocumentFile) {
        for (op in db.getPending()) {
            if (op.attempts >= MAX_ATTEMPTS) {
                db.deletePending(op.id)
                continue
            }
            val source = File(op.sourcePath)
            if (!source.exists()) {
                db.deletePending(op.id)
                continue
            }
            when (val result = engine.transferFile(source, op.relPath, sdRoot)) {
                is TransferEngine.Result.Success -> {
                    db.deletePending(op.id)
                    recordSuccess(source.name, op.relPath, op.sizeBytes)
                }
                is TransferEngine.Result.Failed -> db.bumpAttempts(op.id, op.attempts + 1)
                TransferEngine.Result.Skipped -> db.deletePending(op.id)
                TransferEngine.Result.InsufficientSpace -> {
                    // Leave the pending row; keep retrying once space frees.
                    setSdFull(this, true)
                    updateNotification("SD card full — transfers paused")
                }
            }
        }
    }

    private fun handleReadyFile(file: File, rel: String, sdRoot: DocumentFile) {
        val key = file.absolutePath
        // Authoritative in-flight guard: add() returns false if this path is
        // already being transferred, so a concurrent enqueue can't double-copy.
        if (!inFlight.add(key)) return
        try {
            val n = attempts[key] ?: 0
            if (n >= MAX_ATTEMPTS) return
            val size = file.length()
            when (val result = engine.transferFile(file, rel, sdRoot)) {
                is TransferEngine.Result.Success -> {
                    attempts.remove(key)
                    if (sdFull) {
                        setSdFull(this, false)
                        updateNotification("Monitoring active")
                    }
                    recordSuccess(file.name, rel, size)
                }
                is TransferEngine.Result.Failed -> {
                    val pid = db.findPendingId(key)
                    if (pid != null) db.bumpAttempts(pid, n + 1)
                    attempts[key] = n + 1
                    db.insertTransfer(file.name, rel, size, "failed")
                    notifyStateChanged()
                }
                TransferEngine.Result.Skipped -> attempts.remove(key)
                TransferEngine.Result.InsufficientSpace -> {
                    // Don't count against the retry budget; pause until space frees.
                    setSdFull(this, true)
                    updateNotification("SD card full — transfers paused")
                }
            }
        } finally {
            inFlight.remove(key)
        }
    }

    /** Update dashboard stats and history. [size] must be captured before the delete. */
    private fun recordSuccess(fileName: String, relPath: String, size: Long) {
        config.totalMovedBytes += size
        config.totalFilesMoved += 1
        db.insertTransfer(fileName, relPath, size, "done")
        SdSyncWidget.updateAll(this)
        notifyStateChanged()
    }

    override fun onDestroy() {
        stopMonitoring()
        isRunning = false
        sdFull = false
        SdSyncWidget.updateAll(this)
        notifyStateChanged()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "SD Sync", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

        // Quick actions: Stop monitoring / Open app (channel importance is LOW,
        // so actions stay out of the expanded view — they appear in the shade).
        builder.addAction(
            0,
            getString(R.string.btn_pause),
            PendingIntent.getService(
                this, 1,
                Intent(this, TransferService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        builder.addAction(
            0,
            getString(R.string.app_name),
            PendingIntent.getActivity(
                this, 2, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
