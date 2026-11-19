package com.sdsync.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.widget.RemoteViews
import com.sdsync.app.service.TransferService
import com.sdsync.app.util.StorageUtils

/**
 * Small home-screen widget: monitoring status, internal free space, and a
 * Start/Pause button. Updates only when state changes (no polling).
 */
class SdSyncWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.sdsync.app.widget.TOGGLE"

        /** Refresh every widget instance (call from the service on state change). */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SdSyncWidget::class.java))
            if (ids == null || ids.size == 0) return
            val widget = SdSyncWidget()
            for (id in ids) widget.update(context, mgr, id)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) update(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (TransferService.isRunning) {
                context.startService(Intent(context, TransferService::class.java).apply {
                    action = TransferService.ACTION_PAUSE
                })
            } else {
                context.startService(Intent(context, TransferService::class.java).apply {
                    action = TransferService.ACTION_START
                })
            }
            updateAll(context)
        }
    }

    private fun update(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_sdsync)

        val status = when {
            TransferService.sdFull -> context.getString(R.string.widget_status_sd_full)
            !TransferService.isRunning && TransferService.sdReady ->
                context.getString(R.string.widget_status_paused)
            !TransferService.sdReady -> context.getString(R.string.widget_status_no_sd)
            else -> context.getString(R.string.widget_status_monitoring)
        }
        views.setTextViewText(R.id.widget_status, status)

        val free = StatFs(Environment.getDataDirectory().path).availableBytes
        views.setTextViewText(
            R.id.widget_storage,
            context.getString(R.string.widget_internal_free) + ": " + StorageUtils.humanSize(free)
        )

        views.setTextViewText(
            R.id.widget_toggle,
            context.getString(
                if (TransferService.isRunning) R.string.widget_pause else R.string.widget_start
            )
        )

        val toggle = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, SdSyncWidget::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_toggle, toggle)

        val openApp = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_status, openApp)

        mgr.updateAppWidget(appWidgetId, views)
    }
}
