package com.neondrive.launcher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.neondrive.launcher.automation.NeonNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Кнопка, которую отдаёт само приложение через свою медиасессию. */
data class SessionAction(
    val id: String,
    val name: String,
    val isLike: Boolean,
    val isDislike: Boolean
)

/**
 * Мост к чужим плеерам — в первую очередь к Яндекс.Музыке.
 *
 * Своим ExoPlayer'ом её не воспроизвести (DRM и закрытый API), поэтому оболочка
 * работает как полноценный пульт: читает MediaSession приложения, показывает трек
 * с обложкой, шлёт транспортные команды и лайк.
 *
 * Лайк ищется двумя путями, потому что приложения реализуют его по-разному:
 *  • стандартный рейтинг сессии (ACTION_SET_RATING) — тогда шлём Rating;
 *  • собственное действие сессии (customActions) — тогда шлём его по идентификатору.
 *
 * Для доступа к чужим сессиям нужно разрешение «Доступ к уведомлениям» — то же,
 * которым пользуется реакция на уведомления телефона.
 */
class ExternalSessionBridge(private val context: Context) {

    companion object {
        const val PKG_YANDEX_MUSIC = "ru.yandex.music"
        val CONTROLLABLE = listOf(
            PKG_YANDEX_MUSIC,
            "com.spotify.music",
            "deezer.android.app",
            "com.zvuk.app",
            "ru.mts.music.android"
        )

        private val LIKE_HINTS = listOf("like", "лайк", "heart", "favorite", "fav", "нрав")
        private val DISLIKE_HINTS = listOf("dislike", "unlike", "дизлайк", "не нрав")
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

    /** Дополнительные кнопки, которые отдаёт само приложение. */
    private val _actions = MutableStateFlow<List<SessionAction>>(emptyList())
    val actions: StateFlow<List<SessionAction>> = _actions

    /** Текущее состояние лайка: true — нравится, false — нет, null — неизвестно. */
    private val _liked = MutableStateFlow<Boolean?>(null)
    val liked: StateFlow<Boolean?> = _liked

    /** Поддерживает ли сессия лайк хоть каким-то способом. */
    private val _canLike = MutableStateFlow(false)
    val canLike: StateFlow<Boolean> = _canLike

    /** Выдано ли разрешение на доступ к чужим медиасессиям. */
    fun hasAccess(): Boolean = NeonNotificationListener.isEnabled(context) &&
        runCatching { manager?.getActiveSessions(listenerComponent) != null }.getOrDefault(false)

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

    fun stop() = detach()

    fun refresh() {
        val list = runCatching { manager?.getActiveSessions(listenerComponent) }.getOrNull()
        bind(list)
    }

    /* ─────────────────  ПОДКЛЮЧЕНИЕ  ───────────────── */

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
            override fun onMetadataChanged(metadata: MediaMetadata?) = push(target)
            override fun onSessionDestroyed() {
                detach()
                _available.value = false
            }
        }.also { target.registerCallback(it, handler) }
        _available.value = true
        push(target)
    }

    private fun detach() {
        callback?.let { cb -> runCatching { active?.unregisterCallback(cb) } }
        callback = null
        active = null
        _actions.value = emptyList()
        _canLike.value = false
        _liked.value = null
    }

    private fun push(c: MediaController) {
        val md = c.metadata
        val st = c.playbackState

        _now.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Яндекс.Музыка",
            subtitle = listOfNotNull(
                md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                md?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ).filter { it.isNotBlank() }.joinToString(" · "),
            artUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?.let { Uri.parse(it) }
                ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)?.let { Uri.parse(it) },
            isPlaying = st?.state == PlaybackState.STATE_PLAYING,
            positionMs = st?.position ?: 0L,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            sourceLabel = when (c.packageName) {
                PKG_YANDEX_MUSIC -> "Яндекс.Музыка"
                else -> c.packageName
            }
        )

        // Кнопки самого приложения
        _actions.value = st?.customActions.orEmpty().map { a ->
            val id = a.action.lowercase()
            val name = a.name?.toString().orEmpty()
            val hay = "$id ${name.lowercase()}"
            SessionAction(
                id = a.action,
                name = name.ifBlank { a.action },
                isLike = LIKE_HINTS.any { hay.contains(it) } &&
                    DISLIKE_HINTS.none { hay.contains(it) },
                isDislike = DISLIKE_HINTS.any { hay.contains(it) }
            )
        }

        val supportsRating = (st?.actions ?: 0L) and PlaybackState.ACTION_SET_RATING != 0L
        _canLike.value = supportsRating || _actions.value.any { it.isLike }

        // Текущее состояние лайка
        val rating = md?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
        _liked.value = when {
            rating == null || !rating.isRated -> null
            rating.ratingStyle == Rating.RATING_HEART -> rating.hasHeart()
            rating.ratingStyle == Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
            else -> null
        }
    }

    /* ─────────────────  ТРАНСПОРТ  ───────────────── */

    fun play() {
        runCatching { active?.transportControls?.play() }
    }

    fun pause() {
        runCatching { active?.transportControls?.pause() }
    }

    fun next() {
        runCatching { active?.transportControls?.skipToNext() }
    }

    fun prev() {
        runCatching { active?.transportControls?.skipToPrevious() }
    }

    fun seekTo(ms: Long) {
        runCatching { active?.transportControls?.seekTo(ms) }
    }

    fun toggle() {
        if (_now.value.isPlaying) pause() else play()
    }

    /** Отправить произвольное действие сессии. */
    fun sendAction(id: String) {
        runCatching { active?.transportControls?.sendCustomAction(id, null) }
    }

    /**
     * Лайк. Сначала пробуем собственное действие приложения — оно точнее,
     * потом стандартный рейтинг сессии.
     */
    fun toggleLike() {
        val c = active ?: return
        val wantLike = _liked.value != true

        val custom = _actions.value.firstOrNull { if (wantLike) it.isLike else it.isDislike }
            ?: _actions.value.firstOrNull { it.isLike }
        if (custom != null) {
            sendAction(custom.id)
            _liked.value = wantLike
            return
        }

        val style = c.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.ratingStyle
            ?: c.ratingType
        val rating = when (style) {
            Rating.RATING_THUMB_UP_DOWN -> Rating.newThumbRating(wantLike)
            else -> Rating.newHeartRating(wantLike)
        }
        runCatching { c.transportControls.setRating(rating) }
        _liked.value = wantLike
    }

    /* ─────────────────  ЗАПУСК ПРИЛОЖЕНИЯ  ───────────────── */

    fun isRunning(pkg: String = PKG_YANDEX_MUSIC): Boolean =
        runCatching { manager?.getActiveSessions(listenerComponent) }
            .getOrNull()?.any { it.packageName == pkg } == true

    fun launchApp(pkg: String = PKG_YANDEX_MUSIC): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching { context.startActivity(i) }.isSuccess
    }

    /**
     * Поднять приложение и дождаться, пока оно создаст медиасессию.
     *
     * Без этого ожидания команда play уходит в пустоту: приложение ещё грузится,
     * контроллера нет, и оболочка выглядит сломанной. Возвращает true, если сессия
     * появилась и ей можно управлять.
     */
    suspend fun connect(pkg: String = PKG_YANDEX_MUSIC, timeoutMs: Long = 12_000): Boolean {
        if (!hasAccess()) return false

        refresh()
        if (active?.packageName == pkg) return true

        if (!isRunning(pkg)) launchApp(pkg)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            refresh()
            if (active != null) return true
        }
        return active != null
    }
}
