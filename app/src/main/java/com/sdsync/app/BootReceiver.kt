package com.sdsync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sdsync.app.data.ConfigStore
import com.sdsync.app.service.TransferService

/** Optionally restarts monitoring after the device boots. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val config = ConfigStore(context)
            if (config.bootAutostart && config.sdTreeUri != null) {
                val i = Intent(context, TransferService::class.java).apply {
                    action = TransferService.ACTION_START
                }
                context.startService(i)
            }
        }
    }
}
