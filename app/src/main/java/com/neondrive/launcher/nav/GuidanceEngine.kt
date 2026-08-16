package com.neondrive.launcher.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.neondrive.launcher.automation.GpsState
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
    val arrived: Boolean = false,
    /**
     * Позиция машины, «прилипшая» к линии маршрута, и направление участка под ней.
     *
     * Сырой фикс GPS на ГУ дрожит на десятки метров даже стоя на месте, и метка
     * машины прыгает по соседним точкам. Пока привязка к маршруту убедительна,
     * показывать честнее не сырую точку, а её проекцию на дорогу, по которой мы
     * едем: она движется вдоль линии и не скачет поперёк неё.
     */
    val snapped: Boolean = false,
    val snapLat: Double = Double.NaN,
    val snapLon: Double = Double.NaN,
    val courseDeg: Float = 0f,
    /**
     * Где машина находится на треке: индекс отрезка и доля внутри него. По этой
     * паре карта отрезает уже пройденный кусок линии маршрута.
     */
    val passedIndex: Int = -1,
    val passedT: Double = 0.0
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
 * Раз в фикс GPS позиция проецируется на линию маршрута ([GeoMath.nearestOnRoute])
 * и переводится в одно число — сколько метров пройдено ВДОЛЬ трека. На этой же
 * шкале заранее размечены все манёвры, поэтому «какой манёвр следующий» и
 * «сколько до него» — это вычитание, а не поиск: текущим считается первый манёвр,
 * который мы ещё не обогнали по треку.
 *
 * Так сделано не из любви к экономии. Пока расстояние до манёвра мерилось по
 * прямой, поперечная ошибка GPS решала, засчитается поворот или нет: при сносе
 * больше порога машина не «доезжала» до точки манёвра никогда, счётчик манёвров
 * залипал на давно пройденном шаге, и оболочка продолжала уверенно советовать
 * ехать прямо там, где нужно было поворачивать. Обогнать же манёвр вдоль
 * маршрута, не выполнив его, невозможно.
 *
 * Если проекция ушла от линии дальше порога и держится там несколько фиксов
 * подряд, маршрут перестраивается от текущей точки.
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

    /**
     * Манёвр считается пройденным, когда мы обогнали его ВДОЛЬ маршрута на
     * столько метров.
     *
     * Раньше здесь стояло 25 м по прямой от машины до точки манёвра — и это была
     * причина «навигатор потерялся». Прямая не знает про дорогу: если улица
     * проходит в двадцати метрах от перекрёстка, на котором нужно повернуть
     * (а с ошибкой GPS в городе это обычное дело), поворот засчитывался
     * пройденным ещё на подъезде к нему. Карточка переключалась на следующий шаг
     * — как правило, «продолжайте движение N метров» — и водитель проезжал
     * поворот мимо, ничего не заподозрив. Ровно тот случай из теста.
     *
     * Теперь и позиция, и точки манёвров меряются пройденным расстоянием по
     * самому треку. Обогнать манёвр вдоль маршрута, не выполнив его,
     * невозможно, а поперечная ошибка GPS на это число уже не влияет.
     */
    private const val MANEUVER_PASSED_M = 12.0

    /** Приехали. */
    private const val ARRIVED_M = 40.0

    /**
     * Фиксы хуже этой точности не участвуют в решении «съехали с маршрута».
     * Под мостом и в тоннеле ГУ отдаёт точку с точностью в сотню метров — по
     * такой точке перестраивать маршрут нельзя.
     */
    private const val TRUST_ACCURACY_M = 40f

    /** Пока привязка к треку не хуже — метка машины показывается на линии. */
    private const val SNAP_M = 30.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(GuidanceState())
    val state: StateFlow<GuidanceState> = _state

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /**
     * Ответ ассистента, сказанный до того, как поднялся движок синтеза.
     *
     * Синтез инициализируется асинхронно и на слабом ГУ занимает заметное время.
     * Первая же голосовая команда после старта оболочки приходится ровно на это
     * окно, и без очереди на одну фразу ответ на неё пропадал бы молча — самый
     * неприятный вид сбоя для голосового управления: человек не понимает,
     * услышали его или нет. Фраза одна, а не список: устаревшие ответы
     * произносить нельзя, важен только последний.
     */
    private var pendingAssistant: String? = null
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

    /**
     * Предпосчитанная геометрия текущего маршрута.
     *
     * Ведение опирается на расстояние ВДОЛЬ трека, а не по прямой, — значит нужны
     * накопленные длины и положение каждого манёвра на этой шкале. Считать их на
     * каждый фикс GPS нельзя: маршрут через город — это тысячи точек. Считаем
     * один раз на маршрут и держим здесь, привязав к самому объекту списка точек:
     * пришёл новый маршрут (перестроение, другой вариант) — кэш пересобирается сам.
     */
    private class RouteGeometry(val points: List<RoutePoint>, steps: List<RouteStep>) {
        val cum: DoubleArray = GeoMath.cumulative(points)
        val totalM: Double = cum.lastOrNull() ?: 0.0

        /** Положение каждого манёвра на шкале «метров от начала маршрута». */
        val stepAlong: DoubleArray = DoubleArray(steps.size).also { out ->
            var hint = 0
            for (j in steps.indices) {
                val s = steps[j]
                val n = GeoMath.nearestOnRoute(points, s.maneuverLat, s.maneuverLon, hint)
                hint = n.segmentIndex
                // Манёвры на треке идут по порядку. Если проекция выдала шаг
                // назад (петля, развязка, две близкие точки), выправляем: иначе
                // «до манёвра» ушло бы в минус и шаг пропустился бы.
                out[j] = maxOf(GeoMath.alongOf(cum, n), if (j > 0) out[j - 1] else 0.0)
            }
            // Последний манёвр — прибытие: он ровно в конце трека.
            if (out.isNotEmpty()) out[out.size - 1] = maxOf(out[out.size - 1], totalM)
        }
    }

    private var geometry: RouteGeometry? = null

    private fun geometryFor(route: RouteState): RouteGeometry? {
        if (!route.hasRoute) return null
        val cached = geometry
        // Сравнение по ссылке намеренно: RouteHub отдаёт тот же список точек,
        // пока маршрут не сменился, а поэлементное сравнение тысяч координат на
        // каждый фикс — ровно та работа, которой мы избегаем.
        if (cached != null && cached.points === route.points) return cached
        val built = RouteGeometry(route.points, route.steps)
        geometry = built
        // Новый маршрут — счётчики манёвров начинаются заново.
        stepIndex = 0
        segmentHint = 0
        resetSpoken()
        return built
    }

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
        geometry = null
        resetSpoken()
        _state.value = GuidanceState(active = true)

        job = scope.launch {
            SpeedProvider.state.collect { gps ->
                if (!gps.hasFix) return@collect
                onFix(app, gps)
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
        geometry = null
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

    private fun onFix(context: Context, gps: GpsState) {
        val lat = gps.lastLat
        val lon = gps.lastLon
        val speedKmh = gps.speedKmh

        val route = RouteHub.state.value

        // Маршрута нет, а ведение включено — значит его сейчас пересчитывают
        // (или пересчёт не удался). Молчать в этот момент нельзя: именно так
        // выглядит «навигатор потерялся» — карточка застывает на старой
        // подсказке и продолжает уверенно врать. Честно говорим, что происходит.
        if (!route.hasRoute) {
            geometry = null
            _state.value = _state.value.copy(
                // Текст статуса рисует сама карточка по флагам ниже — здесь
                // подсказки нет, и оставлять старую было бы враньём.
                instruction = "",
                thenInstruction = "",
                distanceToManeuverM = 0.0,
                lanes = emptyList(),
                rerouting = route.loading,
                offRoute = !route.loading,
                snapped = false,
                passedIndex = -1
            )
            return
        }

        val geo = geometryFor(route) ?: return

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
                // Маршрут после прибытия больше не нужен, но убирать его мгновенно
                // нельзя: водитель должен успеть увидеть «вы приехали». Полминуты —
                // достаточно, чтобы прочитать, и мало, чтобы забыть сбросить самому.
                scope.launch {
                    kotlinx.coroutines.delay(30_000)
                    if (_state.value.arrived) RouteHub.clear()
                }
                return
            }
        }

        // Сход с маршрута.
        //
        // Точность фикса здесь решает: под мостом, в тоннеле и в дворовых
        // «колодцах» ГУ отдаёт точку с разбросом в сотню метров. Перестраивать
        // маршрут по такой точке — верный способ увести водителя в сторону,
        // поэтому недостоверные фиксы просто не голосуют за сход.
        val trusted = gps.accuracyM <= 0f || gps.accuracyM <= TRUST_ACCURACY_M
        if (nearest.distanceM > OFF_ROUTE_M) {
            // Заведомо мусорный фикс не меняет вообще ничего: карточка остаётся
            // в том виде, в каком была на последней достоверной точке.
            if (!trusted) return
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

        // Сколько проехали по треку — единая шкала и для остатка пути, и для
        // расстояния до манёвра.
        val progress = GeoMath.alongOf(geo.cum, nearest)
        val remaining = (geo.totalM - progress).coerceAtLeast(0.0)

        // Привязка машины к линии и направление дороги под ней — для карты.
        val snap = nearest.distanceM <= SNAP_M
        val snapPoint = if (snap) GeoMath.pointAt(route.points, nearest) else null
        val course = if (snap) GeoMath.bearingAt(route.points, nearest).toFloat() else gps.bearingDeg

        // Ближайший манёвр впереди
        val steps = route.steps
        if (steps.isEmpty()) {
            _state.value = _state.value.copy(
                active = true,
                remainingM = remaining,
                snapped = snap,
                snapLat = snapPoint?.lat ?: Double.NaN,
                snapLon = snapPoint?.lon ?: Double.NaN,
                courseDeg = course,
                passedIndex = nearest.segmentIndex,
                passedT = nearest.t
            )
            return
        }

        /*
         * Какой манёвр показывать.
         *
         * Первый по треку манёвр, который мы ещё не обогнали. Ключевое слово —
         * «по треку»: индекс не хранится между фиксами и не может ни залипнуть,
         * ни перескочить. Прошлая версия вела счётчик вручную, двигая его по
         * прямому расстоянию до точки манёвра, и после единственной ошибки —
         * будь то выброс GPS или поворот, до которого дорога подходит слишком
         * близко, — этот счётчик уже никогда не возвращался на место.
         *
         * Здесь же после перестроения, промаха или отката назад позиция
         * пересчитывается сама: манёвр всегда соответствует тому месту трека,
         * где машина находится прямо сейчас.
         */
        val idx = steps.indices.firstOrNull {
            geo.stepAlong.getOrElse(it) { Double.MAX_VALUE } > progress + MANEUVER_PASSED_M
        } ?: (steps.size - 1)

        if (idx != stepIndex) {
            stepIndex = idx
            resetSpoken()
        }
        val step = steps[stepIndex]
        val toManeuver =
            (geo.stepAlong.getOrElse(stepIndex) { progress } - progress).coerceAtLeast(0.0)
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
            arrived = false,
            snapped = snap,
            snapLat = snapPoint?.lat ?: Double.NaN,
            snapLon = snapPoint?.lon ?: Double.NaN,
            courseDeg = course,
            passedIndex = nearest.segmentIndex,
            passedT = nearest.t
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
                    // Ничего: фокус уже взят перед вызовом speak, а признак
                    // «оболочка говорит» выставлен там же — раньше, чем движок
                    // доберётся до этого колбэка.
                }

                override fun onDone(utteranceId: String?) {
                    markSpeechFinished()
                    abandonFocus()
                }

                @Deprecated("Абстрактный метод базового класса, помечен устаревшим в Android")
                override fun onError(utteranceId: String?) {
                    markSpeechFinished()
                    abandonFocus()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    markSpeechFinished()
                    abandonFocus()
                }
            })
        }

        // Ответ ассистента, прозвучавший до готовности синтеза, договариваем
        // теперь — см. [pendingAssistant].
        pendingAssistant?.let {
            pendingAssistant = null
            emit(it, force = true)
        }
    }

    private fun speak(text: String, force: Boolean = false) {
        if (!voiceEnabled) return
        emit(text, force)
    }

    /**
     * Произнести ответ голосового ассистента «Елисей».
     *
     * Отдельный вход в синтез, а не [speakExternal], по двум причинам.
     *
     * Первая: тумблер «голосовые подсказки» относится к ведению по маршруту.
     * Человек, выключивший объявление поворотов, не просил ассистента молчать в
     * ответ на прямой вопрос — у голосового управления свой тумблер, и смешивать
     * их значило бы делать одну настройку молча зависимой от другой.
     *
     * Вторая: ответ ассистента всегда вытесняет очередь ([TextToSpeech.QUEUE_FLUSH]).
     * Подсказки маршрута выстраиваются в очередь осознанно, но ответ на только
     * что заданный вопрос, произнесённый после двух накопившихся «через триста
     * метров направо», приходит слишком поздно, чтобы быть ответом.
     *
     * [context] нужен, потому что ассистентом можно пользоваться, не начиная
     * никакой поездки: синтез в этом случае ещё не поднимался.
     */
    fun speakAssistant(context: Context, text: String) {
        if (text.isBlank()) return
        initTts(context)
        if (ttsReady) emit(text, force = true) else pendingAssistant = text
    }

    /* ─────────────────  «СЕЙЧАС ГОВОРИМ»  ───────────────── */

    /**
     * Когда началась текущая фраза и когда закончилась последняя.
     *
     * Нужно не синтезу, а голосовому управлению. Микрофон ГУ стоит в одном
     * салоне с динамиками, и при открытом ожидании ключевого слова
     * распознаватель отлично слышит собственный голос оболочки. Грамматика
     * ожидания короткая — шесть вариантов имени плюс «[unk]», — и чистая
     * громкая речь из динамика ложится на неё охотнее любого другого звука.
     * Получался замкнутый круг: сказали «Слушаю» — услышали в этом «Елисей» —
     * снова проснулись — снова сказали «Слушаю». Снаружи это ровно то, на что
     * жалуются: помощник просыпается сам.
     */
    @Volatile
    private var speakingUntil = 0L

    @Volatile
    private var speechEndedAt = 0L

    /**
     * Оценка длительности фразы сверху, по числу букв.
     *
     * Нужна не для точности, а как страховка: сигнал «договорил» приходит от
     * движка синтеза (`onDone`), и на части прошивок ГУ он не приходит вовсе.
     * Опираться на один лишь колбэк нельзя — при его потере голосовое
     * управление осталось бы глухим до конца поездки. Оценка ошибается в
     * большую сторону на доли секунды, и это ровно та ошибка, которая не мешает.
     */
    private fun estimateSpeechMs(text: String): Long =
        (700L + 85L * text.length).coerceAtMost(15_000L)

    /**
     * Говорит ли оболочка прямо сейчас — или говорила меньше [graceMs] назад.
     *
     * Запас после окончания обязателен: динамик доигрывает хвост фразы, а
     * микрофон отдаёт звук с задержкой в сотню-другую миллисекунд. Без запаса
     * последнее слово собственной фразы всё равно попадало бы в распознаватель.
     */
    fun isSpeaking(graceMs: Long = 0L): Boolean {
        val now = System.currentTimeMillis()
        if (now < speakingUntil) return true
        return graceMs > 0L && now - speechEndedAt < graceMs
    }

    private fun markSpeechFinished() {
        speakingUntil = 0L
        speechEndedAt = System.currentTimeMillis()
    }

    private fun emit(text: String, force: Boolean = false) {
        if (!ttsReady || text.isBlank()) return
        requestFocus()
        // Отмечаем «говорим» ДО вызова speak, а не в onStart: между вызовом и
        // реальным началом синтеза проходит заметное время, и ожидание
        // ключевого слова не должно успеть поймать начало собственной фразы.
        // Очередь (QUEUE_ADD) сама себя продлевает — каждая следующая фраза
        // сдвигает срок вперёд.
        val estimatedEnd = System.currentTimeMillis() + estimateSpeechMs(text)
        if (estimatedEnd > speakingUntil) speakingUntil = estimatedEnd
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
        }.onFailure { markSpeechFinished(); abandonFocus() }
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
