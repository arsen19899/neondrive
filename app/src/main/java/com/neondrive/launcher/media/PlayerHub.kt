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
import com.neondrive.launcher.MainActivity
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.RadioMode
import com.neondrive.launcher.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private lateinit var settingsRepo: SettingsRepository

    private val _stationSearch = MutableStateFlow<List<RadioBrowserApi.Result>>(emptyList())
    val stationSearch: StateFlow<List<RadioBrowserApi.Result>> = _stationSearch

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private val _source = MutableStateFlow(MusicSource.DEVICE)
    val source: StateFlow<MusicSource> = _source

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    private val _stations = MutableStateFlow(RadioPresets.default)
    val stations: StateFlow<List<RadioStation>> = _stations

    private val _fmStations = MutableStateFlow<List<FmStation>>(emptyList())
    val fmStations: StateFlow<List<FmStation>> = _fmStations

    private val _radioMode = MutableStateFlow(RadioMode.INTERNET)
    val radioMode: StateFlow<RadioMode> = _radioMode

    private var currentFmFreq: Int? = null

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
        settingsRepo = SettingsRepository(appContext)
        FmRadioController.init(appContext)

        // Станции интернет-радио, сохранённые поиском, FM-станции и выбранный режим
        // радио подтягиваем из настроек один раз при старте.
        scope.launch {
            val s = runCatching { settingsRepo.settings.first() }.getOrNull() ?: return@launch
            if (s.customStations.isNotEmpty()) _stations.value = RadioPresets.default + s.customStations
            _fmStations.value = s.fmStations
            _radioMode.value = s.radioMode
        }

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
            when {
                _source.value == MusicSource.YANDEX -> {
                    external.refresh()
                    // Пока идёт подключение, в _now лежит служебное сообщение — не затираем
                    if (!_connectingYandex.value && external.available.value) {
                        _now.value = external.now.value
                    }
                }
                // FM: опрашивать нечего — тюнер это не MediaSession, _now выставлен
                // напрямую в playFmStation и не должен затираться контроллером.
                _source.value == MusicSource.RADIO && _radioMode.value == RadioMode.FM -> Unit
                else -> pushNow()
            }
        }
    }

    private fun pushNow() {
        if (_source.value == MusicSource.YANDEX) return
        if (_source.value == MusicSource.RADIO && _radioMode.value == RadioMode.FM) return
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
        if (_stations.value.any { it.id == station.id }) return
        _stations.value = _stations.value + station
        persistCustomStations()
    }

    fun removeStation(id: String) {
        _stations.value = _stations.value.filterNot { it.id == id && !it.builtIn }
        persistCustomStations()
    }

    private fun persistCustomStations() {
        val custom = _stations.value.filterNot { it.builtIn }
        scope.launch { runCatching { settingsRepo.setCustomStations(custom) } }
    }

    /* ─────────────────  FM-РАДИО  ───────────────── */

    fun addFmStation(station: FmStation) {
        if (_fmStations.value.any { it.frequencyKHz == station.frequencyKHz }) return
        _fmStations.value = (_fmStations.value + station).sortedBy { it.frequencyKHz }
        persistFmStations()
    }

    fun removeFmStation(frequencyKHz: Int) {
        _fmStations.value = _fmStations.value.filterNot { it.frequencyKHz == frequencyKHz }
        persistFmStations()
    }

    private fun persistFmStations() {
        scope.launch { runCatching { settingsRepo.setFmStations(_fmStations.value) } }
    }

    fun setRadioMode(mode: RadioMode) {
        if (_radioMode.value == mode) return
        _radioMode.value = mode
        scope.launch { runCatching { settingsRepo.setRadioMode(mode) } }
    }

    /**
     * Открыть заводское радио-приложение ГУ, если оно есть — единственное реальное
     * управление тюнером, доступное стороннему приложению без системных прав
     * (см. подробности в [FmRadioController]). Возвращает false, если такого
     * приложения на устройстве не нашлось.
     */
    fun openFactoryRadioApp(): Boolean = FmRadioController.openFactoryApp(appContext)

    /**
     * Выбрать FM-станцию как «текущую» справочно — настроиться на неё физически
     * нужно самой магнитолой (кнопками ГУ или заводским радио-приложением, см.
     * [openFactoryRadioApp]). Программно передать частоту тюнеру сторонний apk
     * не может — это ограничение системы разрешений Android, а не оболочки.
     */
    fun playFmStation(station: FmStation) {
        setRadioMode(RadioMode.FM)
        _source.value = MusicSource.RADIO
        currentFmFreq = station.frequencyKHz
        _now.value = NowPlaying(
            title = station.label,
            subtitle = "%.1f МГц · настройтесь на магнитоле".format(station.mhz),
            isPlaying = false,
            sourceLabel = "FM радио"
        )
    }

    private fun nextFmStation() {
        val list = _fmStations.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.frequencyKHz == currentFmFreq }
        playFmStation(list[(i + 1).mod(list.size)])
    }

    private fun prevFmStation() {
        val list = _fmStations.value
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.frequencyKHz == currentFmFreq }
        playFmStation(list[(if (i < 0) 0 else i - 1).mod(list.size)])
    }

    /** Поиск станций в публичном каталоге radio-browser.info (см. [RadioBrowserApi]). */
    fun searchStations(query: String) {
        if (query.isBlank()) {
            _stationSearch.value = emptyList()
            return
        }
        scope.launch {
            _searching.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    runCatching { RadioBrowserApi.search(query) }.getOrDefault(emptyList())
                }
                _stationSearch.value = results
            } finally {
                _searching.value = false
            }
        }
    }

    fun clearStationSearch() {
        _stationSearch.value = emptyList()
    }

    fun isStationSaved(streamUrl: String): Boolean =
        _stations.value.any { it.streamUrl == streamUrl }

    /** Сохранить найденную станцию в список и сразу включить её. */
    fun saveAndPlayStation(result: RadioBrowserApi.Result) {
        val station = RadioStation(
            id = "custom_" + result.streamUrl.hashCode(),
            name = result.name,
            streamUrl = result.streamUrl,
            genre = result.genre,
            builtIn = false
        )
        addStation(station)
        playStation(station)
    }

    fun saveStation(result: RadioBrowserApi.Result) {
        addStation(
            RadioStation(
                id = "custom_" + result.streamUrl.hashCode(),
                name = result.name,
                streamUrl = result.streamUrl,
                genre = result.genre,
                builtIn = false
            )
        )
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
        setRadioMode(RadioMode.INTERNET)
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
                    // Разбудить приложение тихо (через MediaBrowserService) обычно
                    // получается — тогда экран пользователь вообще не видел, и
                    // возвращать фокус не нужно, оболочка и так всё время была видна.
                    // Если же пришлось честно открыть Activity — забираем фокус
                    // обратно немедленно, чтобы вспышка чужого интерфейса была
                    // как можно короче.
                    if (launchApp && external.lastConnectWasVisible) returnToLauncher()
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

    /** Вернуть фокус оболочке поверх только что открывшегося стороннего плеера. */
    private fun returnToLauncher() {
        runCatching {
            appContext.startActivity(
                Intent(appContext, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        // Без анимации — вместе с FLAG_ACTIVITY_NO_ANIMATION на запуске
                        // стороннего приложения (ExternalSessionBridge.launchApp) это
                        // минимизирует видимую вспышку чужого интерфейса.
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            )
        }
    }

    /** Лайк текущего трека в стороннем приложении. */
    fun toggleLike() {
        if (_source.value == MusicSource.YANDEX) external.toggleLike()
    }

    /* ─────────────────  ТРАНСПОРТ  ───────────────── */

    private val isFm: Boolean get() = _source.value == MusicSource.RADIO && _radioMode.value == RadioMode.FM

    fun playPause() = when {
        _source.value == MusicSource.YANDEX -> external.toggle()
        isFm -> if (_now.value.isPlaying) pause() else play()
        else -> controller?.let { if (it.isPlaying) it.pause() else resumeOrStart() } ?: Unit
    }

    // Для FM это чисто отметка «слушаю»/«не слушаю» в интерфейсе — реальным
    // воспроизведением сторонний apk управлять не может (см. FmRadioController).
    fun play() = when {
        _source.value == MusicSource.YANDEX -> external.play()
        isFm -> _now.value = _now.value.copy(isPlaying = true)
        else -> resumeOrStart()
    }

    fun pause() = when {
        _source.value == MusicSource.YANDEX -> external.pause()
        isFm -> _now.value = _now.value.copy(isPlaying = false)
        else -> controller?.pause() ?: Unit
    }

    fun next() = when {
        _source.value == MusicSource.YANDEX -> external.next()
        isFm -> nextFmStation()
        _source.value == MusicSource.RADIO -> nextStation()
        else -> controller?.seekToNextMediaItem() ?: Unit
    }

    fun prev() = when {
        _source.value == MusicSource.YANDEX -> external.prev()
        isFm -> prevFmStation()
        _source.value == MusicSource.RADIO -> prevStation()
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
