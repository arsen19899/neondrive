package com.neondrive.launcher.nav

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Во что обходится вариант маршрута по «неудобным» участкам. */
data class RouteQuality(
    /** Сколько метров маршрута проходит по грунту, щебню, песку и колеям. */
    val unpavedM: Double = 0.0,
    /** Сколько метров по платным участкам. */
    val tollM: Double = 0.0,
    /** Удалось ли вообще получить данные о покрытии. */
    val known: Boolean = false
)

/**
 * Оценка покрытия и платности вариантов маршрута.
 *
 * ## Почему не «избегать грунтовок» прямо в роутере
 *
 * Так было бы правильнее, но публичный демо-сервер OSRM этого не умеет:
 * параметр `exclude` работает только если профиль собран с классами исключений,
 * а у демо-сервера они не настроены — сервер молча отвечает пустотой на
 * `exclude=toll` ровно так же, как отвечал на несуществующий
 * `annotations=maxspeed`. Проверено запросом, а не документацией.
 *
 * Поэтому избегание сделано честным вторым шагом: роутер даёт два-три варианта,
 * а оболочка смотрит по OpenStreetMap, сколько в каждом грунта и платных
 * участков, и выбирает лучший. Это не «объехать во что бы то ни стало» — если
 * все варианты идут по грунту, то и выбирать не из чего, — но на практике
 * альтернативы у OSRM разные, и выбор обычно есть.
 *
 * Формулировка в настройках подобрана под это ограничение: «предпочитать
 * маршрут без грунтовок», а не «строить в объезд». Обещать второе было бы враньём.
 */
object SurfaceScout {

    private const val USER_AGENT = "NeonDrive-CarLauncher/1.4 (Android head-unit launcher)"

    private val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    /** Покрытия, которые считаем грунтовыми. */
    private val UNPAVED = setOf(
        "unpaved", "gravel", "fine_gravel", "ground", "dirt", "earth",
        "sand", "mud", "grass", "compacted", "pebblestone", "woodchips"
    )

    /** Насколько близко к оси дороги должна пройти точка маршрута, чтобы зачесть её. */
    private const val MATCH_M = 25.0

    /**
     * Оценить вариант маршрута. Тяжёлая сетевая операция — вызывать с фонового
     * потока и только когда пользователь действительно попросил избегать грунта.
     */
    fun evaluate(points: List<RoutePoint>): RouteQuality {
        if (points.size < 2) return RouteQuality()

        val simplified = decimate(points, 700.0)
        if (simplified.size < 2) return RouteQuality()
        val coords = simplified.joinToString(",") { "${it.lat},${it.lon}" }

        val body = buildString {
            append("[out:json][timeout:30];(")
            append("""way["surface"]["highway"](around:50,$coords);""")
            append("""way["toll"="yes"]["highway"](around:50,$coords);""")
            append("""way["highway"="track"](around:50,$coords);""")
            append(");out geom 600;")
        }

        val ways = OVERPASS_MIRRORS.firstNotNullOfOrNull { mirror ->
            runCatching { post(mirror, body) }.getOrNull()
        } ?: return RouteQuality()

        // Считаем не длину найденных дорог, а длину МАРШРУТА, которая рядом с
        // ними: дорога может тянуться на километры мимо нашего пути, и её полная
        // длина ничего не сказала бы о поездке.
        var unpaved = 0.0
        var toll = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val segLen = GeoMath.distanceM(a.lat, a.lon, b.lat, b.lon)
            if (segLen <= 0.0) continue
            val midLat = (a.lat + b.lat) / 2
            val midLon = (a.lon + b.lon) / 2

            var hitUnpaved = false
            var hitToll = false
            for (w in ways) {
                if (!w.inBox(midLat, midLon)) continue
                if (GeoMath.nearestOnRoute(w.points, midLat, midLon).distanceM > MATCH_M) continue
                if (w.unpaved) hitUnpaved = true
                if (w.toll) hitToll = true
                if (hitUnpaved && hitToll) break
            }
            if (hitUnpaved) unpaved += segLen
            if (hitToll) toll += segLen
        }

        return RouteQuality(unpavedM = unpaved, tollM = toll, known = true)
    }

    /* ─────────────────  ВНУТРЕННЕЕ  ───────────────── */

    private class Way(
        val points: List<RoutePoint>,
        val unpaved: Boolean,
        val toll: Boolean,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        fun inBox(lat: Double, lon: Double): Boolean =
            lat >= minLat - PAD && lat <= maxLat + PAD &&
                lon >= minLon - PAD && lon <= maxLon + PAD

        companion object {
            private const val PAD = 0.0003
        }
    }

    private fun post(baseUrl: String, body: String): List<Way>? {
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

            val out = ArrayList<Way>(256)
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val geom = el.optJSONArray("geometry") ?: continue
                val tags = el.optJSONObject("tags")
                val surface = tags?.optString("surface").orEmpty().lowercase()
                val isTrack = tags?.optString("highway") == "track"
                val unpaved = isTrack || surface in UNPAVED
                val toll = tags?.optString("toll") == "yes"
                if (!unpaved && !toll) continue

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
                out += Way(pts, unpaved, toll, minLat, maxLat, minLon, maxLon)
            }
            return out
        } finally {
            runCatching { conn.disconnect() }
        }
    }

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
        return if (out.size > 120) out.filterIndexed { i, _ -> i % (out.size / 120 + 1) == 0 } else out
    }
}
