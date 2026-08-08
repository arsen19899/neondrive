package com.neondrive.launcher.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.ui.common.NeonSegmented
import com.neondrive.launcher.ui.common.NeonScreenScaffold
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import kotlinx.coroutines.launch

@Composable
fun MusicScreen(accent: Color, accent2: Color, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val tracks by PlayerHub.tracks.collectAsState()
    val stations by PlayerHub.stations.collectAsState()
    val now by PlayerHub.now.collectAsState()
    val source by PlayerHub.source.collectAsState()
    var tab by remember { mutableStateOf(source) }

    NeonScreenScaffold(
        title = "Музыка",
        subtitle = when (tab) {
            MusicSource.DEVICE -> "${tracks.size} треков на устройстве"
            MusicSource.RADIO -> "${stations.size} станций"
            MusicSource.YANDEX -> "Управление приложением Яндекс.Музыка"
        },
        accent = accent,
        onBack = onBack,
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeonSegmented(
                    options = MusicSource.entries.toList(),
                    selected = tab,
                    label = { it.label },
                    accent = accent,
                    onSelect = { tab = it }
                )
                Spacer(Modifier.size(10.dp))
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0x660C1424))
                        .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .clickable { scope.launch { PlayerHub.refreshLibrary() } },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Refresh, "Обновить", tint = accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    ) {
        when (tab) {
            MusicSource.DEVICE -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(tracks, key = { _, t -> t.uri.toString() }) { index, track ->
                    MediaRow(
                        title = track.title,
                        subtitle = track.subtitle,
                        meta = formatDuration(track.durationMs),
                        active = now.title == track.title && source == MusicSource.DEVICE,
                        accent = accent,
                        accent2 = accent2,
                        icon = Icons.Rounded.MusicNote
                    ) { PlayerHub.playTracks(tracks, index) }
                }
                if (tracks.isEmpty()) {
                    item { EmptyHint("Аудиофайлы не найдены. Проверьте карту памяти или USB-накопитель.") }
                }
            }

            MusicSource.RADIO -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(stations, key = { _, s -> s.id }) { _, st ->
                    MediaRow(
                        title = st.name,
                        subtitle = st.genre,
                        meta = if (st.builtIn) "PRESET" else "СВОЯ",
                        active = now.title == st.name && source == MusicSource.RADIO,
                        accent = accent,
                        accent2 = accent2,
                        icon = Icons.Rounded.Radio
                    ) { PlayerHub.playStation(st) }
                }
            }

            MusicSource.YANDEX -> Column(Modifier.fillMaxSize()) {
                val available by PlayerHub.external.available.collectAsState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x550C1424))
                        .border(1.dp, accent2.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                        .padding(20.dp)
                ) {
                    Column {
                        Text(
                            if (available) "Яндекс.Музыка подключена"
                            else "Яндекс.Музыка не активна",
                            color = if (available) accent2 else Neon.TextMid,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Оболочка управляет приложением через медиасессию: плей, пауза, " +
                                "переключение треков и кнопки руля работают, не выходя с рабочего стола. " +
                                "Нужно разрешение «Доступ к уведомлениям» — оно же используется для " +
                                "реакции на уведомления телефона.",
                            color = Neon.TextLow,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionChip("Подключиться и играть", accent2) {
                                PlayerHub.switchToYandex(launchApp = true, autoPlay = true)
                            }
                            ActionChip("Открыть приложение", accent) {
                                PlayerHub.openYandexMusic()
                            }
                            if (!PlayerHub.external.hasAccess()) {
                                ActionChip("Выдать доступ", Neon.Red) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings
                                                    .ACTION_NOTIFICATION_LISTENER_SETTINGS
                                            ).addFlags(
                                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    subtitle: String,
    meta: String,
    active: Boolean,
    accent: Color,
    accent2: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (active) Modifier.neonGlow(accent2, 14.dp, 0.2f, 8.dp) else Modifier)
            .background(if (active) accent2.copy(alpha = 0.12f) else Color(0x330C1424))
            .border(
                1.dp,
                if (active) accent2.copy(alpha = 0.6f) else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (active) Icons.Rounded.GraphicEq else icon, null,
            tint = if (active) accent2 else accent.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (active) Neon.TextHi else Neon.TextMid,
                fontSize = 15.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Neon.TextLow, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(meta, color = Neon.TextLow, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ActionChip(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Neon.TextLow, fontSize = 14.sp)
    }
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
