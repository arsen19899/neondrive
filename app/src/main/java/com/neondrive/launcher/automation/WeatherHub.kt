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
import kotlin.math.roundToInt
import org.json.JSONObject

enum class WeatherCondition { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, THUNDER, UNKNOWN }

data class WeatherState(
    val tempC: Int? = null,
    val condition: WeatherCondition = WeatherCondition.UNKNOWN,
    val loading: Boolean = false
)

/**
 * Погода в точке следования — виджет справа от спидометра.
 *
 * На головном устройстве штатного приложения погоды обычно нет, поэтому тянем
 * данные напрямую по координатам GPS через Open-Meteo — открытый сервис без
 * ключа и регистрации, с покрытием всех регионов, включая Беларусь. Обновляем
 * не чаще раза в 15 минут или раз в ~20 км пути — погода не меняется быстрее.
 */
object WeatherHub {

    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state

    private var started = false
    private lateinit var scope: CoroutineScope

    private var lastAt = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    private const val MIN_INTERVAL_MS = 15 * 60_000L
    private const val MIN_MOVE_M = 20_000f

    fun start(context: Context) {
        if (started) return
        started = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            SpeedProvider.state.collect { gps ->
                if (!gps.hasFix) return@collect
                val moved = if (lastLat.isNaN()) Float.MAX_VALUE else {
                    val out = FloatArray(1)
                    Location.distanceBetween(gps.lastLat, gps.lastLon, lastLat, lastLon, out)
                    out[0]
                }
                val due = System.currentTimeMillis() - lastAt > MIN_INTERVAL_MS
                if (lastAt == 0L || due || moved > MIN_MOVE_M) {
                    lastAt = System.currentTimeMillis()
                    lastLat = gps.lastLat
                    lastLon = gps.lastLon
                    refresh(gps.lastLat, gps.lastLon)
                }
            }
        }
    }

    private fun refresh(lat: Double, lon: Double) {
        scope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = withContext(Dispatchers.IO) {
                runCatching { WeatherApi.fetch(lat, lon) }.getOrNull()
            }
            _state.value = if (result != null) {
                WeatherState(tempC = result.first, condition = result.second, loading = false)
            } else {
                _state.value.copy(loading = false)
            }
        }
    }
}

object WeatherApi {

    fun fetch(lat: Double, lon: Double): Pair<Int, WeatherCondition>? = runCatching {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&timezone=auto"
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val current = JSONObject(text).getJSONObject("current")
        val temp = current.getDouble("temperature_2m").roundToInt()
        val code = current.getInt("weather_code")
        temp to codeToCondition(code)
    }.getOrNull()

    /** Коды погоды WMO, как их отдаёт Open-Meteo. */
    private fun codeToCondition(code: Int): WeatherCondition = when (code) {
        0 -> WeatherCondition.CLEAR
        1, 2 -> WeatherCondition.PARTLY_CLOUDY
        3 -> WeatherCondition.CLOUDY
        45, 48 -> WeatherCondition.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCondition.RAIN
        71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
        95, 96, 99 -> WeatherCondition.THUNDER
        else -> WeatherCondition.UNKNOWN
    }
}
