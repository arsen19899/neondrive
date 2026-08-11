package com.neondrive.launcher.nav

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Одна точка маршрута. Свой тип, чтобы слой данных не зависел от osmdroid. */
data class RoutePoint(val lat: Double, val lon: Double)

/**
 * Один манёвр маршрута.
 *
 * [distanceM] — длина участка ДО этого манёвра, как её отдаёт OSRM: то есть
 * сколько ехать по текущей дороге, прежде чем поворачивать.
 */
data class RouteStep(
    val instruction: String,
    val streetName: String,
    val maneuverLat: Double,
    val maneuverLon: Double,
    val distanceM: Double,
    val durationSec: Double,
    /** Тип манёвра OSRM — нужен, чтобы выбрать иконку стрелки. */
    val type: String,
    val modifier: String
)

/** Один вариант маршрута до той же точки. */
data class RouteOption(
    val points: List<RoutePoint> = emptyList(),
    val steps: List<RouteStep> = emptyList(),
    /**
     * Ограничение скорости на каждом отрезке геометрии, км/ч; null — в OSM не
     * проставлено. Длина на единицу меньше [points]: значение относится к отрезку
     * между соседними точками.
     */
    val maxspeeds: List<Int?> = emptyList(),
    val distanceM: Double = 0.0,
    val durationSec: Double = 0.0
) {
    val label: String get() = "${formatDistance(distanceM)} · ${formatDuration(durationSec)}"
}

data class RouteState(
    /** Все варианты, которые вернул роутер. Первый — рекомендованный. */
    val options: List<RouteOption> = emptyList(),
    /** Какой вариант выбран водителем. */
    val selected: Int = 0,
    val destLat: Double = Double.NaN,
    val destLon: Double = Double.NaN,
    val destTitle: String = "",
    val loading: Boolean = false,
    val error: String? = null
) {
    // Свойства выбранного варианта вынесены наверх: остальному коду не должно быть
    // дела до того, что вариантов может быть несколько.
    private val current: RouteOption? get() = options.getOrNull(selected)
    val points: List<RoutePoint> get() = current?.points.orEmpty()
    val steps: List<RouteStep> get() = current?.steps.orEmpty()
    val maxspeeds: List<Int?> get() = current?.maxspeeds.orEmpty()
    val distanceM: Double get() = current?.distanceM ?: 0.0
    val durationSec: Double get() = current?.durationSec ?: 0.0

    val hasRoute: Boolean get() = points.size >= 2
    val hasAlternatives: Boolean get() = options.size > 1
    val hasDestination: Boolean get() = !destLat.isNaN() && !destLon.isNaN()
}

/** «12,4 км» / «850 м» */
fun formatDistance(meters: Double): String = when {
    meters >= 10_000 -> "${(meters / 1000).toInt()} км"
    meters >= 1000 -> "%.1f км".format(meters / 1000)
    meters >= 100 -> "${(meters / 10).toInt() * 10} м"
    else -> "${meters.toInt().coerceAtLeast(0)} м"
}

/** «1 ч 20 мин» / «14 мин» */
fun formatDuration(seconds: Double): String {
    val totalMin = (seconds / 60).toInt().coerceAtLeast(1)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "$h ч $m мин" else "$m мин"
}

/**
 * Маршрут для собственной карты оболочки.
 *
 * Считает открытый маршрутизатор [OSRM](https://project-osrm.org/): геометрия линии
 * плюс список манёвров с координатами, по которым [GuidanceEngine] ведёт водителя.
 * Ключ не нужен, регистрация не нужна.
 *
 * ## Чего здесь принципиально нет
 *
 * **Пробок.** OSRM считает по статическому графу дорог и о заторах не знает.
 * Маршрут будет корректным, но не «самым быстрым прямо сейчас», и время в пути —
 * оценка по разрешённым скоростям, а не по реальной обстановке. Это главное
 * отличие от Яндекс.Навигатора, и обходного пути в бесплатном стеке нет.
 *
 * Камеры и ограничения скорости берутся отдельно — см. [HazardHub].
 *
 * ## Про сервер
 *
 * `router.project-osrm.org` — демонстрационный сервер проекта, открытый и без
 * ключа, но предназначенный для разработки и лёгкого использования. Для личного
 * ГУ этого достаточно; при массовой раздаче сборки поднимите свой инстанс OSRM и
 * поменяйте [OSRM_BASE] — больше нигде адрес не встречается.
 */
object RouteHub {

    const val OSRM_BASE = "https://router.project-osrm.org"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Предпочитать локальный граф сетевому роутеру. Задаётся из настроек. */
    var preferOffline = true

    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state

    /**
     * Построить маршрут и, если [startGuidance], сразу начать вести по нему.
     *
     * [context] нужен только для голосового ведения; без него маршрут просто
     * ляжет на карту линией.
     */
    fun buildTo(
        context: Context?,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        title: String = "",
        startGuidance: Boolean = true
    ) {
        job?.cancel()
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            destLat = toLat,
            destLon = toLon,
            destTitle = title.ifBlank { _state.value.destTitle }
        )
        job = scope.launch {
            // Сначала офлайн, если граф на устройстве есть и настройка включена:
            // в машине сеть пропадает чаще, чем хотелось бы, и ждать таймаута
            // сетевого запроса, имея локальный граф, — бессмысленная задержка.
            // Если офлайн-роутер не готов или не нашёл маршрут, идём в сеть.
            val offline = if (preferOffline && context != null) {
                runCatching {
                    OfflineRouter.route(context, fromLat, fromLon, toLat, toLon)
                }.getOrNull()
            } else null

            val result = if (offline != null) {
                RouteState(options = listOf(offline), destLat = toLat, destLon = toLon)
            } else {
                runCatching { request(fromLat, fromLon, toLat, toLon) }.getOrNull()
            }
            if (result == null || result.points.size < 2) {
                _state.value = RouteState(
                    destLat = toLat,
                    destLon = toLon,
                    destTitle = title,
                    error = "Не удалось построить маршрут. Проверьте интернет."
                )
                return@launch
            }
            _state.value = result.copy(loading = false, destTitle = title.ifBlank { "" })
            if (context != null) HazardHub.loadForRoute(context, result.points)
            if (startGuidance && context != null) {
                GuidanceEngine.start(context)
            }
        }
    }

    /** Пересчёт маршрута до той же точки — при сходе с трассы. */
    fun reroute(context: Context?, fromLat: Double, fromLon: Double) {
        val s = _state.value
        if (!s.hasDestination) return
        buildTo(context, fromLat, fromLon, s.destLat, s.destLon, s.destTitle, startGuidance = false)
    }

    /**
     * Переключиться на другой вариант маршрута. Ведение продолжается по новому:
     * камеры перезапрашиваются, счётчик манёвров начинается заново.
     */
    fun selectOption(context: Context?, index: Int) {
        val s = _state.value
        if (index !in s.options.indices || index == s.selected) return
        _state.value = s.copy(selected = index)
        if (context != null) {
            HazardHub.loadForRoute(context, _state.value.points)
            GuidanceEngine.restart()
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        GuidanceEngine.stop()
        HazardHub.clear()
        _state.value = RouteState()
    }

    /* ─────────────────  ЗАПРОС  ───────────────── */

    private suspend fun request(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): RouteState? = withContext(Dispatchers.IO) {
        // OSRM ждёт координаты в порядке «долгота,широта» — самая частая ошибка
        // при работе с этим API, поэтому порядок зафиксирован здесь явно.
        // annotations=maxspeed — ограничения приходят тем же ответом, без второго
        // запроса. alternatives=true даёт до трёх вариантов маршрута: выбор между
        // «быстрее» и «короче» на трассе экономит больше, чем любая оптимизация UI.
        val url = "$OSRM_BASE/route/v1/driving/" +
            "$fromLon,$fromLat;$toLon,$toLat" +
            "?overview=full&geometries=polyline&steps=true" +
            "&alternatives=true&annotations=maxspeed"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "NeonDrive/1.0 (Android head-unit launcher)")
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("code") != "Ok") return@withContext null
            val routes = json.optJSONArray("routes") ?: return@withContext null
            val options = ArrayList<RouteOption>(routes.length())
            for (i in 0 until routes.length()) {
                val route = routes.optJSONObject(i) ?: continue
                val encoded = route.optString("geometry")
                if (encoded.isBlank()) continue
                options += RouteOption(
                    points = decodePolyline(encoded),
                    steps = parseSteps(route),
                    maxspeeds = parseMaxspeeds(route),
                    distanceM = route.optDouble("distance", 0.0),
                    durationSec = route.optDouble("duration", 0.0)
                )
            }
            if (options.isEmpty()) return@withContext null

            RouteState(
                options = options,
                destLat = toLat,
                destLon = toLon
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Ограничения скорости из `annotation.maxspeed`.
     *
     * OSRM отдаёт на каждый отрезок один из трёх вариантов: конкретную скорость,
     * `unknown` (в OSM не размечено) или `none` (ограничения нет вовсе — немецкий
     * автобан). Первый превращаем в число, остальные — в null: показывать знак
     * там, где данных нет, нельзя, водитель поверит и получит штраф.
     *
     * Если сервер вообще не поддерживает эту аннотацию, список останется пустым и
     * знак ограничения просто не появится.
     */
    private fun parseMaxspeeds(route: JSONObject): List<Int?> {
        val out = ArrayList<Int?>(256)
        val legs = route.optJSONArray("legs") ?: return out
        for (l in 0 until legs.length()) {
            val arr = legs.optJSONObject(l)
                ?.optJSONObject("annotation")
                ?.optJSONArray("maxspeed") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null || o.optBoolean("unknown") || o.optBoolean("none")) {
                    out += null
                    continue
                }
                val speed = o.optDouble("speed", Double.NaN)
                if (speed.isNaN()) {
                    out += null
                    continue
                }
                val kmh = when (o.optString("unit")) {
                    "mph" -> speed * 1.609344
                    else -> speed
                }
                out += kmh.toInt().takeIf { it in 5..200 }
            }
        }
        return out
    }

    private fun parseSteps(route: JSONObject): List<RouteStep> {
        val out = ArrayList<RouteStep>(32)
        val legs = route.optJSONArray("legs") ?: return out
        for (l in 0 until legs.length()) {
            val steps = legs.optJSONObject(l)?.optJSONArray("steps") ?: continue
            for (s in 0 until steps.length()) {
                val step = steps.optJSONObject(s) ?: continue
                val man = step.optJSONObject("maneuver") ?: continue
                val loc = man.optJSONArray("location") ?: continue
                if (loc.length() < 2) continue

                val type = man.optString("type")
                val modifier = man.optString("modifier")
                val exit = man.optInt("exit", 0)
                val name = step.optString("name").trim()

                out += RouteStep(
                    instruction = ManeuverText.build(type, modifier, exit, name),
                    streetName = name,
                    // OSRM отдаёт [долгота, широта] — снова обратный порядок.
                    maneuverLon = loc.optDouble(0),
                    maneuverLat = loc.optDouble(1),
                    distanceM = step.optDouble("distance", 0.0),
                    durationSec = step.optDouble("duration", 0.0),
                    type = type,
                    modifier = modifier
                )
            }
        }
        return out
    }

    /**
     * Разбор encoded polyline (алгоритм Google, точность 5 знаков) — в этом формате
     * OSRM отдаёт геометрию по умолчанию. Формат компактный: маршрут через полгорода
     * это несколько килобайт текста вместо мегабайта JSON-координат, что для ГУ на
     * мобильном интернете заметно.
     */
    private fun decodePolyline(encoded: String): List<RoutePoint> {
        val out = ArrayList<RoutePoint>(256)
        var index = 0
        var lat = 0
        var lon = 0

        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                if (index >= encoded.length) return out
                b = encoded[index++].code - 63
                result = result or ((b and 0x1F) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                if (index >= encoded.length) return out
                b = encoded[index++].code - 63
                result = result or ((b and 0x1F) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            out.add(RoutePoint(lat / 1e5, lon / 1e5))
        }
        return out
    }
}

/**
 * Перевод манёвров OSRM в человеческие русские фразы.
 *
 * OSRM описывает манёвр парой «тип + уточнение» на английском (`turn` + `slight
 * right`), плюс номер съезда для круговых. Готового русского текста он не отдаёт —
 * его собирают на клиенте, и это нормальная практика: так фразы можно писать под
 * озвучку, а не под экран.
 */
private object ManeuverText {

    fun build(type: String, modifier: String, exit: Int, street: String): String {
        val base = when (type) {
            "depart" -> "Начинайте движение"
            "arrive" -> return "Вы приехали"
            "turn" -> turn(modifier)
            "new name", "continue" -> "Продолжайте движение"
            "merge" -> "Перестройтесь" + side(modifier)
            "on ramp" -> "Съезд на дорогу" + side(modifier)
            "off ramp" -> "Съезжайте" + side(modifier)
            "fork" -> "Держитесь" + keepSide(modifier)
            "end of road" -> "В конце дороги" + directionOf(modifier)
            "roundabout", "rotary" ->
                if (exit > 0) "На круге $exit-й съезд" else "Двигайтесь по кругу"
            "roundabout turn" -> "На круге" + directionOf(modifier)
            "exit roundabout", "exit rotary" -> "Съезжайте с круга"
            "notification" -> "Продолжайте движение"
            else -> turn(modifier)
        }
        // Название улицы добавляем только там, где оно звучит естественно.
        val withStreet = if (
            street.isNotBlank() &&
            type in setOf("turn", "new name", "continue", "end of road", "fork", "merge")
        ) "$base на $street" else base
        return withStreet
    }

    private fun turn(modifier: String): String = when (modifier) {
        "left" -> "Поверните налево"
        "right" -> "Поверните направо"
        "slight left" -> "Плавно налево"
        "slight right" -> "Плавно направо"
        "sharp left" -> "Резко налево"
        "sharp right" -> "Резко направо"
        "uturn" -> "Развернитесь"
        "straight" -> "Продолжайте прямо"
        else -> "Продолжайте движение"
    }

    private fun directionOf(modifier: String): String = when (modifier) {
        "left", "slight left", "sharp left" -> " налево"
        "right", "slight right", "sharp right" -> " направо"
        "uturn" -> " разворот"
        else -> " прямо"
    }

    private fun keepSide(modifier: String): String = when (modifier) {
        "left", "slight left", "sharp left" -> " левее"
        "right", "slight right", "sharp right" -> " правее"
        else -> "сь прямо"
    }

    private fun side(modifier: String): String = when (modifier) {
        "left", "slight left", "sharp left" -> " левее"
        "right", "slight right", "sharp right" -> " правее"
        else -> ""
    }
}
