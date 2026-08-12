package com.neondrive.launcher.ui.music

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.RadioMode
import com.neondrive.launcher.media.FmRadioController
import com.neondrive.launcher.media.FmStation
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.media.RadioBrowserApi
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
    val fmStationsTop by PlayerHub.fmStations.collectAsState()
    val radioModeTop by PlayerHub.radioMode.collectAsState()
    val now by PlayerHub.now.collectAsState()
    val source by PlayerHub.source.collectAsState()
    val extraFolders by PlayerHub.extraMusicFolders.collectAsState()
    var tab by remember { mutableStateOf(source) }

    // Резервный путь: если прошивка ГУ не индексирует USB/SD в MediaStore, пользователь
    // указывает папку вручную через системный выбор (SAF) — она пересканируется вместе
    // с обычной библиотекой. Разрешение на чтение берём «навсегда», чтобы папка
    // пережила перезагрузку ГУ.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        scope.launch { PlayerHub.addMusicFolder(uri.toString()) }
    }

    NeonScreenScaffold(
        title = "Музыка",
        subtitle = when (tab) {
            MusicSource.DEVICE -> "${tracks.size} треков на устройстве"
            MusicSource.RADIO -> if (radioModeTop == RadioMode.FM) "${fmStationsTop.size} FM-станций"
                else "${stations.size} станций"
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
                if (tab == MusicSource.DEVICE) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x660C1424))
                            .border(1.dp, accent2.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .clickable { runCatching { pickFolder.launch(null) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.CreateNewFolder, "Добавить папку с USB/SD",
                            tint = accent2, modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                }
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
                if (extraFolders.isNotEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            extraFolders.forEach { folderUri ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0x330C1424))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.Folder, null,
                                        tint = accent2.copy(alpha = 0.8f), modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        runCatching { Uri.parse(folderUri).lastPathSegment }
                                            .getOrNull() ?: folderUri,
                                        color = Neon.TextMid,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        Icons.Rounded.Close, "Убрать папку",
                                        tint = Neon.TextLow,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { scope.launch { PlayerHub.removeMusicFolder(folderUri) } }
                                    )
                                }
                                Spacer(Modifier.size(6.dp))
                            }
                        }
                    }
                }
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
                    item {
                        EmptyHint(
                            "Аудиофайлы не найдены. Проверьте карту памяти или USB-накопитель — " +
                                "либо, если прошивка не видит накопитель сама, добавьте его папку " +
                                "вручную кнопкой «папка» вверху."
                        )
                    }
                }
            }

            MusicSource.RADIO -> {
                var radioTab by remember { mutableStateOf(0) }
                val radioMode by PlayerHub.radioMode.collectAsState()
                val fmStations by PlayerHub.fmStations.collectAsState()

                Column(Modifier.fillMaxSize()) {
                    NeonSegmented(
                        options = RadioMode.entries.toList(),
                        selected = radioMode,
                        label = { it.label },
                        accent = accent,
                        modifier = Modifier.fillMaxWidth()
                    ) { PlayerHub.setRadioMode(it) }

                    Spacer(Modifier.height(10.dp))

                    if (radioMode == RadioMode.FM) {
                        val fmState by FmRadioController.state.collectAsState()
                        if (fmState.factoryAppFound) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ActionChip("Открыть заводское радио", accent2) {
                                    PlayerHub.openFactoryRadioApp()
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            "Настроиться на частоту нужно самой магнитолой — сторонним " +
                                "приложениям Android не даёт управлять тюнером напрямую. " +
                                "Список ниже — памятка со станциями.",
                            color = Neon.TextLow,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(fmStations, key = { it.frequencyKHz }) { st: FmStation ->
                                MediaRow(
                                    title = st.label,
                                    subtitle = "%.1f МГц".format(st.mhz),
                                    meta = "FM",
                                    active = now.title == st.label && source == MusicSource.RADIO &&
                                        radioMode == RadioMode.FM,
                                    accent = accent,
                                    accent2 = accent2,
                                    icon = Icons.Rounded.Radio,
                                    onRemove = { PlayerHub.removeFmStation(st.frequencyKHz) }
                                ) { PlayerHub.playFmStation(st) }
                            }
                            if (fmStations.isEmpty()) {
                                item {
                                    EmptyHint(
                                        "FM-станций нет. Добавьте частоту вручную во вкладке " +
                                            "«Радио» настроек оболочки."
                                    )
                                }
                            }
                        }
                        return@Column
                    }

                    NeonSegmented(
                        options = listOf(0, 1),
                        selected = radioTab,
                        label = { if (it == 0) "Сохранённые" else "Поиск станций" },
                        accent = accent2,
                        modifier = Modifier.fillMaxWidth()
                    ) { radioTab = it }

                    Spacer(Modifier.height(12.dp))

                    if (radioTab == 0) {
                        LazyColumn(
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
                                    icon = Icons.Rounded.Radio,
                                    onRemove = if (!st.builtIn) {
                                        { PlayerHub.removeStation(st.id) }
                                    } else null
                                ) { PlayerHub.playStation(st) }
                            }
                            if (stations.isEmpty()) {
                                item { EmptyHint("Станций нет. Найдите и сохраните их во вкладке «Поиск станций».") }
                            }
                        }
                    } else {
                        RadioSearchTab(accent, accent2)
                    }
                }
            }

            MusicSource.YANDEX -> Column(Modifier.fillMaxSize()) {
                val available by PlayerHub.external.available.collectAsState()
                val hasAccess = PlayerHub.external.hasAccess()
                val connecting by PlayerHub.connectingYandex.collectAsState()
                val liked by PlayerHub.external.liked.collectAsState()
                val canLike by PlayerHub.external.canLike.collectAsState()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0x550C1424))
                        .border(1.dp, accent2.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                        .padding(20.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        connecting -> "Подключение…"
                                        available -> "Яндекс.Музыка подключена"
                                        else -> "Яндекс.Музыка не активна"
                                    },
                                    color = if (available) accent2 else Neon.TextMid,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (available) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        listOf(now.title, now.subtitle).filter { it.isNotBlank() }
                                            .joinToString(" · "),
                                        color = Neon.TextLow,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (canLike) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(19.dp))
                                        .background(
                                            if (liked == true) Neon.Magenta.copy(alpha = 0.2f)
                                            else Color(0x550C1424)
                                        )
                                        .border(
                                            1.dp,
                                            if (liked == true) Neon.Magenta.copy(alpha = 0.85f)
                                            else Neon.TextLow.copy(alpha = 0.35f),
                                            RoundedCornerShape(19.dp)
                                        )
                                        .clickable { PlayerHub.toggleLike() },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (liked == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        "Нравится",
                                        tint = if (liked == true) Neon.Magenta else Neon.TextMid,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Оболочка управляет приложением через медиасессию: плей, пауза, " +
                                "переключение треков, лайк и кнопки руля работают, не выходя с рабочего " +
                                "стола. Нужно разрешение «Доступ к уведомлениям» — оно же используется " +
                                "для реакции на уведомления телефона.",
                            color = Neon.TextLow,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        if (!hasAccess) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Доступ к уведомлениям не выдан — без него плеер не увидит Яндекс.Музыку, " +
                                    "даже если приложение запущено и играет.",
                                color = Neon.Amber,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        } else if (!available && !connecting) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Доступ есть, но сессия не найдена. Нажмите «Подключиться и играть» — " +
                                    "приложение откроется и включит воспроизведение.",
                                color = Neon.TextLow,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionChip("Подключиться и играть", accent2) {
                                PlayerHub.switchToYandex(launchApp = true, autoPlay = true)
                            }
                            ActionChip("Открыть приложение", accent) {
                                PlayerHub.openYandexMusic()
                            }
                            if (!hasAccess) {
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
    onRemove: (() -> Unit)? = null,
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
        if (onRemove != null) {
            Spacer(Modifier.size(10.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Neon.Red.copy(alpha = 0.14f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Close, "Удалить", tint = Neon.Red, modifier = Modifier.size(15.dp))
            }
        }
    }
}

/** Поиск радиостанций в открытом каталоге radio-browser.info и сохранение найденного. */
@Composable
private fun RadioSearchTab(accent: Color, accent2: Color) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val results by PlayerHub.stationSearch.collectAsState()
    val searching by PlayerHub.searching.collectAsState()
    val stations by PlayerHub.stations.collectAsState()

    fun runSearch() {
        val q = query
        scope.launch { PlayerHub.searchStations(q) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x660C1424))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Search, null,
                        tint = accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("Название станции, жанр, город…", color = Neon.TextLow, fontSize = 14.sp)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Neon.TextHi, fontSize = 14.sp),
                            cursorBrush = SolidColor(accent),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { runSearch() },
                                onDone = { runSearch() }
                            ),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            ActionChip("Найти", accent2) { runSearch() }
        }

        Spacer(Modifier.height(12.dp))

        when {
            searching -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent2)
            }
            results.isEmpty() && query.isNotBlank() -> EmptyHint("Ничего не найдено. Проверьте название или интернет-соединение.")
            results.isEmpty() -> EmptyHint("Введите название радиостанции и нажмите «Найти».")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(results, key = { it.streamUrl }) { r ->
                    val saved = stations.any { it.streamUrl == r.streamUrl }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x330C1424))
                            .clickable { PlayerHub.saveAndPlayStation(r) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Radio, null, tint = accent.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.name, color = Neon.TextHi, fontSize = 15.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOf(r.genre, r.country).filter { it.isNotBlank() }.joinToString(" · "),
                                color = Neon.TextLow, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background((if (saved) accent2 else accent).copy(alpha = 0.16f))
                                .clickable(enabled = !saved) { PlayerHub.saveStation(r) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                                if (saved) "Сохранено" else "Сохранить",
                                tint = if (saved) accent2 else accent,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
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
