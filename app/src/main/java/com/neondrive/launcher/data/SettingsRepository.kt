package com.neondrive.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.neondrive.launcher.media.FmStation
import com.neondrive.launcher.media.RadioStation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("neondrive")

/**
 * Единственный источник правды по настройкам оболочки.
 * Карты (кнопки руля, ступени скорости) хранятся строкой вида "a:b|c:d" —
 * без лишних зависимостей на JSON-библиотеки, что важно для слабых ГУ.
 */
class SettingsRepository(private val context: Context) {

    /**
     * Заводское состояние декоративной графики зависит от железа ГУ: на слабом
     * устройстве анимированный фон выключен, а «упрощённая графика» включена
     * сразу — иначе первое впечатление от оболочки на бюджетной магнитоле это
     * рывки при скролле. Значение, которое пользователь выставил сам, лежит в
     * DataStore и всегда перекрывает автоопределение — ниже оно читается первым.
     */
    private val lowEndDevice: Boolean by lazy {
        com.neondrive.launcher.system.DeviceProfile.isLowEnd(context)
    }

    private object K {
        val accent = stringPreferencesKey("accent")
        val sidebarSide = stringPreferencesKey("sidebar_side")
        val animatedBg = booleanPreferencesKey("animated_bg")
        val show24h = booleanPreferencesKey("show_24h")
        val units = stringPreferencesKey("units")
        val mapPackage = stringPreferencesKey("map_package")
        val showSpeedometer = booleanPreferencesKey("show_speedometer")
        val reducedEffects = booleanPreferencesKey("reduced_effects")
        val backgroundImagePath = stringPreferencesKey("background_image_path")
        val backgroundDarken = floatPreferencesKey("background_darken")
        val extraMusicFolders = stringPreferencesKey("extra_music_folders")
        val customStations = stringPreferencesKey("custom_stations")
        val radioMode = stringPreferencesKey("radio_mode")
        val fmStations = stringPreferencesKey("fm_stations")
        val mapMode = stringPreferencesKey("map_mode")
        val mapSide = stringPreferencesKey("map_side")
        val mapScreenPercent = intPreferencesKey("map_screen_percent")
        val navVoice = booleanPreferencesKey("nav_voice")
        val navVoiceVolume = intPreferencesKey("nav_voice_volume")
        val navDuckMusic = booleanPreferencesKey("nav_duck_music")
        val navCameraWarn = booleanPreferencesKey("nav_camera_warn")
        val navSpeedLimitWarn = booleanPreferencesKey("nav_speed_limit_warn")
        val navSpeedTolerance = intPreferencesKey("nav_speed_tolerance")
        val navRotateMap = booleanPreferencesKey("nav_rotate_map")
        val navAutoZoom = booleanPreferencesKey("nav_auto_zoom")
        val navOfflineRouting = booleanPreferencesKey("nav_offline_routing")
        val navFavorites = stringPreferencesKey("nav_favorites")
        val navSearchHistory = stringPreferencesKey("nav_search_history")
        val mapAutoStart = booleanPreferencesKey("map_auto_start")
        val mapAutoStartDelay = intPreferencesKey("map_auto_start_delay")
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

        val phoneBluetoothAddress = stringPreferencesKey("phone_bt_address")

        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val startOnScreenOn = booleanPreferencesKey("start_on_screen_on")
        val beDefaultLauncher = booleanPreferencesKey("be_default_launcher")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<LauncherSettings> = context.dataStore.data.map { p ->
        val d = LauncherSettings()
        LauncherSettings(
            accent = p[K.accent] ?: d.accent,
            sidebarSide = p[K.sidebarSide]?.let { runCatching { SidebarSide.valueOf(it) }.getOrNull() }
                ?: d.sidebarSide,
            animatedBackground = p[K.animatedBg] ?: (d.animatedBackground && !lowEndDevice),
            show24h = p[K.show24h] ?: d.show24h,
            units = p[K.units]?.let { runCatching { SpeedUnits.valueOf(it) }.getOrNull() } ?: d.units,
            mapPackage = p[K.mapPackage] ?: d.mapPackage,
            showSpeedometer = p[K.showSpeedometer] ?: d.showSpeedometer,
            reducedEffects = p[K.reducedEffects] ?: (d.reducedEffects || lowEndDevice),
            backgroundImagePath = p[K.backgroundImagePath] ?: d.backgroundImagePath,
            backgroundDarken = p[K.backgroundDarken] ?: d.backgroundDarken,
            extraMusicFolders = p[K.extraMusicFolders]?.let(::decodeStringList) ?: d.extraMusicFolders,
            customStations = p[K.customStations]?.let(::decodeStations) ?: d.customStations,
            radioMode = p[K.radioMode]?.let { runCatching { RadioMode.valueOf(it) }.getOrNull() }
                ?: d.radioMode,
            fmStations = p[K.fmStations]?.let(::decodeFm) ?: d.fmStations,
            mapMode = p[K.mapMode]?.let { runCatching { MapMode.valueOf(it) }.getOrNull() }
                ?: d.mapMode,
            mapSide = p[K.mapSide]?.let { runCatching { SidebarSide.valueOf(it) }.getOrNull() }
                ?: d.mapSide,
            mapScreenPercent = (p[K.mapScreenPercent] ?: d.mapScreenPercent).coerceIn(30, 80),
            navVoice = p[K.navVoice] ?: d.navVoice,
            navVoiceVolume = (p[K.navVoiceVolume] ?: d.navVoiceVolume).coerceIn(10, 100),
            navDuckMusic = p[K.navDuckMusic] ?: d.navDuckMusic,
            navCameraWarn = p[K.navCameraWarn] ?: d.navCameraWarn,
            navSpeedLimitWarn = p[K.navSpeedLimitWarn] ?: d.navSpeedLimitWarn,
            navSpeedTolerance = (p[K.navSpeedTolerance] ?: d.navSpeedTolerance).coerceIn(0, 30),
            navRotateMap = p[K.navRotateMap] ?: d.navRotateMap,
            navAutoZoom = p[K.navAutoZoom] ?: d.navAutoZoom,
            navOfflineRouting = p[K.navOfflineRouting] ?: d.navOfflineRouting,
            navFavorites = p[K.navFavorites]?.let(::decodeFavorites) ?: d.navFavorites,
            navSearchHistory = p[K.navSearchHistory]?.let(::decodeStringList) ?: d.navSearchHistory,
            mapAutoStart = p[K.mapAutoStart] ?: d.mapAutoStart,
            mapAutoStartDelaySec = p[K.mapAutoStartDelay] ?: d.mapAutoStartDelaySec,
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

            phoneBluetoothAddress = p[K.phoneBluetoothAddress] ?: d.phoneBluetoothAddress,

            startOnBoot = p[K.startOnBoot] ?: d.startOnBoot,
            startOnScreenOn = p[K.startOnScreenOn] ?: d.startOnScreenOn,
            beDefaultLauncher = p[K.beDefaultLauncher] ?: d.beDefaultLauncher,
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
    suspend fun setShowSpeedometer(v: Boolean) = put { it[K.showSpeedometer] = v }
    suspend fun setReducedEffects(v: Boolean) = put { it[K.reducedEffects] = v }
    suspend fun setBackgroundImagePath(v: String) = put { it[K.backgroundImagePath] = v }
    suspend fun setBackgroundDarken(v: Float) = put { it[K.backgroundDarken] = v.coerceIn(0f, 0.92f) }
    suspend fun setExtraMusicFolders(v: List<String>) = put { it[K.extraMusicFolders] = encodeStringList(v) }
    suspend fun setCustomStations(v: List<RadioStation>) = put { it[K.customStations] = encodeStations(v) }
    suspend fun setRadioMode(v: RadioMode) = put { it[K.radioMode] = v.name }
    suspend fun setFmStations(v: List<FmStation>) = put { it[K.fmStations] = encodeFm(v) }
    suspend fun setMapMode(v: MapMode) = put { it[K.mapMode] = v.name }
    suspend fun setMapSide(v: SidebarSide) = put { it[K.mapSide] = v.name }
    suspend fun setMapScreenPercent(v: Int) = put { it[K.mapScreenPercent] = v.coerceIn(30, 80) }
    suspend fun setNavVoice(v: Boolean) = put { it[K.navVoice] = v }
    suspend fun setNavVoiceVolume(v: Int) = put { it[K.navVoiceVolume] = v.coerceIn(10, 100) }
    suspend fun setNavDuckMusic(v: Boolean) = put { it[K.navDuckMusic] = v }
    suspend fun setNavCameraWarn(v: Boolean) = put { it[K.navCameraWarn] = v }
    suspend fun setNavSpeedLimitWarn(v: Boolean) = put { it[K.navSpeedLimitWarn] = v }
    suspend fun setNavSpeedTolerance(v: Int) = put { it[K.navSpeedTolerance] = v.coerceIn(0, 30) }
    suspend fun setNavRotateMap(v: Boolean) = put { it[K.navRotateMap] = v }
    suspend fun setNavAutoZoom(v: Boolean) = put { it[K.navAutoZoom] = v }
    suspend fun setNavOfflineRouting(v: Boolean) = put { it[K.navOfflineRouting] = v }
    suspend fun setNavFavorites(v: List<FavoritePlace>) = put {
        it[K.navFavorites] = encodeFavorites(v)
    }

    /**
     * История поиска: новый запрос уходит вверх, дубли схлопываются, длина
     * ограничена десятью — на экране ГУ больше всё равно не пролистать за рулём.
     */
    suspend fun pushSearchHistory(query: String) {
        val q = query.trim()
        if (q.length < 3) return
        put { prefs ->
            val old = prefs[K.navSearchHistory]?.let(::decodeStringList) ?: emptyList()
            val next = (listOf(q) + old.filter { !it.equals(q, ignoreCase = true) }).take(10)
            prefs[K.navSearchHistory] = encodeStringList(next)
        }
    }
    suspend fun setMapAutoStart(v: Boolean) = put { it[K.mapAutoStart] = v }
    suspend fun setMapAutoStartDelay(sec: Int) = put {
        it[K.mapAutoStartDelay] = sec.coerceIn(0, 60)
    }
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

    suspend fun setPhoneBluetoothAddress(v: String) = put { it[K.phoneBluetoothAddress] = v }

    suspend fun setStartOnBoot(v: Boolean) = put { it[K.startOnBoot] = v }
    suspend fun setStartOnScreenOn(v: Boolean) = put { it[K.startOnScreenOn] = v }
    suspend fun setBeDefaultLauncher(v: Boolean) = put { it[K.beDefaultLauncher] = v }
    suspend fun setKeepScreenOn(v: Boolean) = put { it[K.keepScreenOn] = v }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    /* ─────────  Кодирование карт  ───────── */

    // Составные ASCII-метки вместо "|"/":" — те изредка встречаются внутри самих
    // ссылок на поток или названий станций, а такое сочетание символов — никогда.
    private fun encodeStations(v: List<RadioStation>) = v.filterNot { it.builtIn }
        .joinToString("###REC###") {
            listOf(it.id, it.name, it.streamUrl, it.genre).joinToString("###FLD###")
        }

    private fun decodeStations(s: String): List<RadioStation> {
        if (s.isBlank()) return emptyList()
        return s.split("###REC###").mapNotNull { rec ->
            val f = rec.split("###FLD###")
            if (f.size < 3 || f[0].isBlank() || f[2].isBlank()) return@mapNotNull null
            RadioStation(
                id = f[0], name = f[1], streamUrl = f[2],
                genre = f.getOrElse(3) { "" }, builtIn = false
            )
        }
    }

    private fun encodeFm(v: List<FmStation>) = v.joinToString("###REC###") {
        listOf(it.frequencyKHz.toString(), it.name).joinToString("###FLD###")
    }

    private fun decodeFm(s: String): List<FmStation> {
        if (s.isBlank()) return emptyList()
        return s.split("###REC###").mapNotNull { rec ->
            val f = rec.split("###FLD###")
            val freq = f.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            FmStation(frequencyKHz = freq, name = f.getOrElse(1) { "" })
        }
    }

    private fun encodeFavorites(v: List<FavoritePlace>) =
        v.joinToString("###REC###") { "${it.name.replace("###REC###", " ")}|${it.lat}|${it.lon}" }

    private fun decodeFavorites(s: String): List<FavoritePlace> =
        if (s.isBlank()) emptyList() else s.split("###REC###").mapNotNull { rec ->
            val parts = rec.split("|")
            if (parts.size != 3) return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            FavoritePlace(parts[0], lat, lon)
        }

    private fun encodeStringList(v: List<String>) = v.joinToString("###REC###")

    private fun decodeStringList(s: String): List<String> =
        if (s.isBlank()) emptyList() else s.split("###REC###").filter { it.isNotBlank() }

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
