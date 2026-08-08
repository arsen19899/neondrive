package com.neondrive.launcher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.neondrive.launcher.data.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Единая «ручка» для всей музыки в оболочке.
 * Внутри — три источника: файлы с устройства, интернет-радио и Яндекс.Музыка.
 * UI и автоматика работают только с этим объектом.
 */
object PlayerHub {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    lateinit var external: ExternalSessionBridge
        private set

    private val _source = MutableStateFlow(MusicSource.DEVICE)
    val source: StateFlow<MusicSource> = _source

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    private val _stations = MutableStateFlow(RadioPresets.default)
    val stations: StateFlow<List<RadioStation>> = _stations

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val audio: AudioManager get() =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /* ─────────────────  ЖИЗНЕННЫЙ ЦИКЛ  ───────────────── */

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        external = ExternalSessionBridge(appContext)

        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()
            controller?.addListener(playerListener)
            _ready.value = controller != null
            pushNow()
        }, ContextCompat.getMainExecutor(appContext))

        external.start()
        scope.launch {
            external.now.collect {
                if (_source.value == MusicSource.YANDEX &&
                    !_connectingYandex.value &&
                    external.available.value
                ) {
                    _now.value = it
                }
            }
        }
        scope.launch { tick() }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pushNow()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = pushNow()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = pushNow()
        override fun onPlaybackStateChanged(playbackState: Int) = pushNow()
    }

    private suspend fun tick() {
        while (true) {
            delay(500)
            if (_source.value == MusicSource.YANDEX) {
                external.refresh()
                // Пока идёт подключение, в _now лежит служебное сообщение — не затираем
                if (!_connectingYandex.value && external.available.value) {
                    _now.value = external.now.value
                }
            } else {
                pushNow()
            }
        }
    }

    private fun pushNow() {
        if (_source.value == MusicSource.YANDEX) return
        val c = controller ?: return
        val md = c.mediaMetadata
        _now.value = NowPlaying(
            title = md.title?.toString()
                ?: if (_source.value == MusicSource.RADIO) "Радио" else "Нет воспроизведения",
            subtitle = listOfNotNull(md.artist?.toString(), md.albumTitle?.toString())
                .filter { it.isNotBlank() }.joinToString(" · "),
            artUri = md.artworkUri,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0L,
            sourceLabel = _source.value.label
        )
    }

    /* ─────────────────  БИБЛИОТЕКА  ───────────────── */

    suspend fun refreshLibrary() {
        val list = MediaLibrary.scan(appContext)
        withContext(Dispatchers.Main) { _tracks.value = list }
    }

    fun addStation(station: RadioStation) {
        _stations.value = _stations.value + station
    }

    fun removeStation(id: String) {
        _stations.value = _stations.value.filterNot { it.id == id && !it.builtIn }
    }

    /* ─────────────────  ВОСПРОИЗВЕДЕНИЕ  ───────────────── */

    fun playTracks(list: List<Track>, startIndex: Int = 0) {
        val c = controller ?: return
        _source.value = MusicSource.DEVICE
        val items = list.map { t ->
            MediaItem.Builder()
                .setUri(t.uri)
                .setMediaId(t.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setArtworkUri(t.albumArtUri)
                        .build()
                )
                .build()
        }
        c.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), 0L)
        c.prepare()
        c.play()
        pushNow()
    }

    fun playStation(station: RadioStation) {
        val c = controller ?: return
        _source.value = MusicSource.RADIO
        c.setMediaItem(
            MediaItem.Builder()
                .setUri(station.streamUrl)
                .setMediaId(station.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.genre.ifBlank { "Интернет-радио" })
                        .build()
                )
                .build()
        )
        c.prepare()
        c.play()
        pushNow()
    }

    /** Идёт ли сейчас подключение к Яндекс.Музыке — UI показывает это состояние. */
    private val _connectingYandex = MutableStateFlow(false)
    val connectingYandex: StateFlow<Boolean> = _connectingYandex

    /**
     * Переключиться на Яндекс.Музыку.
     *
     * Приложение может быть ещё не запущено, поэтому поднимаем его и терпеливо ждём
     * появления медиасессии — иначе команда play уходит в пустоту и выглядит так,
     * будто плеер сломан.
     */
    fun switchToYandex(launchApp: Boolean = true, autoPlay: Boolean = true) {
        _source.value = MusicSource.YANDEX
        _now.value = external.now.value.copy(sourceLabel = "Яндекс.Музыка")

        // Свой плеер уступает место
        runCatching { controller?.pause() }

        scope.launch {
            if (!external.hasAccess()) {
                _now.value = _now.value.copy(
                    title = "Нужен доступ к уведомлениям",
                    subtitle = "Настройки оболочки → Система → Доступ к уведомлениям"
                )
                return@launch
            }
            _connectingYandex.value = true
            try {
                val connected = if (launchApp) external.connect() else {
                    external.refresh(); external.available.value
                }
                if (connected) {
                    if (autoPlay && !external.now.value.isPlaying) {
                        delay(600)
                        external.play()
                    }
                } else {
                    _now.value = _now.value.copy(
                        title = "Яндекс.Музыка не отвечает",
                        subtitle = "Откройте приложение и включите любой трек"
                    )
                }
            } finally {
                _connectingYandex.value = false
            }
        }
    }

    fun openYandexMusic() {
        external.launchApp()
    }

    /** Лайк текущего трека в стороннем приложении. */
    fun toggleLike() {
        if (_source.value == MusicSource.YANDEX) external.toggleLike()
    }

    /* ─────────────────  ТРАНСПОРТ  ───────────────── */

    fun playPause() = when (_source.value) {
        MusicSource.YANDEX -> external.toggle()
        else -> controller?.let { if (it.isPlaying) it.pause() else resumeOrStart() } ?: Unit
    }

    fun play() = when (_source.value) {
        MusicSource.YANDEX -> external.play()
        else -> resumeOrStart()
    }

    fun pause() = when (_source.value) {
        MusicSource.YANDEX -> external.pause()
        else -> controller?.pause() ?: Unit
    }

    fun next() = when (_source.value) {
        MusicSource.YANDEX -> external.next()
        MusicSource.RADIO -> nextStation()
        else -> controller?.seekToNextMediaItem() ?: Unit
    }

    fun prev() = when (_source.value) {
        MusicSource.YANDEX -> external.prev()
        MusicSource.RADIO -> prevStation()
        else -> controller?.seekToPreviousMediaItem() ?: Unit
    }

    fun seekTo(ms: Long) = when (_source.value) {
        MusicSource.YANDEX -> external.seekTo(ms)
        else -> controller?.seekTo(ms) ?: Unit
    }

    private fun resumeOrStart() {
        val c = controller ?: return
        if (c.mediaItemCount > 0) {
            c.prepare(); c.play()
        } else {
            val list = _tracks.value
            if (list.isNotEmpty()) playTracks(list, 0)
        }
    }

    private fun nextStation() {
        val list = _stations.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == controller?.currentMediaItem?.mediaId }
        playStation(list[(i + 1).mod(list.size)])
    }

    private fun prevStation() {
        val list = _stations.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == controller?.currentMediaItem?.mediaId }
        playStation(list[(if (i < 0) 0 else i - 1).mod(list.size)])
    }

    /** Переключение источника по кругу — удобно повесить на кнопку руля. */
    fun cycleSource() {
        when (_source.value) {
            MusicSource.DEVICE -> _stations.value.firstOrNull()?.let { playStation(it) }
            MusicSource.RADIO -> switchToYandex()
            MusicSource.YANDEX -> {
                external.pause()
                _tracks.value.takeIf { it.isNotEmpty() }?.let { playTracks(it, 0) }
                    ?: run { _source.value = MusicSource.DEVICE }
            }
        }
    }

    val isPlaying: Boolean get() = _now.value.isPlaying

    /* ─────────────────  ГРОМКОСТЬ  ───────────────── */

    private var duckedFrom: Int? = null

    /**
     * Громкость считаем в «сырых» ступенях потока, а не в процентах.
     * На головных устройствах шкала часто всего 15 делений: округление процентов
     * туда-обратно приводило бы к дрожанию уровня.
     */
    fun maxVolumeRaw(): Int =
        runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15)
            .coerceAtLeast(1)

    fun volumeRaw(): Int =
        runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)

    fun setVolumeRaw(value: Int) {
        val max = maxVolumeRaw()
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceIn(0, max), 0)
        }
    }

    fun volumePercent(): Int = (volumeRaw() * 100f / maxVolumeRaw()).roundToInt()

    fun setVolumePercent(percent: Int) {
        setVolumeRaw((percent.coerceIn(0, 100) / 100f * maxVolumeRaw()).roundToInt())
    }

    fun nudgeVolume(up: Boolean) {
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    fun toggleMute() {
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI
            )
        }
    }

    /** Приглушить до [toPercent] % от текущего уровня, запомнив исходный. */
    fun duck(toPercent: Int) {
        if (duckedFrom != null) return
        val current = volumeRaw()
        duckedFrom = current
        // Минимум одна ступень вниз, иначе на короткой шкале приглушения не слышно
        val target = (current * toPercent / 100f).roundToInt()
            .coerceAtMost(if (current > 0) current - 1 else 0)
        setVolumeRaw(target)
    }

    fun unduck() {
        duckedFrom?.let { setVolumeRaw(it) }
        duckedFrom = null
    }

    val isDucked: Boolean get() = duckedFrom != null
}
