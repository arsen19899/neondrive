package com.neondrive.launcher.automation

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.neondrive.launcher.MainActivity

/**
 * Общая логика «поднять оболочку поверх текущего экрана».
 *
 * Используется всеми триггерами пробуждения: включение экрана, разблокировка,
 * подача питания (зажигание) и загрузка системы — чтобы NeonDrive гарантированно
 * оказывался на переднем плане, а не оставался позади того, что было открыто
 * последним.
 */
object ForegroundLauncher {

    fun bringToFront(context: Context) {
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
