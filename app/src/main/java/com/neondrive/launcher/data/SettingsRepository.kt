package com.neondrive.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("neondrive")

/**
 * Единственный источник правды по настройкам оболочки.
 * Карты (кнопки руля, ступени скорости) хранятся строкой вида "a:b|c:d" —
 * без лишних зависимостей на JSON-библиотеки, что важно для слабых ГУ.
 */
class SettingsRepository(private val context: Context) {

    private object K {
        val accent = stringPreferencesKey("accent")
        val sidebarSide = stringPreferencesKey("sidebar_side")
        val animatedBg = booleanPreferencesKey("animated_bg")
        val show24h = booleanPreferencesKey("show_24h")
        val units = stringPreferencesKey("units")
        val mapPackage = stringPreferencesKey("map_package")
        val navWindowed = booleanPreferencesKey("nav_windowed")
        val homeLat = doublePreferencesKey("home_lat")
        val homeLon = doublePreferencesKey("home_lon")

        val autoplay = booleanPreferencesKey("autoplay")
        val autoplayDelay = intPreferencesKey("autoplay_delay")
        val autoplaySource = stringPreferencesKey("autoplay_source")

        val notifReaction = stringPreferencesKey("notif_reaction")
        val duckPercent = intPreferencesKey("duck_percent")
        val duckHold = intPreferencesKey("duck_hold_ms")
        val onlyPaired = booleanPreferencesKey("only_paired")

        val speedVolume = booleanPreferencesKey("speed_volume")
        val speedSteps = stringPreferencesKey("speed_steps")
        val speedSmooth = intPreferencesKey("speed_smooth_ms")

        val resumeAfterCall = booleanPreferencesKey("resume_after_call")
        val resumeDelay = intPreferencesKey("resume_delay")

        val swcEnabled = booleanPreferencesKey("swc_enabled")
        val swcLongMs = intPreferencesKey("swc_long_ms")
        val swcShort = stringPreferencesKey("swc_short")
        val swcLong = stringPreferencesKey("swc_long")
        val swcAdc = booleanPreferencesKey("swc_adc")
        val swcAdcPath = stringPreferencesKey("swc_adc_path")
        val swcAdcTol = intPreferencesKey("swc_adc_tol")
        val swcAdcMap = stringPreferencesKey("swc_adc_map")

        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<LauncherSettings> = context.dataStore.data.map { p ->
        val d = LauncherSettings()
        LauncherSettings(
            accent = p[K.accent] ?: d.accent,
            sidebarSide = p[K.sidebarSide]?.let { runCatching { SidebarSide.valueOf(it) }.getOrNull() }
                ?: d.sidebarSide,
            animatedBackground = p[K.animatedBg] ?: d.animatedBackground,
            show24h = p[K.show24h] ?: d.show24h,
            units = p[K.units]?.let { runCatching { SpeedUnits.valueOf(it) }.getOrNull() } ?: d.units,
            mapPackage = p[K.mapPackage] ?: d.mapPackage,
            navWindowed = p[K.navWindowed] ?: d.navWindowed,
            homeLat = p[K.homeLat] ?: d.homeLat,
            homeLon = p[K.homeLon] ?: d.homeLon,

            autoplay = p[K.autoplay] ?: d.autoplay,
            autoplayDelaySec = p[K.autoplayDelay] ?: d.autoplayDelaySec,
            autoplaySource = p[K.autoplaySource]?.let {
                runCatching { MusicSource.valueOf(it) }.getOrNull()
            } ?: d.autoplaySource,

            notificationReaction = p[K.notifReaction]?.let {
                runCatching { NotificationReaction.valueOf(it) }.getOrNull()
            } ?: d.notificationReaction,
            duckPercent = p[K.duckPercent] ?: d.duckPercent,
            duckHoldMs = p[K.duckHold] ?: d.duckHoldMs,
            onlyPairedDeviceNotifications = p[K.onlyPaired] ?: d.onlyPairedDeviceNotifications,

            speedVolumeEnabled = p[K.speedVolume] ?: d.speedVolumeEnabled,
            speedSteps = p[K.speedSteps]?.let(::decodeSteps) ?: d.speedSteps,
            speedVolumeSmoothMs = p[K.speedSmooth] ?: d.speedVolumeSmoothMs,

            resumeAfterCall = p[K.resumeAfterCall] ?: d.resumeAfterCall,
            resumeAfterCallDelaySec = p[K.resumeDelay] ?: d.resumeAfterCallDelaySec,

            swcEnabled = p[K.swcEnabled] ?: d.swcEnabled,
            swcLongPressMs = p[K.swcLongMs] ?: d.swcLongPressMs,
            swcShort = p[K.swcShort]?.let(::decodeActions) ?: d.swcShort,
            swcLong = p[K.swcLong]?.let(::decodeActions) ?: d.swcLong,
            swcAdcEnabled = p[K.swcAdc] ?: d.swcAdcEnabled,
            swcAdcPath = p[K.swcAdcPath] ?: d.swcAdcPath,
            swcAdcTolerance = p[K.swcAdcTol] ?: d.swcAdcTolerance,
            swcAdcMap = p[K.swcAdcMap]?.let(::decodeActions) ?: d.swcAdcMap,

            startOnBoot = p[K.startOnBoot] ?: d.startOnBoot,
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn
        )
    }

    /* ─────────  Точечные сеттеры  ───────── */

    suspend fun setAccent(v: String) = put { it[K.accent] = v }
    suspend fun setSidebarSide(v: SidebarSide) = put { it[K.sidebarSide] = v.name }
    suspend fun setAnimatedBackground(v: Boolean) = put { it[K.animatedBg] = v }
    suspend fun setShow24h(v: Boolean) = put { it[K.show24h] = v }
    suspend fun setUnits(v: SpeedUnits) = put { it[K.units] = v.name }
    suspend fun setMapPackage(v: String) = put { it[K.mapPackage] = v }
    suspend fun setNavWindowed(v: Boolean) = put { it[K.navWindowed] = v }
    suspend fun setHomePoint(lat: Double, lon: Double) = put {
        it[K.homeLat] = lat
        it[K.homeLon] = lon
    }

    suspend fun clearHomePoint() = put {
        it[K.homeLat] = Double.NaN
        it[K.homeLon] = Double.NaN
    }

    suspend fun setAutoplay(v: Boolean) = put { it[K.autoplay] = v }
    suspend fun setAutoplayDelay(sec: Int) = put { it[K.autoplayDelay] = sec.coerceIn(0, 60) }
    suspend fun setAutoplaySource(v: MusicSource) = put { it[K.autoplaySource] = v.name }

    suspend fun setNotificationReaction(v: NotificationReaction) = put { it[K.notifReaction] = v.name }
    suspend fun setDuckPercent(v: Int) = put { it[K.duckPercent] = v.coerceIn(5, 90) }
    suspend fun setDuckHold(ms: Int) = put { it[K.duckHold] = ms.coerceIn(500, 15000) }
    suspend fun setOnlyPaired(v: Boolean) = put { it[K.onlyPaired] = v }

    suspend fun setSpeedVolumeEnabled(v: Boolean) = put { it[K.speedVolume] = v }
    suspend fun setSpeedSteps(v: List<SpeedVolumeStep>) = put { it[K.speedSteps] = encodeSteps(v) }
    suspend fun setSpeedSmooth(ms: Int) = put { it[K.speedSmooth] = ms.coerceIn(200, 6000) }

    suspend fun setResumeAfterCall(v: Boolean) = put { it[K.resumeAfterCall] = v }
    suspend fun setResumeDelay(sec: Int) = put { it[K.resumeDelay] = sec.coerceIn(0, 30) }

    suspend fun setSwcEnabled(v: Boolean) = put { it[K.swcEnabled] = v }
    suspend fun setSwcLongMs(v: Int) = put { it[K.swcLongMs] = v.coerceIn(250, 2000) }
    suspend fun setSwcShort(v: Map<Int, SwcAction>) = put { it[K.swcShort] = encodeActions(v) }
    suspend fun setSwcLong(v: Map<Int, SwcAction>) = put { it[K.swcLong] = encodeActions(v) }
    suspend fun setSwcAdcEnabled(v: Boolean) = put { it[K.swcAdc] = v }
    suspend fun setSwcAdcPath(v: String) = put { it[K.swcAdcPath] = v }
    suspend fun setSwcAdcTolerance(v: Int) = put { it[K.swcAdcTol] = v.coerceIn(1, 200) }
    suspend fun setSwcAdcMap(v: Map<Int, SwcAction>) = put { it[K.swcAdcMap] = encodeActions(v) }

    suspend fun setStartOnBoot(v: Boolean) = put { it[K.startOnBoot] = v }
    suspend fun setKeepScreenOn(v: Boolean) = put { it[K.keepScreenOn] = v }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    /* ─────────  Кодирование карт  ───────── */

    private fun encodeSteps(v: List<SpeedVolumeStep>) =
        v.joinToString("|") { "${it.fromKmh}:${it.gain}" }

    private fun decodeSteps(s: String): List<SpeedVolumeStep> = s.split("|")
        .mapNotNull { part ->
            val (a, b) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            val from = a.toIntOrNull() ?: return@mapNotNull null
            val gain = b.toIntOrNull() ?: return@mapNotNull null
            SpeedVolumeStep(from, gain)
        }
        .ifEmpty { Defaults.speedSteps }

    private fun encodeActions(v: Map<Int, SwcAction>) =
        v.entries.joinToString("|") { "${it.key}:${it.value.name}" }

    private fun decodeActions(s: String): Map<Int, SwcAction> = s.split("|")
        .mapNotNull { part ->
            val bits = part.split(":")
            if (bits.size != 2) return@mapNotNull null
            val code = bits[0].toIntOrNull() ?: return@mapNotNull null
            val action = runCatching { SwcAction.valueOf(bits[1]) }.getOrNull()
                ?: return@mapNotNull null
            code to action
        }
        .toMap()
}
