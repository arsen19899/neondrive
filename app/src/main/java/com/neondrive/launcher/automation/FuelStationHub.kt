package com.neondrive.launcher.automation

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FuelState(
    /** Расстояние по дорогам до ближайшей АЗС, км. Null — ещё не найдено. */
    val distanceKm: Float? = null,
    val stationName: String = "",
    val loading: Boolean = false,
    val error: Boolean = false
)

/**
 * Расстояние до ближайшей заправки — по дорогам, а не по прямой.
 *
 * Механизм в два шага, оба через открытые бесплатные сервисы без ключей:
 *  1. Overpass API (сеть зеркал OpenStreetMap) — ищем узлы amenity=fuel в радиусе
 *     вокруг текущей точки. Возвращает и координаты, и (если есть) название.
 *  2. Из ближайших по прямой кандидатов (не больше 5 — гонять роутинг по всем
 *     дорого) через OSRM считаем настоящее расстояние по дорогам и берём минимум.
 *     Это и есть требование «не по прямой, а как реально ехать».
 *
 * Публичные демо-сервисы отваливаются поодиночке (перегрузка, временная
 * недоступность) — поэтому у каждого шага есть несколько независимых зеркал,
 * перебираемых по очереди, плюс повтор с расширенным радиусом поиска, если по
 * умолчанию рядом ничего не нашлось. См. подробности в [FuelStationApi].
 */
object FuelStationHub {

    private val _state = MutableStateFlow(FuelState())
    val state: StateFlow<FuelState> = _state

    private var started = false
    private lateinit var scope: CoroutineScope

    private var lastQueryAt = 0L
    private var lastQueryLat = Double.NaN
    private var lastQueryLon = Double.NaN

    private const val MIN_INTERVAL_MS = 4 * 60_000L
    private const val MIN_MOVE_M = 1500f

    fun start(context: Context) {
        if (started) return
        started = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            SpeedProvider.state.collect { gps ->
                if (!gps.hasFix) return@collect
                val moved = if (lastQueryLat.isNaN()) Float.MAX_VALUE else {
                    val out = FloatArray(1)
                    Location.distanceBetween(gps.lastLat, gps.lastLon, lastQueryLat, lastQueryLon, out)
                    out[0]
                }
                // Повторяем чаще, пока не получили вообще ни одного результата —
                // не ждать же 4 минуты до следующей попытки, если первая просто
                // не достучалась до сервиса.
                val retryInterval = if (_state.value.distanceKm == null) 45_000L else MIN_INTERVAL_MS
                val due = System.currentTimeMillis() - lastQueryAt > retryInterval
                if (lastQueryAt == 0L || due || moved > MIN_MOVE_M) {
                    lastQueryAt = System.currentTimeMillis()
                    lastQueryLat = gps.lastLat
                    lastQueryLon = gps.lastLon
                    query(gps.lastLat, gps.lastLon)
                }
            }
        }
    }

    /** Принудительно повторить запрос прямо сейчас (например, по кнопке в UI). */
    fun refreshNow() {
        if (!started || lastQueryLat.isNaN()) return
        lastQueryAt = System.currentTimeMillis()
        query(lastQueryLat, lastQueryLon)
    }

    private fun query(lat: Double, lon: Double) {
        scope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = withContext(Dispatchers.IO) {
                runCatching { FuelStationApi.findNearest(lat, lon) }.getOrNull()
            }
            _state.value = if (result != null) {
                FuelState(
                    distanceKm = result.distanceKm,
                    stationName = result.name,
                    loading = false,
                    error = false
                )
            } else {
                _state.value.copy(loading = false, error = true)
            }
        }
    }
}

object FuelStationApi {

    data class Nearest(val name: String, val distanceKm: Float)
    private data class Node(val lat: Double, val lon: Double, val name: String)

    // Несколько независимых зеркал Overpass — публичные инстансы время от времени
    // перегружены или недоступны поодиночке, перебор по очереди резко поднимает
    // шанс получить ответ хоть от одного.
    private val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    // Аналогично — основной публичный роутер OSRM и запасной инстанс.
    private val OSRM_MIRRORS = listOf(
        "https://router.project-osrm.org/route/v1/driving",
        "https://routing.openstreetmap.de/routed-car/route/v1/driving"
    )

    private const val USER_AGENT = "NeonDrive-CarLauncher/1.2 (+https://github.com/)"

    /** [radiusM] — насколько широко искать АЗС вокруг точки, метры. */
    fun findNearest(lat: Double, lon: Double, radiusM: Int = 15000): Nearest? {
        // Сначала обычный радиус, и только если совсем ничего не нашлось —
        // расширяем: в глухих местах 15 км может быть мало.
        val candidates = queryOverpassAnyMirror(lat, lon, radiusM)
            ?: queryOverpassAnyMirror(lat, lon, radiusM * 3)
            ?: return null
        if (candidates.isEmpty()) return null

        // Роутинг по всем найденным станциям — дорого и медленно. Берём 5 ближайших
        // по прямой и уже среди них ищем минимум по дорогам.
        val shortlisted = candidates
            .sortedBy { straightLineM(lat, lon, it.lat, it.lon) }
            .take(5)

        var best: Nearest? = null
        for (c in shortlisted) {
            val km = routeDistanceKmAnyMirror(lat, lon, c.lat, c.lon) ?: continue
            if (best == null || km < best.distanceKm) {
                best = Nearest(c.name.ifBlank { "АЗС" }, km)
            }
        }
        // Ни один роутер не ответил — честно откатываемся на «по прямой», это не
        // то же самое, но лучше молчания.
        return best ?: shortlisted.firstOrNull()?.let {
            Nearest(it.name.ifBlank { "АЗС" }, straightLineM(lat, lon, it.lat, it.lon) / 1000f)
        }
    }

    private fun queryOverpassAnyMirror(lat: Double, lon: Double, radiusM: Int): List<Node>? {
        for (mirror in OVERPASS_MIRRORS) {
            val result = queryOverpass(mirror, lat, lon, radiusM)
            if (result != null && result.isNotEmpty()) return result
        }
        return null
    }

    private fun queryOverpass(baseUrl: String, lat: Double, lon: Double, radiusM: Int): List<Node>? {
        val query = "[out:json][timeout:15];node[\"amenity\"=\"fuel\"](around:$radiusM,$lat,$lon);out body 30;"
        return runCatching {
            val conn = java.net.URL(baseUrl).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 7000
            conn.readTimeout = 12000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use {
                it.write(("data=" + java.net.URLEncoder.encode(query, "UTF-8")).toByteArray())
            }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val elements = JSONObject(text).getJSONArray("elements")
            (0 until elements.length()).mapNotNull { i ->
                val el = elements.getJSONObject(i)
                val elLat = el.optDouble("lat", Double.NaN)
                val elLon = el.optDouble("lon", Double.NaN)
                if (elLat.isNaN() || elLon.isNaN()) return@mapNotNull null
                val name = el.optJSONObject("tags")?.optString("name", "").orEmpty()
                Node(elLat, elLon, name)
            }
        }.getOrNull()
    }

    private fun routeDistanceKmAnyMirror(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float? {
        for (mirror in OSRM_MIRRORS) {
            val km = routeDistanceKm(mirror, lat1, lon1, lat2, lon2)
            if (km != null) return km
        }
        return null
    }

    private fun routeDistanceKm(baseUrl: String, lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float? {
        return runCatching {
            val url = "$baseUrl/$lon1,$lat1;$lon2,$lat2?overview=false"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val routes = JSONObject(text).getJSONArray("routes")
            if (routes.length() == 0) return@runCatching null
            (routes.getJSONObject(0).getDouble("distance") / 1000.0).toFloat()
        }.getOrNull()
    }

    private fun straightLineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0]
    }
}
