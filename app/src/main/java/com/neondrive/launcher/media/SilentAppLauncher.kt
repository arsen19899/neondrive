package com.neondrive.launcher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Поднять процесс стороннего музыкального приложения и заставить его реально
 * заиграть, не показывая его экран.
 *
 * Многие приложения (в т.ч. Яндекс.Музыка) поддерживают Android Auto и поэтому
 * объявляют MediaBrowserService — служебный компонент без своего интерфейса,
 * которым Android Auto управляет воспроизведением. bindService-подключение к
 * нему само поднимает процесс и никогда не показывает Activity — но этого
 * подключения одного недостаточно: приложение обычно не создаёт настоящую
 * MediaSession, пока не получит команду играть. Поэтому дальше используется
 * его же MediaControllerCompat с лесенкой из нескольких способов запустить
 * воспроизведение — на случай, если приложение поддерживает не все:
 *
 *  1. playFromSearch("", …) — семантика «просто включи музыку», которой
 *     пользуется голосовой ассистент; на пустом запросе многие приложения
 *     сами подбирают что играть;
 *  2. play() — «продолжить» последнюю очередь/плейлист;
 *  3. просмотр корня медиатеки и playFromMediaId по первому проигрываемому
 *     пункту — последний резерв, если первые два ничего не подняли.
 *
 * Если ни один способ не сработал (или у приложения нет MediaBrowserService,
 * либо оно отказало в подключении), единственный оставшийся путь — реально
 * открыть Activity: это делает [ExternalSessionBridge.connect] сама.
 */
object SilentAppLauncher {

    private const val CONNECT_TIMEOUT_MS = 4000L
    private const val PLAYBACK_POLL_MS = 350L
    private const val PLAYBACK_POLL_ATTEMPTS = 6

    /** Есть ли у пакета MediaBrowserService — тогда можно разбудить его без экрана. */
    private fun findBrowserService(context: Context, pkg: String): ComponentName? {
        val intent = Intent("android.media.browse.MediaBrowserService").setPackage(pkg)
        val info = runCatching {
            context.packageManager.queryIntentServices(intent, 0)
        }.getOrNull()?.firstOrNull()?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    /**
     * Разбудить приложение и постараться реально включить воспроизведение.
     * true — на выходе есть основания полагать, что музыка заиграла (получили
     * PLAYING/BUFFERING от собственного контроллера приложения); дальше сессию
     * подхватит обычный [ExternalSessionBridge] через MediaSessionManager.
     */
    suspend fun wakeAndPlay(context: Context, pkg: String): Boolean {
        val component = findBrowserService(context, pkg) ?: return false
        val browser = connectBrowser(context, component) ?: return false
        return try {
            startPlayback(context, browser)
        } finally {
            runCatching { browser.disconnect() }
        }
    }

    private suspend fun connectBrowser(context: Context, component: ComponentName): MediaBrowserCompat? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var browser: MediaBrowserCompat? = null

            val timeoutRunnable = Runnable {
                if (cont.isActive) cont.resume(null)
            }

            val callback = object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    handler.removeCallbacks(timeoutRunnable)
                    if (cont.isActive) cont.resume(browser)
                }
                override fun onConnectionFailed() {
                    handler.removeCallbacks(timeoutRunnable)
                    if (cont.isActive) cont.resume(null)
                }
                override fun onConnectionSuspended() {
                    if (cont.isActive) cont.resume(null)
                }
            }

            browser = runCatching { MediaBrowserCompat(context, component, callback, null) }.getOrNull()
            if (browser == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            runCatching { browser.connect() }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
            handler.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS)
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                runCatching { browser?.disconnect() }
            }
        }

    private suspend fun startPlayback(context: Context, browser: MediaBrowserCompat): Boolean {
        val token = browser.sessionToken
        val controller = runCatching { MediaControllerCompat(context, token) }.getOrNull() ?: return false

        // Шаг 1: «просто включи музыку» — то же, чем пользуется голосовой ассистент,
        // когда приложение само выбирает, что играть.
        runCatching { controller.transportControls.playFromSearch("", null) }
        if (waitForPlayback(controller)) return true

        // Шаг 2: «продолжить» — поднимает последнюю очередь/плейлист.
        runCatching { controller.transportControls.play() }
        if (waitForPlayback(controller)) return true

        // Шаг 3: явно берём первый проигрываемый пункт корня медиатеки.
        val root = browser.root
        val mediaId = findPlayableMediaId(browser, root)
        if (mediaId != null) {
            runCatching { controller.transportControls.playFromMediaId(mediaId, null) }
            if (waitForPlayback(controller)) return true
        }

        return false
    }

    private suspend fun waitForPlayback(controller: MediaControllerCompat): Boolean {
        repeat(PLAYBACK_POLL_ATTEMPTS) {
            delay(PLAYBACK_POLL_MS)
            val state = runCatching { controller.playbackState?.state }.getOrNull()
            if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
                return true
            }
        }
        return false
    }

    /** Первый проигрываемый пункт корня, либо — если корень это только папки — первый пункт внутри первой из них. */
    private suspend fun findPlayableMediaId(browser: MediaBrowserCompat, parentId: String): String? {
        val rootChildren = loadChildren(browser, parentId) ?: return null
        rootChildren.firstOrNull { it.isPlayable }?.let { return it.mediaId }

        val firstFolder = rootChildren.firstOrNull { it.isBrowsable } ?: return null
        val folderId = firstFolder.mediaId ?: return null
        val nested = loadChildren(browser, folderId) ?: return null
        return nested.firstOrNull { it.isPlayable }?.mediaId
    }

    private suspend fun loadChildren(
        browser: MediaBrowserCompat,
        parentId: String
    ): List<MediaBrowserCompat.MediaItem>? = suspendCancellableCoroutine { cont ->
        val callback = object : MediaBrowserCompat.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                runCatching { browser.unsubscribe(parentId) }
                if (cont.isActive) cont.resume(children)
            }
            override fun onError(parentId: String) {
                runCatching { browser.unsubscribe(parentId) }
                if (cont.isActive) cont.resume(null)
            }
        }
        runCatching { browser.subscribe(parentId, callback) }.onFailure {
            if (cont.isActive) cont.resume(null)
        }
        cont.invokeOnCancellation { runCatching { browser.unsubscribe(parentId) } }
    }
}
