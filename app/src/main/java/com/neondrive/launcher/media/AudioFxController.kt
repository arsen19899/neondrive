package com.neondrive.launcher.media

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
 */
object AudioFxController {

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId && eq != null) return
        release()
        sessionId = audioSessionId
        runCatching {
            eq = Equalizer(0, audioSessionId).apply { enabled = _state.value.enabled }
            bass = BassBoost(0, audioSessionId).apply { enabled = true }
            virt = Virtualizer(0, audioSessionId).apply { enabled = true }
            loud = LoudnessEnhancer(audioSessionId).apply { enabled = true }
        }
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
    }

    fun setBand(index: Int, levelMb: Short) {
        runCatching { eq?.setBandLevel(index.toShort(), levelMb) }
        _state.value = _state.value.copy(
            bands = _state.value.bands.map { if (it.index == index) it.copy(levelMb = levelMb) else it },
            currentPreset = -1
        )
    }

    fun applyPreset(preset: Int) {
        runCatching { eq?.usePreset(preset.toShort()) }
        refresh()
        _state.value = _state.value.copy(currentPreset = preset)
    }

    fun setBassBoost(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        runCatching { bass?.setStrength(s.toShort()) }
        _state.value = _state.value.copy(bassBoost = s)
    }

    fun setVirtualizer(strength: Int) {
        val s = strength.coerceIn(0, 1000)
        runCatching { virt?.setStrength(s.toShort()) }
        _state.value = _state.value.copy(virtualizer = s)
    }

    fun setLoudness(gainMb: Int) {
        val g = gainMb.coerceIn(0, 1500)
        runCatching { loud?.setTargetGain(g) }
        _state.value = _state.value.copy(loudness = g)
    }

    fun flatten() {
        val e = eq ?: return
        runCatching {
            for (i in 0 until e.numberOfBands.toInt()) e.setBandLevel(i.toShort(), 0)
        }
        refresh()
    }
}
