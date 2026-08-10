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
import kotlinx.coroutines.withTimeoutOrNull

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

        private val LIKE_HINTS = listOf(
            "like", "лайк", "heart", "favorite", "fav", "нрав",
            "избранное", "избран", "thumbup", "thumb_up"
        )
        private val DISLIKE_HINTS = listOf(
            "dislike", "unlike", "дизлайк", "не нрав", "thumbdown", "thumb_down"
        )
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

    /**
     * Только что нажатый лайк живёт здесь короткое время как «источник правды».
     *
     * Раньше `push()` на каждое обновление сессии (а сессия дёргается каждые 500 мс
     * опросом) тут же перезаписывал [_liked] значением из метаданных приложения.
     * Если приложение не отражает наш toggle в своём рейтинге сразу — а Яндекс.Музыка
     * не всегда это делает, особенно если наш способ послать лайк ей не подошёл, —
     * кнопка визуально «отменяла» нажатие в течение доли секунды. Выглядело так,
     * будто лайк вообще не срабатывает.
     */
    private var likeOverrideValue: Boolean? = null
    private var likeOverrideUntil = 0L

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
        val fromSession: Boolean? = when {
            rating == null || !rating.isRated -> null
            rating.ratingStyle == Rating.RATING_HEART -> rating.hasHeart()
            rating.ratingStyle == Rating.RATING_THUMB_UP_DOWN -> rating.isThumbUp
            else -> null
        }
        _liked.value = if (System.currentTimeMillis() < likeOverrideUntil) {
            likeOverrideValue
        } else {
            fromSession
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
     * Лайк. Раньше пробовали только один способ — который найдётся первым — и на
     * этом останавливались. Проблема в том, что наша эвристика для кастомных кнопок
     * ищет лайк по ключевым словам в id/названии действия, а приложение может
     * называть его как угодно; угадали не всегда. Теперь пробуем оба честных
     * способа сразу — стандартный рейтинг сессии и найденную по эвристике кнопку —
     * чтобы сработал хотя бы один, если он в принципе поддерживается приложением.
     */
    fun toggleLike() {
        val c = active ?: return
        val wantLike = _liked.value != true
        var sentAnything = false

        // Стандартный рейтинг сессии — только если приложение реально его поддерживает
        // (RATING_NONE = «не поддерживает», слать в этом случае бессмысленно).
        val style = c.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.ratingStyle
            ?.takeIf { it != Rating.RATING_NONE }
            ?: c.ratingType.takeIf { it != Rating.RATING_NONE }
        if (style != null) {
            val rating = when (style) {
                Rating.RATING_THUMB_UP_DOWN -> Rating.newThumbRating(wantLike)
                Rating.RATING_HEART -> Rating.newHeartRating(wantLike)
                else -> null
            }
            if (rating != null && runCatching { c.transportControls.setRating(rating) }.isSuccess) {
                sentAnything = true
            }
        }

        // Кастомная кнопка приложения (иконка сердца/пальца в уведомлении)
        val custom = _actions.value.firstOrNull { if (wantLike) it.isLike else it.isDislike }
            ?: _actions.value.firstOrNull { it.isLike }
        if (custom != null) {
            sendAction(custom.id)
            sentAnything = true
        }

        if (!sentAnything) return

        // Optimistic UI: показываем нажатие сразу, не дожидаясь, пока приложение
        // отразит его в своей сессии (а некоторые не отражают вовсе).
        likeOverrideValue = wantLike
        likeOverrideUntil = System.currentTimeMillis() + 2500
        _liked.value = wantLike
    }

    /* ─────────────────  ЗАПУСК ПРИЛОЖЕНИЯ  ───────────────── */

    fun isRunning(pkg: String = PKG_YANDEX_MUSIC): Boolean =
        runCatching { manager?.getActiveSessions(listenerComponent) }
            .getOrNull()?.any { it.packageName == pkg } == true

    /**
     * Показывался ли экран приложения при последнем [connect]. false — разбудили
     * тихо через MediaBrowserService, экран пользователь не видел. UI ориентируется
     * на этот флаг, чтобы решить, нужно ли забирать фокус обратно себе.
     */
    var lastConnectWasVisible: Boolean = false
        private set

    fun launchApp(pkg: String = PKG_YANDEX_MUSIC): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Гасим системную анимацию перехода — вместе с мгновенным возвратом
            // фокуса оболочке (см. PlayerHub.returnToLauncher) это сводит видимую
            // «вспышку» чужого интерфейса к минимуму.
            ?.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            ?: return false
        return runCatching { context.startActivity(i) }.isSuccess
    }

    /** Сколько ждать тихую попытку [SilentAppLauncher.wakeAndPlay], прежде чем считать её неудавшейся. */
    private val SILENT_ATTEMPT_TIMEOUT_MS = 7000L

    /**
     * Поднять приложение и дождаться, пока оно создаст медиасессию — незаметно
     * для пользователя, если это вообще возможно, но с гарантией, что музыка
     * заиграет так или иначе.
     *
     * Каскад надёжности в два звена:
     *  1. Тихая попытка через [SilentAppLauncher.wakeAndPlay] — она не только
     *     поднимает процесс через MediaBrowserService, но и сама шлёт команды play,
     *     так что автовоспроизведение не зависит от того, создаст ли приложение
     *     сессию само по себе. Ограничена по времени снаружи ([withTimeoutOrNull]),
     *     чтобы зависший внутри неё опрос не мог заблокировать запуск музыки вовсе.
     *  2. Если тихая попытка не подтвердила воспроизведение (нет сервиса, не
     *     подключились, ни один из способов play не сработал) — без раздумий
     *     открываем Activity напрямую. Это гарантированно работающий путь: он не
     *     требует поддержки MediaBrowserService и не зависит от эвристик поиска
     *     плеера в медиатеке.
     *
     * Так «должна открываться в фоне и сразу работать» выполняется в подавляющем
     * большинстве случаев тихим путём, а на случай, если конкретная версия
     * приложения тихий путь не поддерживает, есть безусловный резерв — вместо
     * того чтобы просто не заиграть.
     */
    suspend fun connect(pkg: String = PKG_YANDEX_MUSIC, timeoutMs: Long = 12_000): Boolean {
        if (!hasAccess()) return false

        refresh()
        if (active?.packageName == pkg) {
            lastConnectWasVisible = false
            return true
        }

        if (!isRunning(pkg)) {
            val silentlyPlaying = runCatching {
                withTimeoutOrNull(SILENT_ATTEMPT_TIMEOUT_MS) { SilentAppLauncher.wakeAndPlay(context, pkg) }
            }.getOrNull() == true

            if (silentlyPlaying) {
                lastConnectWasVisible = false
            } else {
                lastConnectWasVisible = true
                launchApp(pkg)
            }
        } else {
            lastConnectWasVisible = false
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(400)
            refresh()
            if (active != null) return true
        }
        return active != null
    }
}
