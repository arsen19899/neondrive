package com.neondrive.launcher.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.neondrive.launcher.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Подача питания на ГУ (зажигание через ACC) — ещё один момент, когда оболочка
 * должна сама выйти на передний план, как и при пробуждении экрана. Настройка
 * общая с «Запускать при пробуждении», чтобы не плодить переключатели ради
 * одного и того же намерения пользователя — «всегда быть поверх».
 *
 * ACTION_POWER_CONNECTED — защищённая системная рассылка, декларативный приёмник
 * в манифесте получает её без ограничений implicit-broadcast, введённых для
 * прикладных интентов.
 */
class PowerConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_POWER_CONNECTED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = runCatching {
                    SettingsRepository(appContext).settings.first().startOnScreenOn
                }.getOrDefault(true)
                if (enabled) ForegroundLauncher.bringToFront(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
