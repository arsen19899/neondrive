package com.neondrive.launcher.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.neondrive.launcher.automation.SpeedProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class GuidanceState(
    val active: Boolean = false,
    /** Текст следующего манёвра: «Поверните направо на Ленина». */
    val instruction: String = "",
    /** Тип и уточнение манёвра — для стрелки на карточке. */
    val maneuverType: String = "",
    val maneuverModifier: String = "",
    /** Сколько осталось до манёвра, м. */
    val distanceToManeuverM: Double = 0.0,
    /** Что будет после следующего манёвра — второй строкой на карточке. */
    val thenInstruction: String = "",
    /** Остаток всего маршрута. */
    val remainingM: Double = 0.0,
    val remainingSec: Double = 0.0,
    /** Полосы на подходе к манёвру; пусто — разметки в OSM нет. */
    val lanes: List<RouteLane> = emptyList(),
    val offRoute: Boolean = false,
    val rerouting: Boolean = false,
    val arrived: Boolean = false
) {
    val distanceLabel: String get() = formatDistance(distanceToManeuverM)
    val remainingLabel: String get() = formatDistance(remainingM)
    val etaLabel: String get() = formatDuration(remainingSec)

    /**
     * Время прибытия часами: «14:32».
     *
     * За рулём это полезнее, чем «через 47 минут» — с часами на приборной панели
     * сравнивается мгновенно, а «через сколько» приходится складывать в уме.
     * Показываем оба, но время прибытия крупнее.
     */
    val arrivalLabel: String
        get() {
            if (remainingSec <= 0) return ""
            val at = System.currentTimeMillis() + (remainingSec * 1000).toLong()
            return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(at))
        }
}

/**
 * Ведение по маршруту: следующий манёвр, остаток пути, голос и перестроение.
 *
 * ## Как это работает
 *
 * Раз в фикс GPS позиция проецируется на линию маршрута ([GeoMath.nearestOnRoute]).
 * От проекции считается остаток пути и расстояние до ближайшего впереди манёвра;
 * когда манёвр пройден — берётся следующий. Если проекция ушла от линии дальше
 * порога и держится там несколько фиксов подряд, маршрут перестраивается от
 * текущей точки.
 *
 * ## Почему пороги именно такие
 *
 * Сход с маршрута — 60 м и три фикса подряд. Одного фикса мало: GPS на ГУ
 * регулярно «прыгает» на десятки метров под мостом, в тоннеле или между домами,
 * и перестроение на каждый такой выброс превратило бы ведение в кашу. Три фикса
 * при обновлении дважды в секунду — это примерно полторы секунды, за которые
 * машина на 90 км/ч проезжает 37 м: достаточно быстро, чтобы не увести далеко,
 * и достаточно медленно, чтобы отсеять шум.
 *
 * ## Голос
 *
 * Системный [TextToSpeech]. Фразы произносятся на трёх рубежах перед манёвром —
 * далеко, близко и в момент — и каждая ровно один раз на манёвр: повторяющееся
 * «через 200 метров направо» на каждом фиксе GPS сводит с ума. Русский голос
 * есть не на каждой прошивке ГУ; если движок его не даёт, ведение продолжается
 * молча, карточка манёвра на экране остаётся.
 */
object GuidanceEngine {

    /** Дальше этого от линии маршрута — считаем, что съехали. */
    private const val OFF_ROUTE_M = 60.0
    private const val OFF_ROUTE_FIXES = 3

    /** Манёвр считается пройденным, когда до него меньше этого. */
    private const val MANEUVER_PASSED_M = 25.0

    /** Приехали. */
    private const val ARRIVED_M = 40.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(GuidanceState())
    val state: StateFlow<GuidanceState> = _state

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceEnabled = true
    private var voiceVolume = 0.9f
    private var duckMusic = true

    private var audioManager: AudioManager? = null
    private var focusRequest: Any? = null   // AudioFocusRequest на API 26+
    private var focusHeld = false

    private var stepIndex = 0
    private var segmentHint = 0
    private var offRouteCount = 0
    /** Рубежи, уже озвученные для текущего манёвра. */
    private var spokenFar = false
    private var spokenNear = false
    private var spokenNow = false

    fun setVoiceEnabled(on: Boolean) {
        voiceEnabled = on
        if (!on) {
            runCatching { tts?.stop() }
            abandonFocus()
        }
    }

    fun isVoiceEnabled(): Boolean = voiceEnabled

    /** Громкость подсказок, 0..100 % — независимо от громкости музыки. */
    fun setVoiceVolume(percent: Int) {
        voiceVolume = (percent.coerceIn(0, 100) / 100f)
    }

    /** Приглушать ли музыку на время подсказки. */
    fun setDuckMusic(on: Boolean) {
        duckMusic = on
    }

    private var lastContext: Context? = null

    fun start(context: Context) {
        val app = context.applicationContext
        lastContext = app
        initTts(app)
        job?.cancel()
        stepIndex = 0
        segmentHint = 0
        offRouteCount = 0
        resetSpoken()
        _state.value = GuidanceState(active = true)

        job = scope.launch {
            SpeedProvider.state.collect { gps ->
                if (!gps.hasFix) return@collect
                onFix(app, gps.lastLat, gps.lastLon, gps.speedKmh)
            }
        }
    }

    /**
     * Озвучить произвольную фразу тем же голосом и с тем же приглушением музыки.
     * Нужна [HazardHub] для предупреждений о камерах и превышении — заводить ради
     * них второй синтезатор и второй запрос аудиофокуса было бы расточительно.
     */
    fun speakExternal(text: String) = speak(text)

    /** Перезапустить ведение по текущему маршруту — например, после смены варианта. */
    fun restart() {
        val ctx = lastContext ?: return
        start(ctx)
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { tts?.stop() }
        abandonFocus()
        _state.value = GuidanceState()
    }

    /** Освободить движок синтеза — при полном выключении оболочки. */
    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    /* ─────────────────  ОСНОВНОЙ ЦИКЛ  ───────────────── */

    private fun onFix(context: Context, lat: Double, lon: Double, speedKmh: Float) {
        val route = RouteHub.state.value
        if (!route.hasRoute) return

        val nearest = GeoMath.nearestOnRoute(route.points, lat, lon, segmentHint)
        segmentHint = nearest.segmentIndex

        // Камеры и ограничение скорости считаются на том же фиксе GPS —
        // отдельный сборщик ради них заводить незачем.
        HazardHub.onFix(lat = lat, lon = lon, speedKmh = speedKmh)

        // Приехали?
        if (route.hasDestination) {
            val toDest = GeoMath.distanceM(lat, lon, route.destLat, route.destLon)
            if (toDest <= ARRIVED_M) {
                speak("Вы приехали", force = true)
                _state.value = _state.value.copy(
                    active = false,
                    arrived = true,
                    instruction = "Вы приехали",
                    distanceToManeuverM = 0.0,
                    remainingM = 0.0,
                    remainingSec = 0.0
                )
                job?.cancel()
                job = null
                return
            }
        }

        // Сход с маршрута
        if (nearest.distanceM > OFF_ROUTE_M) {
            offRouteCount++
            if (offRouteCount >= OFF_ROUTE_FIXES && !_state.value.rerouting) {
                _state.value = _state.value.copy(offRoute = true, rerouting = true)
                speak("Перестраиваю маршрут", force = true)
                RouteHub.reroute(context, lat, lon)
                // После перестроения начинаем с начала нового трека.
                stepIndex = 0
                segmentHint = 0
                offRouteCount = 0
                resetSpoken()
                scope.launch {
                    // Небольшая пауза, чтобы не долбить роутер, пока ответ в пути.
                    kotlinx.coroutines.delay(4000)
                    _state.value = _state.value.copy(rerouting = false, offRoute = false)
                }
            }
            return
        }
        offRouteCount = 0

        // Ближайший манёвр впереди
        val steps = route.steps
        if (steps.isEmpty()) {
            _state.value = _state.value.copy(
                active = true,
                remainingM = GeoMath.remainingAlong(route.points, nearest)
            )
            return
        }

        if (stepIndex >= steps.size) stepIndex = steps.size - 1
        var step = steps[stepIndex]
        var toManeuver = GeoMath.distanceM(lat, lon, step.maneuverLat, step.maneuverLon)

        // Манёвр пройден — переходим к следующему
        while (toManeuver < MANEUVER_PASSED_M && stepIndex < steps.size - 1) {
            stepIndex++
            resetSpoken()
            step = steps[stepIndex]
            toManeuver = GeoMath.distanceM(lat, lon, step.maneuverLat, step.maneuverLon)
        }

        val remaining = GeoMath.remainingAlong(route.points, nearest)
        // Оценка оставшегося времени по средней скорости маршрута: точнее, чем
        // делить на текущую скорость (на светофоре она ноль и ETA уходит в
        // бесконечность), и честнее, чем показывать исходную оценку до конца.
        val avgSpeedMs = if (route.distanceM > 0 && route.durationSec > 0)
            route.distanceM / route.durationSec else 11.0
        val remainingSec = if (avgSpeedMs > 0.1) remaining / avgSpeedMs else 0.0

        _state.value = GuidanceState(
            active = true,
            instruction = step.instruction,
            maneuverType = step.type,
            maneuverModifier = step.modifier,
            distanceToManeuverM = toManeuver,
            thenInstruction = steps.getOrNull(stepIndex + 1)?.instruction.orEmpty(),
            lanes = step.lanes,
            remainingM = remaining,
            remainingSec = remainingSec,
            offRoute = false,
            rerouting = false,
            arrived = false
        )

        announce(step, toManeuver, speedKmh)
    }

    /**
     * Рубежи озвучки подстраиваются под скорость: на трассе о повороте надо знать
     * заранее, во дворе фраза за километр бесполезна. Считаем от времени до
     * манёвра, а не от расстояния.
     */
    private fun announce(step: RouteStep, toManeuver: Double, speedKmh: Float) {
        val speedMs = (speedKmh / 3.6f).coerceAtLeast(5f)
        val farM = (speedMs * 30).coerceIn(300f, 1500f)   // ~30 секунд до манёвра
        val nearM = (speedMs * 10).coerceIn(100f, 400f)   // ~10 секунд

        when {
            !spokenFar && toManeuver in nearM.toDouble()..farM.toDouble() -> {
                spokenFar = true
                speak("Через ${GeoMath.roundForSpeech(toManeuver)} метров ${lower(step.instruction)}")
            }
            !spokenNear && toManeuver <= nearM && toManeuver > 60 -> {
                spokenNear = true
                spokenFar = true
                speak("Через ${GeoMath.roundForSpeech(toManeuver)} метров ${lower(step.instruction)}")
            }
            !spokenNow && toManeuver <= 60 -> {
                spokenNow = true
                spokenNear = true
                spokenFar = true
                speak(step.instruction)
            }
        }
    }

    private fun lower(s: String): String =
        if (s.isEmpty()) s else s[0].lowercaseChar() + s.substring(1)

    private fun resetSpoken() {
        spokenFar = false
        spokenNear = false
        spokenNow = false
    }

    /* ─────────────────  ГОЛОС  ───────────────── */

    private fun initTts(context: Context) {
        if (tts != null) return
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            tts = TextToSpeech(context, TextToSpeech.OnInitListener { status ->
                onTtsInit(status)
            })
        }
    }

    /**
     * Готов ли движок синтеза и годится ли он для русской озвучки.
     *
     * Вынесено из лямбды-колбэка отдельным методом: там нужны ранние выходы, а
     * `return@` из SAM-лямбды, переданной в конструктор, читается плохо и зависит
     * от того, как компилятор назовёт метку.
     */
    private fun onTtsInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return

        // Русский голос есть не на каждой прошивке ГУ. Если его нет — не
        // подсовываем английский движок русскому тексту, это звучит как набор
        // букв; просто оставляем ведение молчаливым.
        val res = runCatching { tts?.setLanguage(Locale("ru", "RU")) }.getOrNull()
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsReady = false
            return
        }

        // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE — не косметика: по этому признаку
        // система понимает, что звук является навигационной подсказкой, и
        // обрабатывает его иначе, чем музыку. На части прошивок и в
        // Bluetooth-гарнитурах это включает правильную маршрутизацию звука,
        // а музыке даёт корректно пригаснуть.
        runCatching {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }

        // Фокус нужно не только взять, но и вовремя отпустить, иначе музыка
        // останется приглушённой навсегда после первой же фразы.
        runCatching {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Ничего: фокус уже взят перед вызовом speak.
                }

                override fun onDone(utteranceId: String?) {
                    abandonFocus()
                }

                @Deprecated("Абстрактный метод базового класса, помечен устаревшим в Android")
                override fun onError(utteranceId: String?) {
                    abandonFocus()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    abandonFocus()
                }
            })
        }
    }

    private fun speak(text: String, force: Boolean = false) {
        if (!voiceEnabled || !ttsReady || text.isBlank()) return
        requestFocus()
        runCatching {
            val params = Bundle().apply {
                // Громкость подсказки задаётся здесь, а не системным ползунком:
                // на магнитоле пользователь крутит громкость музыки, и подсказка
                // должна иметь свою, независимую — иначе на тихой музыке её не
                // слышно, а на громкой она орёт.
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, voiceVolume)
            }
            tts?.speak(
                text,
                if (force) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                params,
                UTTERANCE_ID
            )
        }.onFailure { abandonFocus() }
    }

    /* ─────────────────  АУДИОФОКУС  ───────────────── */

    /**
     * Просим систему приглушить музыку на время подсказки.
     *
     * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — именно то, что нужно навигации: чужой
     * плеер не останавливается, а временно убавляет громкость и сам возвращает её
     * обратно, когда фокус отпущен. Полная пауза (`TRANSIENT`) для короткой фразы
     * «через 300 метров направо» была бы грубой: на радио это означало бы обрыв
     * эфира, а на своём плеере — щелчок и потерю пары секунд трека.
     *
     * Раньше фокус не запрашивался вовсе, и подсказка звучала поверх музыки на
     * полной громкости — в реальной поездке разобрать её было невозможно.
     */
    private fun requestFocus() {
        if (!duckMusic || focusHeld) return
        val am = audioManager ?: return
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        }.getOrDefault(false)
        focusHeld = ok
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (focusRequest as? AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
        focusRequest = null
    }

    private const val UTTERANCE_ID = "neon_nav"
}
