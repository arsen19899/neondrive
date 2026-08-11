package com.neondrive.launcher.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.media.NowPlaying
import com.neondrive.launcher.ui.common.HudLabel
import com.neondrive.launcher.ui.common.NeonCard
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow

/**
 * Плеер рабочего стола. Три источника переключаются прямо здесь,
 * длинное касание обложки открывает полный список треков и станций.
 */
@Composable
fun PlayerPanel(
    now: NowPlaying,
    source: MusicSource,
    accent: Color,
    accent2: Color,
    volumePercent: Int,
    modifier: Modifier = Modifier,
    liked: Boolean? = null,
    canLike: Boolean = false,
    connecting: Boolean = false,
    onLike: () -> Unit = {},
    onSource: (MusicSource) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onVolume: (Boolean) -> Unit,
    onOpenLibrary: () -> Unit
) {
    NeonCard(modifier = modifier, accent = accent2) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Панель живёт в колонке произвольной ширины — от компактных ГУ 7"
            // до широких приборных экранов.
            //
            // Ниже 270 dp горизонтальная раскладка перестаёт работать в принципе:
            // обложка и текст делят и без того узкую строку, название трека
            // ужимается до трёх букв с многоточием, а три вкладки источника с
            // подписями «С устройства» и «Яндекс.Музыка» превращаются в кашу.
            // Такая ширина получается, когда под навигацию отдана большая доля
            // экрана (две трети и выше) — то есть в самом востребованном режиме.
            // Поэтому там рисуется отдельная вертикальная раскладка, где каждому
            // элементу достаётся вся ширина колонки. Всё, что шире, рисуется ровно
            // как раньше.
            if (maxWidth < 270.dp) {
                CompactPlayerBody(
                    now = now, source = source, accent2 = accent2,
                    liked = liked, canLike = canLike, connecting = connecting,
                    onLike = onLike, onSource = onSource, onPlayPause = onPlayPause,
                    onNext = onNext, onPrev = onPrev, onOpenLibrary = onOpenLibrary
                )
                return@BoxWithConstraints
            }

            val coverSize = 72.dp
            val titleSize = 15.sp
            val subtitleSize = 12.sp
            val likeSize = 40.dp
            val transportSize = 44.dp
            val playSize = 56.dp

            Column(Modifier.fillMaxWidth()) {

                // Переключатель источника
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MusicSource.entries.forEach { s ->
                        val sel = s == source
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (sel) accent2.copy(alpha = 0.20f) else Color(0x330C1424))
                                .border(
                                    1.dp,
                                    if (sel) accent2.copy(alpha = 0.7f) else Color.Transparent,
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable { onSource(s) }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                s.label,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (sel) accent2 else Neon.TextLow,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Обложка
                    Box(
                        Modifier
                            .size(coverSize)
                            .neonGlow(accent2, 14.dp, if (now.isPlaying) 0.35f else 0.12f, 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0C1424))
                            .border(1.dp, accent2.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable { onOpenLibrary() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (now.artUri != null) {
                            AsyncImage(
                                model = now.artUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(coverSize)
                            )
                        } else {
                            Icon(
                                Icons.Rounded.LibraryMusic, null,
                                tint = accent2.copy(alpha = 0.55f),
                                modifier = Modifier.size(coverSize * 0.42f)
                            )
                        }
                    }

                    Spacer(Modifier.padding(horizontal = 6.dp))

                    Column(Modifier.weight(1f)) {
                        HudLabel(
                            if (connecting) "Подключение…" else now.sourceLabel.ifBlank { source.label },
                            accent2
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            now.title,
                            color = Neon.TextHi,
                            fontSize = titleSize,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            now.subtitle,
                            color = Neon.TextLow,
                            fontSize = subtitleSize,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 14.sp
                        )
                    }

                    // Лайк доступен, когда его поддерживает сессия стороннего приложения
                    if (canLike) {
                        Box(
                            Modifier
                                .size(likeSize)
                                .then(
                                    if (liked == true) Modifier.neonGlow(Neon.Magenta, likeSize / 2, 0.4f, 8.dp)
                                    else Modifier
                                )
                                .clip(RoundedCornerShape(likeSize / 2))
                                .background(
                                    if (liked == true) Neon.Magenta.copy(alpha = 0.2f) else Color(0x550C1424)
                                )
                                .border(
                                    1.dp,
                                    if (liked == true) Neon.Magenta.copy(alpha = 0.85f)
                                    else Neon.TextLow.copy(alpha = 0.35f),
                                    RoundedCornerShape(likeSize / 2)
                                )
                                .clickable(onClick = onLike),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (liked == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                "Нравится",
                                tint = if (liked == true) Neon.Magenta else Neon.TextMid,
                                modifier = Modifier.size(likeSize * 0.5f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Эквалайзерная «полоска» вместо скучного прогресс-бара, когда играет радио
                if (now.durationMs > 0) {
                    ProgressLine(
                        fraction = (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f),
                        accent = accent2
                    )
                } else {
                    SpectrumLine(active = now.isPlaying, accent = accent2)
                }

                // Карточка часто выше, чем нужно её содержимому (плеер занимает всё
                // свободное место в колонке) — гибкий отступ прижимает транспорт
                // книзу, а не оставляет пустоту под спектром.
                Spacer(Modifier.weight(1f).heightIn(min = 10.dp))

                // Громкость регулируется физическими кнопками магнитолы — здесь только транспорт
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundBtn(Icons.Rounded.SkipPrevious, accent2, transportSize, onClick = onPrev)
                    RoundBtn(
                        if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        accent2, playSize, filled = true, onClick = onPlayPause
                    )
                    RoundBtn(Icons.Rounded.SkipNext, accent2, transportSize, onClick = onNext)
                }
            }
        }
    }
}

/**
 * Плеер для узкой колонки — когда под навигацию отдано две трети экрана и больше.
 *
 * Принцип другой, чем в основной раскладке: ничего не делит строку по горизонтали.
 * Обложка занимает всю ширину карточкой, под ней название во всю ширину в две
 * строки, ещё ниже транспорт. В таком порядке даже в 200 dp всё читается, тогда
 * как ужимание горизонтальной раскладки давало обрезанные подписи и кнопки,
 * в которые невозможно попасть на ходу.
 *
 * Подписи источников тоже короткие: «С устройства» и «Яндекс.Музыка» в треть
 * колонки не помещаются ни при каком кегле.
 */
@Composable
private fun CompactPlayerBody(
    now: NowPlaying,
    source: MusicSource,
    accent2: Color,
    liked: Boolean?,
    canLike: Boolean,
    connecting: Boolean,
    onLike: () -> Unit,
    onSource: (MusicSource) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MusicSource.entries.forEach { s ->
                val sel = s == source
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (sel) accent2.copy(alpha = 0.20f) else Color(0x330C1424))
                        .border(
                            1.dp,
                            if (sel) accent2.copy(alpha = 0.7f) else Color.Transparent,
                            RoundedCornerShape(9.dp)
                        )
                        .clickable { onSource(s) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        shortSourceLabel(s),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (sel) accent2 else Neon.TextLow,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Обложка во всю ширину колонки: на узкой панели это единственное место,
        // где она вообще имеет смысл — сбоку от текста она съедала бы половину
        // строки и не давала прочитать ни название, ни исполнителя.
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .neonGlow(accent2, 14.dp, if (now.isPlaying) 0.30f else 0.10f, 10.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0C1424))
                .border(1.dp, accent2.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .clickable { onOpenLibrary() },
            contentAlignment = Alignment.Center
        ) {
            if (now.artUri != null) {
                AsyncImage(
                    model = now.artUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(96.dp)
                )
            } else {
                Icon(
                    Icons.Rounded.LibraryMusic, null,
                    tint = accent2.copy(alpha = 0.55f),
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        HudLabel(
            if (connecting) "Подключение…" else now.sourceLabel.ifBlank { source.label },
            accent2
        )
        Spacer(Modifier.height(3.dp))
        Text(
            now.title,
            color = Neon.TextHi,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp
        )
        Text(
            now.subtitle,
            color = Neon.TextLow,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(8.dp))

        if (now.durationMs > 0) {
            ProgressLine(
                fraction = (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f),
                accent = accent2
            )
        } else {
            SpectrumLine(active = now.isPlaying, accent = accent2)
        }

        Spacer(Modifier.weight(1f).heightIn(min = 8.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundBtn(Icons.Rounded.SkipPrevious, accent2, 40.dp, onClick = onPrev)
            RoundBtn(
                if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                accent2, 52.dp, filled = true, onClick = onPlayPause
            )
            RoundBtn(Icons.Rounded.SkipNext, accent2, 40.dp, onClick = onNext)
            if (canLike) {
                RoundBtn(
                    if (liked == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (liked == true) Neon.Magenta else Neon.TextMid,
                    40.dp,
                    filled = liked == true,
                    onClick = onLike
                )
            }
        }
    }
}

/** Короткие подписи источников — длинные в треть узкой колонки не помещаются. */
private fun shortSourceLabel(source: MusicSource): String = when (source) {
    MusicSource.DEVICE -> "Файлы"
    MusicSource.RADIO -> "Радио"
    MusicSource.YANDEX -> "Я.Музыка"
}

@Composable
private fun RoundBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .neonGlow(color, size / 2, if (filled) 0.4f else 0.14f, 8.dp)
            .clip(RoundedCornerShape(size / 2))
            .background(if (filled) color.copy(alpha = 0.22f) else Color(0x550C1424))
            .border(1.dp, color.copy(alpha = if (filled) 0.85f else 0.3f), RoundedCornerShape(size / 2))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun ProgressLine(fraction: Float, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(accent.copy(alpha = 0.14f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.6f), accent)))
        )
    }
}

/**
 * Живая «гребёнка» спектра — чисто декоративная, но оживляет радио.
 * Анимация запускается только пока играет музыка: бесконечный transition сам по
 * себе гоняет перерисовку на 60 fps вечно, даже показывая статичную линию —
 * на слабом ГУ это заметная лишняя нагрузка вхолостую.
 */
@Composable
private fun SpectrumLine(active: Boolean, accent: Color) {
    val phase = if (active) {
        val tr = rememberInfiniteTransition(label = "spectrum")
        val p by tr.animateFloat(
            0f, (Math.PI * 2).toFloat(),
            infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "phase"
        )
        p
    } else 0f
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
    ) {
        val bars = 34
        val gap = 3f
        val w = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val k = if (active) {
                (kotlin.math.sin(phase + i * 0.5f) * 0.5f + 0.5f) *
                    (kotlin.math.sin(phase * 0.7f + i * 0.21f) * 0.4f + 0.6f)
            } else 0.08f
            val h = (size.height * (0.15f + 0.85f * k)).coerceAtLeast(2f)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(accent, accent.copy(alpha = 0.25f)),
                    startY = size.height - h,
                    endY = size.height
                ),
                topLeft = Offset(i * (w + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2, w / 2)
            )
        }
    }
}
