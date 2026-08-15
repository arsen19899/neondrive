package com.neondrive.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neondrive.launcher.data.FavoritePlace
import com.neondrive.launcher.nav.Place
import com.neondrive.launcher.nav.PlaceCategory
import com.neondrive.launcher.nav.PlaceSearch
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonPanel
import com.neondrive.launcher.voice.VoiceAssistant
import kotlinx.coroutines.delay

/**
 * Поиск точки назначения для собственной навигации оболочки.
 *
 * Два способа найти, потому что за рулём они нужны в разных ситуациях:
 *  • строка поиска — когда известно название или адрес; выдача обновляется по мере
 *    набора и смещена к текущей позиции, поэтому «лен» рядом с домом даст соседнюю
 *    Ленина, а не Ленинград за тысячу километров;
 *  • плитки категорий — когда важно не «что», а «ближайшее»: заправка, поесть,
 *    туалет. Одно касание вместо набора текста на ходу.
 *
 * Запрос уходит не на каждую букву, а после паузы в наборе: публичные геокодеры
 * OSM живут на пожертвованиях и просят не заваливать их запросами.
 */
@Composable
fun NavSearchDialog(
    accent: Color,
    accent2: Color,
    currentLat: Double,
    currentLon: Double,
    hasFix: Boolean,
    favorites: List<FavoritePlace>,
    history: List<String>,
    onPick: (Place) -> Unit,
    onSaveFavorite: (Place) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<PlaceCategory?>(null) }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // Текстовый поиск с паузой: 450 мс без новых нажатий — и только тогда запрос.
    LaunchedEffect(query) {
        if (query.trim().length < 3) {
            if (category == null) results = emptyList()
            return@LaunchedEffect
        }
        category = null
        loading = true
        note = null
        delay(450)
        val found = PlaceSearch.byText(query, currentLat, currentLon)
        results = found
        loading = false
        note = if (found.isEmpty()) "Ничего не найдено" else null
    }

    LaunchedEffect(category) {
        val cat = category ?: return@LaunchedEffect
        loading = true
        note = null
        val found = PlaceSearch.byCategory(cat, currentLat, currentLon)
        results = found
        loading = false
        note = if (found.isEmpty()) "Рядом ничего не нашлось" else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.88f)
                .heightIn(max = 560.dp)
                .neonPanel(accent, radius = 24.dp)
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "КУДА ЕДЕМ",
                    color = Neon.TextHi,
                    fontSize = 16.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x660C1424))
                        .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, "Закрыть", tint = accent, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.size(14.dp))

            // Строка поиска
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xAA060B14))
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Адрес или название места",
                            color = Neon.TextLow,
                            fontSize = 15.sp
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Neon.TextHi, fontSize = 15.sp),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clickable { query = ""; results = emptyList(); note = null },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close, "Очистить",
                            tint = Neon.TextLow, modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Голосовой ввод адреса. Набирать текст за рулём — то, ради чего
                // голосовое управление вообще нужно, поэтому кнопка стоит прямо
                // в строке поиска, а не прячется в меню. Диктовка не разбирается
                // как команда: сказанное просто ложится в поле, и дальше работает
                // обычный поиск с задержкой.
                Spacer(Modifier.size(6.dp))
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(accent2.copy(alpha = 0.16f))
                        .clickable {
                            category = null
                            VoiceAssistant.dictate(context) { spoken ->
                                if (spoken.isNotBlank()) query = spoken
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Mic, "Сказать адрес",
                        tint = accent2, modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.size(12.dp))

            // Категории
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlaceCategory.entries.forEach { cat ->
                    val selected = category == cat
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) accent2.copy(alpha = 0.22f) else Color(0xAA060B14)
                            )
                            .border(
                                1.dp,
                                (if (selected) accent2 else accent).copy(alpha = if (selected) 0.8f else 0.28f),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                query = ""
                                category = cat
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            cat.label,
                            fontSize = 13.sp,
                            color = if (selected) accent2 else Neon.TextMid
                        )
                    }
                }
            }

            // Избранное и история — до того, как человек начал печатать. Три четверти
            // поездок повторяются: дом, работа, дача. Заставлять набирать их каждый раз
            // за рулём — худшее, что может сделать навигация.
            if (query.isBlank() && category == null && (favorites.isNotEmpty() || history.isNotEmpty())) {
                Spacer(Modifier.size(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    favorites.forEach { fav ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xAA060B14))
                                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .clickable {
                                    onPick(
                                        Place(
                                            name = fav.name,
                                            subtitle = "Избранное",
                                            lat = fav.lat,
                                            lon = fav.lon,
                                            straightM = 0.0
                                        )
                                    )
                                }
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Star, null,
                                tint = accent, modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(fav.name, fontSize = 13.sp, color = Neon.TextHi, maxLines = 1)
                        }
                    }
                    history.forEach { q ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x66060B14))
                                .border(1.dp, Neon.TextLow.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                .clickable { query = q }
                                .padding(horizontal = 13.dp, vertical = 10.dp)
                        ) {
                            Text(q, fontSize = 13.sp, color = Neon.TextMid, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(Modifier.size(14.dp))

            when {
                !hasFix -> Text(
                    "Нет сигнала GPS. Поиск ближайших мест и построение маршрута " +
                        "начнутся, как только появятся спутники.",
                    color = Neon.Amber,
                    fontSize = 13.sp
                )

                loading -> Text("Ищем…", color = Neon.TextLow, fontSize = 14.sp)

                note != null -> Text(note.orEmpty(), color = Neon.TextLow, fontSize = 14.sp)

                results.isEmpty() -> Text(
                    "Введите адрес или выберите категорию рядом.",
                    color = Neon.TextLow,
                    fontSize = 14.sp
                )

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(results) { place ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(place) }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    place.name,
                                    color = Neon.TextHi,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                if (place.subtitle.isNotBlank()) {
                                    Text(
                                        place.subtitle,
                                        color = Neon.TextLow,
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            Spacer(Modifier.size(10.dp))
                            Text(
                                place.distanceLabel,
                                color = accent,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.size(8.dp))
                            // Звезда добавляет точку в избранное, не открывая её:
                            // сохранить дачу проще один раз при нахождении, чем
                            // потом искать её заново.
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .clickable { onSaveFavorite(place) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.StarBorder, "В избранное",
                                    tint = accent2, modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
