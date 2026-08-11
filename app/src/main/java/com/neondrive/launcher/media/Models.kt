package com.neondrive.launcher.media

import android.net.Uri

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val albumArtUri: Uri?
) {
    val subtitle: String get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
}

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val genre: String = "",
    val builtIn: Boolean = true
)

/** Обычная FM-станция, принимаемая через антенну ГУ, а не по интернету. */
data class FmStation(
    val frequencyKHz: Int,
    val name: String
) {
    val mhz: Float get() = frequencyKHz / 1000f
    val label: String get() = name.ifBlank { "%.1f МГц".format(mhz) }
}

/** То, что видит UI, независимо от источника (свой ExoPlayer или чужая MediaSession). */
data class NowPlaying(
    val title: String = "Нет воспроизведения",
    val subtitle: String = "",
    val artUri: Uri? = null,
    val artBitmapKey: Long = 0L,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sourceLabel: String = ""
)

object RadioPresets {
    val default = listOf(
        // Radio Record (Dance) убран: поток rr96.aacp не отвечает, станция в
        // пресетах только мешала — пользователь жал и получал тишину.
        RadioStation("record_synth", "Record Synthwave", "https://radiorecord.hostingradio.ru/synth96.aacp", "Synthwave"),
        RadioStation("record_darkside", "Record Darkside", "https://radiorecord.hostingradio.ru/darkside96.aacp", "Chill"),
        RadioStation("energy", "Energy", "https://pub0302.101.ru:8443/stream/air/aac/64/99", "Pop"),
        RadioStation("nashe", "Наше Радио", "https://nashe1.hostingradio.ru/nashe-128.mp3", "Рок"),
        RadioStation("maximum", "Максимум", "https://maximum.hostingradio.ru/maximum96.aacp", "Рок"),
        RadioStation("jazz", "Радио Jazz", "https://nashe1.hostingradio.ru/jazz-128.mp3", "Jazz"),
        RadioStation("dfm", "DFM", "https://dfm.hostingradio.ru/dfm96.aacp", "Dance")
    )
}
