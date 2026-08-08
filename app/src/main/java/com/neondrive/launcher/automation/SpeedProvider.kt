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
 * Скорость по GPS. Сглаживание экспоненциальное — стрелка не должна дёргаться
 * на каждом «плохом» отсчёте, но и отставать больше секунды тоже нельзя.
 */
object SpeedProvider : LocationListener {

    private const val ALPHA = 0.35f
    private const val MIN_TIME_MS = 500L
    private const val MIN_DIST_M = 0f

    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    private var lm: LocationManager? = null
    private var lastLocation: Location? = null
    private var smoothed = 0f
    private var started = false

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
        _state.value = _state.value.copy(speedKmh = 0f, hasFix = false)
    }

    override fun onLocationChanged(location: Location) {
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

        _state.value = _state.value.copy(
            speedKmh = shown,
            hasFix = true,
            accuracyM = location.accuracy,
            altitudeM = location.altitude,
            bearingDeg = location.bearing,
            lastLat = location.latitude,
            lastLon = location.longitude,
            satellites = location.extras?.getInt("satellites", 0) ?: _state.value.satellites
        )
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
