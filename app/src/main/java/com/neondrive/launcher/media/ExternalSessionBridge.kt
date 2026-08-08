package com.neondrive.launcher.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.neondrive.launcher.automation.NeonNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Мост к чужим плеерам — в первую очередь к Яндекс.Музыке.
 *
 * Своим ExoPlayer'ом её не воспроизвести (DRM + закрытый API), поэтому оболочка
 * работает как пульт: читает MediaSession приложения и шлёт транспортные команды.
 * Для доступа к сессиям нужно разрешение «Доступ к уведомлениям» — то же самое,
 * которым пользуется реакция на уведомления телефона.
 */
class ExternalSessionBridge(private val context: Context) {

    companion object {
        const val PKG_YANDEX_MUSIC = "ru.yandex.music"
        val CONTROLLABLE = listOf(PKG_YANDEX_MUSIC, "com.spotify.music", "deezer.android.app")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val manager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val listenerComponent = ComponentName(context, NeonNotificationListener::class.java)

    private var active: MediaController? = null
    private var callback: MediaController.Callback? = null

    private val _now = MutableStateFlow(NowPlaying(sourceLabel = "Яндекс.Музыка"))
    val now: StateFlow<NowPlaying> = _now

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available

    /** Есть ли у нас доступ к активным сессиям (выдано ли разрешение). */
    fun hasAccess(): Boolean = runCatching {
        manager?.getActiveSessions(listenerComponent) != null
    }.getOrDefault(false)

    fun start() {
        runCatching {
            manager?.addOnActiveSessionsChangedListener(
                { controllers -> bind(controllers) },
                listenerComponent,
                handler
            )
        }
        refresh()
    }

    fun stop() {
        detach()
    }

    fun refresh() {
        val list = runCatching { manager?.getActiveSessions(listenerComponent) }.getOrNull()
        bind(list)
    }

    private fun bind(controllers: List<MediaController>?) {
        val target = controllers?.firstOrNull { it.packageName in CONTROLLABLE }
        if (target == null) {
            detach()
            _available.value = false
            return
        }
        if (target.sessionToken == active?.sessionToken) {
            push(target)
            return
        }
        detach()
        active = target
        callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = push(target)
            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = push(target)
            override fun onSessionDestroyed() {
                detach(); _available.value = false
            }
        }.also { target.registerCallback(it, handler) }
        _available.value = true
        push(target)
    }

    private fun detach() {
        callback?.let { cb -> runCatching { active?.unregisterCallback(cb) } }
        callback = null
        active = null
    }

    private fun push(c: MediaController) {
        val md = c.metadata
        val st = c.playbackState
        _now.value = NowPlaying(
            title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                ?: "Яндекс.Музыка",
            subtitle = listOfNotNull(
                md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM)
            ).filter { it.isNotBlank() }.joinToString(" · "),
            artUri = md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?.let { Uri.parse(it) },
            isPlaying = st?.state == PlaybackState.STATE_PLAYING,
            positionMs = st?.position ?: 0L,
            durationMs = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            sourceLabel = when (c.packageName) {
                PKG_YANDEX_MUSIC -> "Яндекс.Музыка"
                else -> c.packageName
            }
        )
    }

    /* ─────────  Транспорт  ───────── */

    fun play() = runCatching { active?.transportControls?.play() }.let { }
    fun pause() = runCatching { active?.transportControls?.pause() }.let { }
    fun next() = runCatching { active?.transportControls?.skipToNext() }.let { }
    fun prev() = runCatching { active?.transportControls?.skipToPrevious() }.let { }
    fun seekTo(ms: Long) = runCatching { active?.transportControls?.seekTo(ms) }.let { }
    fun toggle() {
        if (_now.value.isPlaying) pause() else play()
    }

    fun isYandexRunning(): Boolean =
        runCatching { manager?.getActiveSessions(listenerComponent) }
            .getOrNull()?.any { it.packageName == PKG_YANDEX_MUSIC } == true
}
