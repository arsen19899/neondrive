package com.neondrive.launcher.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.neondrive.launcher.MainActivity

/**
 * Пробуждение головного устройства.
 *
 * ACTION_SCREEN_ON система рассылает только на лету, поэтому приёмник
 * регистрируется из [AutomationService], а не в манифесте.
 *
 * Запуск активности из фона на Android 10+ разрешён приложениям, назначенным
 * домашним экраном, — поэтому настройка работает в паре с «Сделать оболочкой
 * по умолчанию».
 */
class ScreenOnReceiver(
    private val isEnabled: () -> Boolean
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_USER_PRESENT) return
        if (!isEnabled()) return

        // Во время разговора выдёргивать экран телефона было бы грубо
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = runCatching { audio?.mode }.getOrNull() ?: AudioManager.MODE_NORMAL
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return

        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            )
        }
    }
}
