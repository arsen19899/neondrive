package com.neondrive.launcher.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import com.neondrive.launcher.media.PlayerHub

/**
 * Ловит кнопки руля, когда лаунчер не на переднем плане.
 * Многие ГУ шлют нажатия именно как ACTION_MEDIA_BUTTON.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        if (event == null) return

        PlayerHub.init(context.applicationContext)
        if (SteeringWheelManager.handleKeyEvent(event)) {
            if (isOrderedBroadcast) abortBroadcast()
        }
    }
}
