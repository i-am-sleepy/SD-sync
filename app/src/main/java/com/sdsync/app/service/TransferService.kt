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

/**
 * Foreground service that runs monitoring and sequential transfers.
 * Start/Pause is controlled from MainActivity via explicit intents.
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
    private val processing = HashSet<String>()
    private val attempts = HashMap<String, Int>()

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
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (monitor != null) return
        val sdRoot = SdUtils.getSdRoot(this)
        if (sdRoot == null) {
            updateNotification("SD card not selected")
            stopSelf()
            return
        }
        // recover interrupted transfers first
        engine.retryPending(sdRoot)
        monitor = Monitor(config) { file, rel -> handleReadyFile(file, rel, sdRoot) }
        monitor?.start()
        updateNotification("Monitoring active")
    }

    private fun handleReadyFile(file: File, rel: String, sdRoot: DocumentFile) {
        val key = file.absolutePath
        if (!processing.add(key)) return
        try {
            val n = attempts[key] ?: 0
            if (n >= MAX_ATTEMPTS) {
                attempts.remove(key)
                return
            }
            when (val result = engine.transferFile(file, rel, sdRoot)) {
                is TransferEngine.Result.Success -> {
                    attempts.remove(key)
                    config.totalMovedBytes += file.length()
                    config.totalFilesMoved += 1
                    db.insertTransfer(file.name, rel, file.length(), "done")
                }
                is TransferEngine.Result.Failed -> {
                    attempts[key] = n + 1
                    db.insertTransfer(file.name, rel, file.length(), "failed")
                }
                TransferEngine.Result.Skipped -> attempts.remove(key)
            }
        } finally {
            processing.remove(key)
        }
    }

    override fun onDestroy() {
        monitor?.stop()
        monitor = null
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
