package com.neondrive.launcher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Поднять процесс стороннего музыкального приложения, не показывая его экран.
 *
 * Многие приложения (в т.ч. Яндекс.Музыка) поддерживают Android Auto и поэтому
 * объявляют MediaBrowserService — служебный компонент без своего интерфейса,
 * которым Android Auto управляет воспроизведением. Если он есть, обычное
 * bindService-подключение к нему само поднимает процесс приложения и создаёт
 * его MediaSession — и всё это никогда не показывает Activity на экране.
 *
 * Если такого сервиса нет (приложение не поддерживает Auto), единственный
 * оставшийся способ получить сессию — реально открыть Activity. В этом случае
 * [ExternalSessionBridge.connect] сама откроет её и тут же вернёт фокус оболочке
 * (см. [PlayerHub.switchToYandex]) — полностью незаметно это не сделать,
 * это уже честный компромисс, а не баг.
 */
object SilentAppLauncher {

    /** Есть ли у пакета MediaBrowserService — тогда можно разбудить его без экрана. */
    private fun findBrowserService(context: Context, pkg: String): ComponentName? {
        val intent = Intent("android.media.browse.MediaBrowserService").setPackage(pkg)
        val info = runCatching {
            context.packageManager.queryIntentServices(intent, 0)
        }.getOrNull()?.firstOrNull()?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    /**
     * Подключиться к сервису и разбудить процесс приложения, не трогая экран.
     * true — подключение состоялось, процесс жив, дальше сессию подхватит
     * обычный [ExternalSessionBridge] через MediaSessionManager.
     */
    suspend fun wake(context: Context, pkg: String, timeoutMs: Long = 4000): Boolean {
        val component = findBrowserService(context, pkg) ?: return false
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var browser: MediaBrowserCompat? = null

            val timeoutRunnable = Runnable {
                if (cont.isActive) cont.resume(false)
            }

            val callback = object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    handler.removeCallbacks(timeoutRunnable)
                    if (cont.isActive) cont.resume(true)
                }
                override fun onConnectionFailed() {
                    handler.removeCallbacks(timeoutRunnable)
                    if (cont.isActive) cont.resume(false)
                }
                override fun onConnectionSuspended() {
                    if (cont.isActive) cont.resume(false)
                }
            }

            browser = runCatching { MediaBrowserCompat(context, component, callback, null) }.getOrNull()
            if (browser == null) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            runCatching { browser.connect() }.onFailure {
                if (cont.isActive) cont.resume(false)
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                runCatching { browser?.disconnect() }
            }
        }
    }
}
