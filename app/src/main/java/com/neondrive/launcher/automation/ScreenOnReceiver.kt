package com.neondrive.launcher.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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
        ForegroundLauncher.bringToFront(context)
    }
}
