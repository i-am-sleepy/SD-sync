package com.sdsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.sdsync.app.R
import com.sdsync.app.data.ConfigStore
import com.sdsync.app.data.DbHelper
import com.sdsync.app.util.SdUtils
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Foreground service that runs monitoring and sequential transfers.
 * All transfers run on a single background worker thread (never the main thread),
 * so large files never block the UI. Start/Pause is controlled from MainActivity.
 */
class TransferService : Service() {

    companion object {
        const val ACTION_START = "com.sdsync.app.START"
        const val ACTION_PAUSE = "com.sdsync.app.PAUSE"
        private const val CHANNEL_ID = "transfer_channel"
        private const val NOTIF_ID = 1
        private const val MAX_ATTEMPTS = 5

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var config: ConfigStore
    private lateinit var db: DbHelper
    private lateinit var engine: TransferEngine
    private var monitor: Monitor? = null
    private var worker: Thread? = null
    private val attempts = HashMap<String, Int>()

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
        startForeground(NOTIF_ID, buildNotification("Monitoring…"))
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
        if (sdRoot == null) {
            updateNotification("SD card not selected")
            stopSelf()
            return
        }
        running = true
        startWorker()
        // Recover interrupted transfers first (on the worker thread, not main).
        enqueue { recoverPending(sdRoot) }
        monitor = Monitor(config) { file, rel -> enqueue { handleReadyFile(file, rel, sdRoot) } }
        monitor?.start()
        updateNotification("Monitoring active")
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
            }
        }
    }

    private fun handleReadyFile(file: File, rel: String, sdRoot: DocumentFile) {
        val key = file.absolutePath
        val n = attempts[key] ?: 0
        if (n >= MAX_ATTEMPTS) return
        val size = file.length()
        when (val result = engine.transferFile(file, rel, sdRoot)) {
            is TransferEngine.Result.Success -> {
                attempts.remove(key)
                recordSuccess(file.name, rel, size)
            }
            is TransferEngine.Result.Failed -> {
                val pid = db.findPendingId(key)
                if (pid != null) db.bumpAttempts(pid, n + 1)
                attempts[key] = n + 1
                db.insertTransfer(file.name, rel, size, "failed")
            }
            TransferEngine.Result.Skipped -> attempts.remove(key)
        }
    }

    /** Update dashboard stats and history. [size] must be captured before the delete. */
    private fun recordSuccess(fileName: String, relPath: String, size: Long) {
        config.totalMovedBytes += size
        config.totalFilesMoved += 1
        db.insertTransfer(fileName, relPath, size, "done")
    }

    override fun onDestroy() {
        stopMonitoring()
        isRunning = false
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
