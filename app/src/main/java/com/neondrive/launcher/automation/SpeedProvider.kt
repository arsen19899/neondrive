package com.neondrive.launcher.automation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GpsState(
    val speedKmh: Float = 0f,
    val hasFix: Boolean = false,
    val satellites: Int = 0,
    val accuracyM: Float = 0f,
    val altitudeM: Double = 0.0,
    val bearingDeg: Float = 0f,
    val lastLat: Double = 0.0,
    val lastLon: Double = 0.0,
    val permissionGranted: Boolean = false
)

/**
 * Скорость и позиция по GPS.
 *
 * ## Почему здесь фильтр, а не просто «что пришло, то и показываем»
 *
 * Слушатель подписан сразу на два провайдера: спутниковый и сетевой. Сетевой
 * нужен — часть ГУ отдаёт скорость только через него, а в начале поездки он
 * даёт первую точку раньше спутников. Но точность у него сотни метров, и когда
 * оба провайдера работают одновременно, точки от них приходят вперемешку:
 * спутниковая, сетевая, спутниковая… Метка машины при этом прыгает между
 * реальным положением и вышкой сотовой связи — то самое «прыгает на соседние
 * точки». Поэтому пока спутники живы, сетевые точки игнорируются целиком.
 *
 * Отдельно отбрасываются «телепорты»: смещение, которое невозможно проехать за
 * прошедшее время. Такое приходит после тоннеля, при холодном старте и просто
 * от плохого приёма между домами. Подряд их отбрасывается не больше нескольких —
 * иначе после настоящего перемещения (машину везли на эвакуаторе, ГУ было
 * выключено) фильтр залип бы навсегда.
 *
 * Сглаживание скорости экспоненциальное — стрелка не должна дёргаться на каждом
 * «плохом» отсчёте, но и отставать больше секунды тоже нельзя.
 */
object SpeedProvider : LocationListener {

    private const val ALPHA = 0.35f
    private const val MIN_TIME_MS = 500L
    private const val MIN_DIST_M = 0f

    /** Сколько после спутниковой точки не слушаем сетевую. */
    private const val NETWORK_HOLDOFF_MS = 20_000L

    /** Точность хуже этой — точка почти наверняка от вышки, а не от спутников. */
    private const val JUNK_ACCURACY_M = 120f

    /** Быстрее этого (м/с ≈ 360 км/ч) машина не ездит — значит это выброс. */
    private const val TELEPORT_SPEED_MS = 100.0
    private const val TELEPORT_MIN_M = 120f
    private const val MAX_REJECTS = 4

    /** Ниже этой скорости курс от GPS недостоверен — держим прежний. */
    private const val BEARING_MIN_KMH = 5f

    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    private var lm: LocationManager? = null
    private var lastLocation: Location? = null
    private var smoothed = 0f
    private var started = false
    private var lastGpsFixMs = 0L
    private var rejectedInRow = 0

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (started) return
        val granted = hasPermission(context)
        _state.value = _state.value.copy(permissionGranted = granted)
        if (!granted) return

        lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val manager = lm ?: return
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DIST_M, this
            )
        }
        // Некоторые ГУ отдают скорость только через сетевой провайдер
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 0f, this
            )
        }
        started = true
    }

    fun stop() {
        runCatching { lm?.removeUpdates(this) }
        started = false
        smoothed = 0f
        lastLocation = null
        lastGpsFixMs = 0L
        rejectedInRow = 0
        _state.value = _state.value.copy(speedKmh = 0f, hasFix = false)
    }

    override fun onLocationChanged(location: Location) {
        if (!accept(location)) return

        // Скорость из фикса, а если её нет — считаем по смещению
        val raw = if (location.hasSpeed()) {
            location.speed
        } else {
            val prev = lastLocation
            if (prev == null) 0f else {
                val dt = (location.time - prev.time) / 1000f
                if (dt <= 0.2f) smoothed / 3.6f else location.distanceTo(prev) / dt
            }
        }
        lastLocation = location

        val kmh = (raw * 3.6f).coerceIn(0f, 320f)
        smoothed = if (smoothed == 0f) kmh else smoothed + ALPHA * (kmh - smoothed)
        // Ниже 2 км/ч GPS обычно шумит — показываем ноль
        val shown = if (smoothed < 2f) 0f else smoothed

        // Курс на месте — случайное число: стоящая машина «вертится» на карте, а
        // вместе с ней и вся повёрнутая по курсу карта. Ниже порога держим тот
        // курс, с которым машина остановилась.
        val bearing = if (location.hasBearing() && shown >= BEARING_MIN_KMH) {
            location.bearing
        } else {
            _state.value.bearingDeg
        }

        _state.value = _state.value.copy(
            speedKmh = shown,
            hasFix = true,
            accuracyM = location.accuracy,
            altitudeM = location.altitude,
            bearingDeg = bearing,
            lastLat = location.latitude,
            lastLon = location.longitude,
            satellites = location.extras?.getInt("satellites", 0) ?: _state.value.satellites
        )
    }

    /**
     * Годится ли фикс к показу. Отсекает чужой провайдер, мусорную точность и
     * телепорты — см. описание класса.
     */
    private fun accept(location: Location): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val isGps = location.provider == LocationManager.GPS_PROVIDER

        if (isGps) {
            lastGpsFixMs = now
        } else if (now - lastGpsFixMs < NETWORK_HOLDOFF_MS) {
            return false
        }

        if (_state.value.hasFix &&
            location.hasAccuracy() && location.accuracy > JUNK_ACCURACY_M
        ) return false

        val prev = lastLocation
        if (prev != null && rejectedInRow < MAX_REJECTS) {
            val dt = ((location.time - prev.time) / 1000.0).coerceAtLeast(0.5)
            val d = location.distanceTo(prev)
            if (d > TELEPORT_MIN_M && d / dt > TELEPORT_SPEED_MS) {
                rejectedInRow++
                return false
            }
        }
        rejectedInRow = 0
        return true
    }

    @Deprecated("Требуется на старых ГУ (API < 29)")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        _state.value = _state.value.copy(satellites = extras?.getInt("satellites", 0) ?: 0)
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            smoothed = 0f
            _state.value = _state.value.copy(hasFix = false, speedKmh = 0f)
        }
    }
}
