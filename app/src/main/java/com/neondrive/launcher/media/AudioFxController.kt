package com.neondrive.launcher.media

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EqBand(val index: Int, val centerHz: Int, val levelMb: Short)

data class EqState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val minLevelMb: Short = -1500,
    val maxLevelMb: Short = 1500,
    val bands: List<EqBand> = emptyList(),
    val presets: List<String> = emptyList(),
    val currentPreset: Int = -1,
    val bassBoost: Int = 0,     // 0..1000
    val virtualizer: Int = 0,   // 0..1000
    val loudness: Int = 0       // мБ, 0..1500
)

/**
 * Обёртка над системными аудиоэффектами. Работает поверх аудиосессии нашего ExoPlayer;
 * если ГУ разрешает глобальную сессию (0), эффекты применяются и к сторонним плеерам
 * вроде Яндекс.Музыки.
 *
 * Важный нюанс системных `android.media.audiofx.*`: они НЕ хранят состояние сами —
 * каждый раз, когда создаётся новый `Equalizer`/`BassBoost`/... (а это происходит и при
 * перезапуске приложения, и при каждой смене audioSessionId — например, когда ExoPlayer
 * пересоздаёт сессию при переключении трека), эффект возвращается к состоянию по
 * умолчанию (обычно плоская АЧХ, всё выключено). Поэтому весь пользовательский выбор —
 * полосы, пресет, бас, объём, громкость, вкл/выкл — дублируется в свои SharedPreferences
 * и переигрывается заново на каждый новый экземпляр эффекта в [attach]. Без этого
 * настройки эквалайзера «слетали» при каждом перезапуске оболочки или смене трека.
 */
object AudioFxController {

    private const val PREFS_NAME = "neondrive_eq"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BANDS = "bands"          // "idx:levelMb|idx:levelMb|..."
    private const val KEY_PRESET = "preset"        // -1, если полосы выставлены вручную
    private const val KEY_BASS = "bass"
    private const val KEY_VIRT = "virt"
    private const val KEY_LOUD = "loud"

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state

    /** Инициализация хранилища настроек — безопасно звать многократно. */
    private fun ensurePrefs(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun attach(context: Context, audioSessionId: Int) {
        ensurePrefs(context)
        if (audioSessionId == sessionId && eq != null) return
        release()
        sessionId = audioSessionId
        val savedEnabled = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        // Каждый эффект создаётся в своём runCatching. На магнитолах со внешним
        // DSP (Unisoc/MTK с YD7388, TDA7388 и прочими) часть эффектов в прошивке
        // просто не реализована, и конструктор бросает исключение. Раньше все
        // четыре создавались в одном блоке: падение BassBoost обрывало блок, и
        // эквалайзер оставался без Virtualizer и LoudnessEnhancer, хотя сам
        // прекрасно работал. Теперь отсутствие одного эффекта не тянет за собой
        // остальные — доступно ровно то, что реально поддерживает железо.
        runCatching { eq = Equalizer(0, audioSessionId).apply { enabled = savedEnabled } }
        runCatching { bass = BassBoost(0, audioSessionId).apply { enabled = true } }
        runCatching { virt = Virtualizer(0, audioSessionId).apply { enabled = true } }
        runCatching { loud = LoudnessEnhancer(audioSessionId).apply { enabled = true } }
        applySavedValues()
        refresh()
    }

    @Synchronized
    fun release() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        runCatching { virt?.release() }
        runCatching { loud?.release() }
        eq = null; bass = null; virt = null; loud = null
    }

    /**
     * Переиграть сохранённое состояние на свежесозданные объекты эффектов.
     * Пресет приоритетнее сырых полос: если он был выбран последним, применяем
     * его заново через usePreset (это сама расставит полосы), иначе — ручные уровни.
     */
    private fun applySavedValues() {
        val p = prefs ?: return

        val preset = p.getInt(KEY_PRESET, -1)
        if (preset >= 0) {
            runCatching { eq?.usePreset(preset.toShort()) }
        } else {
            val bandsStr = p.getString(KEY_BANDS, "").orEmpty()
            bandsStr.split("|").forEach { part ->
                if (part.isBlank()) return@forEach
                val bits = part.split(":")
                if (bits.size != 2) return@forEach
                val idx = bits[0].toIntOrNull() ?: return@forEach
                val lvl = bits[1].toIntOrNull() ?: return@forEach
                runCatching { eq?.setBandLevel(idx.toShort(), lvl.toShort()) }
            }
        }

        runCatching { bass?.setStrength(p.getInt(KEY_BASS, 0).coerceIn(0, 1000).toShort()) }
        runCatching { virt?.setStrength(p.getInt(KEY_VIRT, 0).coerceIn(0, 1000).toShort()) }
        runCatching { loud?.setTargetGain(p.getInt(KEY_LOUD, 0).coerceIn(0, 1500)) }

        _state.value = _state.value.copy(currentPreset = preset)
    }

    fun refresh() {
        val e = eq
        if (e == null) {
            _state.value = EqState(available = false)
            return
        }
        runCatching {
            val range = e.bandLevelRange
            val bands = (0 until e.numberOfBands.toInt()).map { i ->
                EqBand(
                    index = i,
                    centerHz = e.getCenterFreq(i.toShort()) / 1000,
                    levelMb = e.getBandLevel(i.toShort())
                )
            }
            val presets = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) }
            _state.value = _state.value.copy(
                available = true,
                enabled = e.enabled,
                minLevelMb = range[0],
                maxLevelMb = range[1],
                bands = bands,
                presets = presets,
                bassBoost = bass?.roundedStrength?.toInt() ?: 0,
                virtualizer = virt?.roundedStrength?.toInt() ?: 0,
                loudness = loud?.targetGain?.toInt() ?: 0
            )
        }.onFailure { _state.value = EqState(available = false) }
    }

    fun setEnabled(on: Boolean) {
        runCatching { eq?.enabled = on }
        _state.value = _state.value.copy(enabled = on)
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }

    fun setBand(index: Int, levelMb: Short) {
        runCatching { eq?.setBandLevel(index.toShort(), levelMb) }
        val bands = _state.value.bands.map { if (it.index == index) it.copy(levelMb = levelMb) else it }
        _state.value = _state.value.copy(bands = bands, currentPreset = -1)
        prefs?.edit()
            ?.putString(KEY_BANDS, encodeBands(bands))
            ?.putInt(KEY_PRESET, -1)
            ?.apply()
    }

    fun applyPreset(preset: Int) {
        runCatching { eq?.usePreset(preset.toShort()) }
        refresh()
        _state.value = _state.value.copy(currentPreset = preset)
        prefs?.edit()
            ?.putInt(KEY_PRESET, preset)
            ?.putString(KEY_BANDS, encodeBands(_state.value.bands))
            ?.apply()
    }

    fun setBassBoost(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        runCatching { bass?.setStrength(s.toShort()) }
        _state.value = _state.value.copy(bassBoost = s)
        prefs?.edit()?.putInt(KEY_BASS, s)?.apply()
    }

    fun setVirtualizer(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        runCatching { virt?.setStrength(s.toShort()) }
        _state.value = _state.value.copy(virtualizer = s)
        prefs?.edit()?.putInt(KEY_VIRT, s)?.apply()
    }

    fun setLoudness(gainMb: Int) {
        val g = gainMb.coerceIn(0, 1500)
        runCatching { loud?.setTargetGain(g) }
        _state.value = _state.value.copy(loudness = g)
        prefs?.edit()?.putInt(KEY_LOUD, g)?.apply()
    }

    fun flatten() {
        val e = eq ?: return
        runCatching {
            for (i in 0 until e.numberOfBands.toInt()) e.setBandLevel(i.toShort(), 0)
        }
        refresh()
        _state.value = _state.value.copy(currentPreset = -1)
        prefs?.edit()
            ?.putInt(KEY_PRESET, -1)
            ?.putString(KEY_BANDS, encodeBands(_state.value.bands))
            ?.apply()
    }

    private fun encodeBands(bands: List<EqBand>): String =
        bands.joinToString("|") { "${it.index}:${it.levelMb}" }
}
