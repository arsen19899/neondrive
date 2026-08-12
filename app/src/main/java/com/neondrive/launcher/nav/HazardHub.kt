package com.neondrive.launcher.nav

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Участок дороги с известным ограничением скорости. */
data class SpeedZone(
    val points: List<RoutePoint>,
    val limitKmh: Int,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

/** Камера на дороге. [limitKmh] — ограничение, которое она контролирует, если известно. */
data class SpeedCamera(
    val lat: Double,
    val lon: Double,
    val limitKmh: Int? = null,
    val source: String = "OSM"
)

data class HazardState(
    /** Ближайшая камера впереди по маршруту. */
    val cameraAheadM: Double? = null,
    val cameraLimitKmh: Int? = null,
    /** Действующее ограничение на текущем участке, км/ч. Null — неизвестно. */
    val speedLimitKmh: Int? = null,
    /** Превышение сверх допуска прямо сейчас. */
    val speeding: Boolean = false,
    val camerasLoaded: Int = 0
)

/**
 * Камеры и ограничения скорости.
 *
 * ## Откуда данные
 *
 * **Камеры — OpenStreetMap.** Тег `highway=speed_camera` в Беларуси размечен
 * неплохо: стационарные «Стрелки» на магистралях и городских улицах в базе есть.
 * Дополнительно берутся `enforcement=maxspeed` и камеры наблюдения за трафиком —
 * разные мапперы размечают одно и то же по-разному. Запрос уходит один раз на
 * маршрут, коридором вдоль всей линии: Overpass умеет `around` по ломаной, так
 * что не нужно ни бить маршрут на куски, ни тянуть всё подряд по области.
 *
 * **Ограничения — оттуда же, из OSM.** Тем же запросом забираются дороги вдоль
 * маршрута вместе с их геометрией и тегом `maxspeed`, а текущее ограничение
 * определяется по ближайшей дороге под машиной.
 *
 * Изначально ограничения пытались брать у OSRM параметром `annotations=maxspeed` —
 * и это было ошибкой, которая ломала навигацию целиком: такого значения у OSRM
 * нет (это расширение Mapbox Directions), сервер отвечал InvalidOptions, и
 * маршрут не строился вообще никогда.
 *
 * Где в OSM ограничение не проставлено, там его не будет и здесь — знак просто не
 * показывается. Врать и подставлять «наверное, 60» оболочка не станет: на дороге
 * это хуже, чем честное «не знаю». А вот теги вида `BY:urban` разворачиваются в
 * числа честно: это не догадка, а прямо записанное в карте «здесь действует
 * белорусский норматив для населённого пункта».
 *
 * Справочно, если знака нет: в Беларуси по умолчанию 60 км/ч в населённом пункте,
 * 90 вне его, 110 на автомагистрали, 20 в жилой зоне.
 *
 * **Свои файлы.** В папку `Android/data/<пакет>/files/poi/` можно положить базы от
 * радар-детекторов — `.csv` (`долгота,широта,название`, как у TomTom) и `.ov2`.
 * Они читаются при старте и работают наравне с данными OSM, в том числе без сети.
 *
 * ## Чего это не заменяет
 *
 * Мобильных засад и свежепоставленных камер здесь нет и быть не может: OSM
 * обновляется людьми, а не оператором комплексов фиксации. Это подсказка, а не
 * гарантия — следить за знаками всё равно придётся самому.
 */
object HazardHub {

    private const val USER_AGENT = "NeonDrive-CarLauncher/1.4 (Android head-unit launcher)"

    private val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    /** За сколько метров предупреждать о камере. */
    private const val WARN_DISTANCE_M = 300.0

    /** Ближе этого камера считается пройденной. */
    private const val PASSED_M = 25.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(HazardState())
    val state: StateFlow<HazardState> = _state

    /** Камеры текущего маршрута плюс постоянные из пользовательских файлов. */
    private var routeCameras: List<SpeedCamera> = emptyList()
    private var fileCameras: List<SpeedCamera> = emptyList()
    private var speedZones: List<SpeedZone> = emptyList()
    private var filesLoaded = false

    /** Зона, найденная на прошлом фиксе: почти всегда она же и на этом. */
    private var lastZone: SpeedZone? = null

    private var warnedCameras = HashSet<Int>()
    private var speedingAnnounced = false

    var cameraWarnEnabled = true
    var speedLimitEnabled = true
    var toleranceKmh = 10

    /* ─────────────────  ЗАГРУЗКА  ───────────────── */

    /** Подтянуть камеры вдоль только что построенного маршрута. */
    fun loadForRoute(context: Context, points: List<RoutePoint>) {
        loadJob?.cancel()
        warnedCameras.clear()
        if (points.size < 2) {
            routeCameras = emptyList()
            return
        }
        loadJob = scope.launch {
            if (!filesLoaded) {
                filesLoaded = true
                fileCameras = runCatching { loadUserFiles(context) }.getOrDefault(emptyList())
            }
            val data = runCatching { queryRoadData(points) }.getOrNull()
            routeCameras = data?.first.orEmpty()
            speedZones = data?.second.orEmpty()
            lastZone = null
            _state.value = _state.value.copy(
                camerasLoaded = routeCameras.size + fileCameras.size
            )
        }
    }

    fun clear() {
        loadJob?.cancel()
        routeCameras = emptyList()
        speedZones = emptyList()
        lastZone = null
        warnedCameras.clear()
        speedingAnnounced = false
        _state.value = HazardState()
    }

    /**
     * Очередной фикс GPS: пересчитать ближайшую камеру и проверить превышение.
     * Вызывается из [GuidanceEngine], чтобы не заводить второй сборщик GPS.
     */
    fun onFix(lat: Double, lon: Double, speedKmh: Float) {
        val limitKmh = currentLimit(lat, lon)
        val cameras = routeCameras + fileCameras

        var nearestDist: Double? = null
        var nearestLimit: Int? = null
        var nearestKey: Int? = null

        for (cam in cameras) {
            val d = GeoMath.distanceM(lat, lon, cam.lat, cam.lon)
            if (d > WARN_DISTANCE_M * 2) continue
            if (nearestDist == null || d < nearestDist) {
                nearestDist = d
                nearestLimit = cam.limitKmh
                nearestKey = cameraKey(cam)
            }
        }

        // Дальше работаем с неизменяемыми копиями: умные приведения типов на
        // локальных `var` компилятор делает не везде одинаково, а читается такой
        // код всё равно хуже.
        val dist = nearestDist
        val key = nearestKey
        val camLimit = nearestLimit

        val showCamera = cameraWarnEnabled && dist != null && dist <= WARN_DISTANCE_M
        val limit = if (speedLimitEnabled) limitKmh else null
        val speeding = limit != null && speedKmh > limit + toleranceKmh

        _state.value = _state.value.copy(
            cameraAheadM = if (showCamera) dist else null,
            cameraLimitKmh = if (showCamera) camLimit else null,
            speedLimitKmh = limit,
            speeding = speeding
        )

        // Голос — один раз на камеру. Без этого фраза повторялась бы на каждом
        // фиксе GPS все триста метров подряд.
        if (showCamera && dist != null && key != null && dist > PASSED_M &&
            warnedCameras.add(key)
        ) {
            val rounded = GeoMath.roundForSpeech(dist)
            val limitPart = if (camLimit != null) ", ограничение $camLimit" else ""
            GuidanceEngine.speakExternal("Камера через $rounded метров$limitPart")
        }

        // Предупреждение о превышении — тоже один раз на эпизод: пока не сбросил
        // скорость до нормы, повторять бессмысленно и раздражающе.
        if (speeding && !speedingAnnounced) {
            speedingAnnounced = true
            GuidanceEngine.speakExternal("Превышение, ограничение $limit")
        } else if (!speeding && limit != null && speedKmh < limit) {
            speedingAnnounced = false
        }
    }

    private fun cameraKey(cam: SpeedCamera): Int =
        ((cam.lat * 1e5).toInt() * 31) xor (cam.lon * 1e5).toInt()

    /* ─────────────────  OVERPASS  ───────────────── */

    /**
     * Один запрос на маршрут — и камеры, и дороги с ограничениями.
     *
     * Два `out` в одном запросе Overpass допускает, а лишний сетевой поход в
     * дороге — это лишние секунды и лишний трафик. Дороги забираются с геометрией
     * (`out geom`), иначе по ним нельзя понять, под какой из них сейчас машина.
     */
    private fun queryRoadData(
        points: List<RoutePoint>
    ): Pair<List<SpeedCamera>, List<SpeedZone>> {
        // Полный трек в запрос не влезет и не нужен: коридор вдоль проредённой
        // каждые ~700 м ломаной покрывает ту же дорогу, а запрос остаётся
        // коротким. Без прореживания городской маршрут — это тысячи точек, и
        // Overpass такой запрос просто отвергнет.
        val simplified = decimate(points, 700.0)
        if (simplified.size < 2) return emptyList<SpeedCamera>() to emptyList()

        val coords = simplified.joinToString(",") { "${it.lat},${it.lon}" }
        val body = buildString {
            append("[out:json][timeout:30];(")
            append("""node["highway"="speed_camera"](around:250,$coords);""")
            append("""node["enforcement"="maxspeed"](around:250,$coords);""")
            append("""node["surveillance:type"="camera"]["surveillance:zone"="traffic"](around:250,$coords);""")
            append(");out body 300;")
            // Коридор для дорог узкий: 50 м. Широкий притащил бы параллельные
            // улицы и дворовые проезды, и знак ограничения скакал бы между ними.
            append("""way["maxspeed"]["highway"](around:50,$coords);""")
            append("out geom 600;")
        }

        for (mirror in OVERPASS_MIRRORS) {
            val result = runCatching { postOverpass(mirror, body) }.getOrNull()
            if (result != null) return result
        }
        return emptyList<SpeedCamera>() to emptyList()
    }

    private fun postOverpass(
        baseUrl: String,
        body: String
    ): Pair<List<SpeedCamera>, List<SpeedZone>>? {
        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 7000
            readTimeout = 25000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use {
                it.write(("data=" + URLEncoder.encode(body, "UTF-8")).toByteArray())
            }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val elements = JSONObject(text).optJSONArray("elements") ?: return null

            val cameras = ArrayList<SpeedCamera>(64)
            val zones = ArrayList<SpeedZone>(256)

            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val tags = el.optJSONObject("tags")

                when (el.optString("type")) {
                    "node" -> {
                        val lat = el.optDouble("lat", Double.NaN)
                        val lon = el.optDouble("lon", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        cameras += SpeedCamera(lat, lon, parseMaxspeed(tags?.optString("maxspeed")))
                    }

                    "way" -> {
                        val limit = parseMaxspeed(tags?.optString("maxspeed")) ?: continue
                        val geom = el.optJSONArray("geometry") ?: continue
                        val pts = ArrayList<RoutePoint>(geom.length())
                        var minLat = 90.0; var maxLat = -90.0
                        var minLon = 180.0; var maxLon = -180.0
                        for (g in 0 until geom.length()) {
                            val o = geom.optJSONObject(g) ?: continue
                            val la = o.optDouble("lat", Double.NaN)
                            val lo = o.optDouble("lon", Double.NaN)
                            if (la.isNaN() || lo.isNaN()) continue
                            pts += RoutePoint(la, lo)
                            if (la < minLat) minLat = la
                            if (la > maxLat) maxLat = la
                            if (lo < minLon) minLon = lo
                            if (lo > maxLon) maxLon = lo
                        }
                        if (pts.size < 2) continue
                        zones += SpeedZone(pts, limit, minLat, maxLat, minLon, maxLon)
                    }
                }
            }
            return cameras to zones
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Значение тега `maxspeed` в км/ч.
     *
     * В OSM оно бывает трёх видов: просто число («90»), число с единицами
     * («55 mph»), и ссылка на национальный норматив («BY:urban»). Последнее — не
     * догадка, а прямо записанное в карте «здесь действует такой-то тип дороги»,
     * поэтому разворачивать его в число законно. Значения `none`, `signals`,
     * `variable` и прочую неопределённость возвращаем как null: показать знак
     * там, где ограничение неизвестно, хуже, чем не показать ничего.
     */
    private fun parseMaxspeed(raw: String?): Int? {
        val v = raw?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return null

        // Национальные нормативы. Беларусь — основной регион, соседей добавляем,
        // потому что маршрут запросто уходит за границу.
        NATIONAL_LIMITS[v]?.let { return it }

        val digits = v.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        val kmh = if (v.contains("mph")) (digits * 1.609344).toInt() else digits
        return kmh.takeIf { it in 5..200 }
    }

    /** Национальные нормативы скорости из тега `maxspeed`, км/ч. */
    private val NATIONAL_LIMITS = mapOf(
        "by:living_street" to 20,
        "by:urban" to 60,
        "by:rural" to 90,
        "by:motorway" to 110,
        "ru:living_street" to 20,
        "ru:urban" to 60,
        "ru:rural" to 90,
        "ru:motorway" to 110,
        "ua:urban" to 50,
        "ua:rural" to 90,
        "ua:motorway" to 130,
        "pl:living_street" to 20,
        "pl:urban" to 50,
        "pl:rural" to 90,
        "pl:motorway" to 140,
        "lt:urban" to 50,
        "lt:rural" to 90,
        "lt:motorway" to 130,
        "lv:urban" to 50,
        "lv:rural" to 90,
        "walk" to 5
    )

    /* ─────────────────  ТЕКУЩЕЕ ОГРАНИЧЕНИЕ  ───────────────── */

    /**
     * Ограничение на дороге под машиной.
     *
     * Сначала проверяется зона с прошлого фикса: машина почти всегда всё ещё на
     * той же улице, и это превращает перебор сотен дорог в одну проверку. Полный
     * перебор идёт только когда прошлая зона не подошла, и защищён проверкой
     * прямоугольника: считать расстояние до линии имеет смысл лишь для дорог,
     * чей габаритный прямоугольник вообще накрывает текущую точку.
     */
    private fun currentLimit(lat: Double, lon: Double): Int? {
        if (speedZones.isEmpty()) return null

        lastZone?.let { z ->
            if (inBox(z, lat, lon) &&
                GeoMath.nearestOnRoute(z.points, lat, lon).distanceM <= ZONE_MATCH_M
            ) return z.limitKmh
        }

        var best: SpeedZone? = null
        var bestDist = ZONE_MATCH_M
        for (z in speedZones) {
            if (!inBox(z, lat, lon)) continue
            val d = GeoMath.nearestOnRoute(z.points, lat, lon).distanceM
            if (d < bestDist) {
                bestDist = d
                best = z
            }
        }
        lastZone = best
        return best?.limitKmh
    }

    /** Ближе этого к оси дороги считаем, что едем именно по ней. */
    private const val ZONE_MATCH_M = 30.0

    /** Запас прямоугольника в градусах — примерно 40 м по широте. */
    private const val BOX_PAD = 0.00035

    private fun inBox(z: SpeedZone, lat: Double, lon: Double): Boolean =
        lat >= z.minLat - BOX_PAD && lat <= z.maxLat + BOX_PAD &&
            lon >= z.minLon - BOX_PAD && lon <= z.maxLon + BOX_PAD

    /** Прореживание трека: оставляем точки не чаще чем раз в [stepM] метров. */
    private fun decimate(points: List<RoutePoint>, stepM: Double): List<RoutePoint> {
        if (points.size <= 2) return points
        val out = ArrayList<RoutePoint>(points.size / 4 + 2)
        out += points.first()
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += GeoMath.distanceM(
                points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon
            )
            if (acc >= stepM) {
                out += points[i]
                acc = 0.0
            }
        }
        if (out.last() != points.last()) out += points.last()
        // Overpass не любит гигантские запросы: жёстко ограничиваем длину ломаной.
        return if (out.size > 120) out.filterIndexed { i, _ -> i % (out.size / 120 + 1) == 0 } else out
    }

    /* ─────────────────  ПОЛЬЗОВАТЕЛЬСКИЕ ФАЙЛЫ  ───────────────── */

    /** Папка, куда класть свои базы камер. Видна с компьютера по USB. */
    fun poiFolder(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.cacheDir, "poi")

    private fun loadUserFiles(context: Context): List<SpeedCamera> {
        val dir = poiFolder(context)
        runCatching { dir.mkdirs() }
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<SpeedCamera>(512)
        for (f in files) {
            when (f.extension.lowercase()) {
                "csv", "txt" -> out += runCatching { parseCsv(f) }.getOrDefault(emptyList())
                "ov2" -> out += runCatching { parseOv2(f) }.getOrDefault(emptyList())
            }
        }
        return out
    }

    /**
     * CSV от радар-детекторов. Канонический порядок у TomTom — «долгота,широта»,
     * но половина баз в интернете лежит наоборот, поэтому порядок определяется по
     * значению: широта не бывает больше 90 градусов по модулю. Это надёжнее, чем
     * доверять заголовку, которого часто просто нет.
     */
    private fun parseCsv(file: File): List<SpeedCamera> {
        val out = ArrayList<SpeedCamera>(256)
        file.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split(',', ';').map { it.trim().trim('"') }
            if (parts.size < 2) return@forEachLine
            val a = parts[0].toDoubleOrNull() ?: return@forEachLine
            val b = parts[1].toDoubleOrNull() ?: return@forEachLine
            val (lat, lon) = if (abs(a) <= 90.0 && abs(b) > 90.0) a to b
            else if (abs(b) <= 90.0) b to a
            else return@forEachLine
            val name = parts.getOrNull(2).orEmpty()
            out += SpeedCamera(
                lat = lat,
                lon = lon,
                limitKmh = name.filter { it.isDigit() }.take(3).toIntOrNull()
                    ?.takeIf { it in 20..150 },
                source = file.name
            )
        }
        return out
    }

    /**
     * TomTom `.ov2` — простой бинарный формат.
     *
     * Запись начинается с байта типа. Тип 1 — «skipper», служебная запись на 21
     * байт, её пропускаем целиком. Типы 2 и 3 — сама точка: 4 байта длины всей
     * записи, затем долгота и широта как целые со знаком в 1/100000 градуса, затем
     * название до нулевого байта. Все числа — little-endian.
     */
    private fun parseOv2(file: File): List<SpeedCamera> {
        val bytes = file.readBytes()
        val out = ArrayList<SpeedCamera>(256)
        var i = 0
        while (i < bytes.size) {
            when (bytes[i].toInt() and 0xFF) {
                1 -> i += 21
                2, 3 -> {
                    if (i + 13 > bytes.size) return out
                    val total = readIntLe(bytes, i + 1)
                    if (total <= 13 || i + total > bytes.size) return out
                    val lon = readIntLe(bytes, i + 5) / 100000.0
                    val lat = readIntLe(bytes, i + 9) / 100000.0
                    val name = String(bytes, i + 13, total - 13 - 1, Charsets.ISO_8859_1)
                        .trim { it <= ' ' }
                    if (abs(lat) <= 90 && abs(lon) <= 180) {
                        out += SpeedCamera(
                            lat = lat,
                            lon = lon,
                            limitKmh = name.filter { it.isDigit() }.take(3).toIntOrNull()
                                ?.takeIf { it in 20..150 },
                            source = file.name
                        )
                    }
                    i += total
                }
                else -> return out   // неизвестный тип — дальше разбирать нечего
            }
        }
        return out
    }

    private fun readIntLe(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
