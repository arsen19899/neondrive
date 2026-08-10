package com.neondrive.launcher.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.neondrive.launcher.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Поднимает автоматику сразу после старта ГУ, ещё до открытия рабочего стола. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                val appContext = context.applicationContext
                runCatching { AutomationService.start(appContext) }

                // Поднять сам рабочий стол поверх — не только фоновую автоматику
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        val s = runCatching { SettingsRepository(appContext).settings.first() }.getOrNull()
                        if (s != null && s.startOnBoot && s.startOnScreenOn) {
                            ForegroundLauncher.bringToFront(appContext)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
