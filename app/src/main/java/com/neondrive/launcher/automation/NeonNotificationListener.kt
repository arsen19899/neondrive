package com.neondrive.launcher.automation

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class PhoneNotification(
    val pkg: String,
    val title: String,
    val text: String,
    val fromPairedDevice: Boolean,
    val at: Long = System.currentTimeMillis()
)

/**
 * Слушает уведомления системы. Нужен для двух вещей:
 *  1) реакция музыки на уведомления подключённого телефона (пауза / приглушение);
 *  2) доступ к чужим MediaSession — без него не порулить Яндекс.Музыкой.
 */
class NeonNotificationListener : NotificationListenerService() {

    companion object {
        /** Пакеты, через которые ГУ показывает уведомления, прилетевшие с телефона по BT. */
        val PAIRED_DEVICE_PACKAGES = setOf(
            "com.android.bluetooth",
            "com.google.android.bluetooth",
            "com.android.bluetoothmidiservice",
            "com.syu.bt",                 // прошивки SYU / Topway
            "com.yyw.btservice",
            "com.hct.bt",
            "com.ts.bt",
            "com.autochips.bt",
            "com.microntek.bt",           // MTC / Microntek
            "com.aiyue.bt",
            "com.carbit.bt"
        )

        private val _events = MutableSharedFlow<PhoneNotification>(extraBufferCapacity = 16)
        val events: SharedFlow<PhoneNotification> = _events

        /** Выдан ли доступ к уведомлениям. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            val me = ComponentName(context, NeonNotificationListener::class.java)
            return flat.split(":").any {
                val cn = ComponentName.unflattenFromString(it)
                cn != null && cn.packageName == me.packageName
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return                       // свои же уведомления
        val n = sbn.notification ?: return

        // Постоянные и «тихие» уведомления музыку трогать не должны
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (sbn.isOngoing) return
        val isMediaStyle = n.extras?.getParcelable<android.os.Parcelable>(
            Notification.EXTRA_MEDIA_SESSION
        ) != null
        if (isMediaStyle) return

        val extras = n.extras
        _events.tryEmit(
            PhoneNotification(
                pkg = pkg,
                title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                fromPairedDevice = pkg in PAIRED_DEVICE_PACKAGES || pkg.contains(".bt")
            )
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Как только доступ появился — подхватываем чужие медиасессии
        runCatching { com.neondrive.launcher.media.PlayerHub.external.refresh() }
    }
}
