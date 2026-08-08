package com.neondrive.launcher.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Поднимает автоматику сразу после старта ГУ, ещё до открытия рабочего стола. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                runCatching { AutomationService.start(context.applicationContext) }
            }
        }
    }
}
