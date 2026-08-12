package com.neondrive.launcher.ui.home

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.data.FavoritePlace
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MapMode
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.nav.GuidanceEngine
import com.neondrive.launcher.nav.HazardHub
import com.neondrive.launcher.nav.MapFrameController
import com.neondrive.launcher.nav.NavigatorBridge
import com.neondrive.launcher.nav.RouteHub
import com.neondrive.launcher.nav.formatDistance
import com.neondrive.launcher.nav.formatDuration
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import com.neondrive.launcher.ui.theme.neonPanel
import kotlinx.coroutines.launch

/**
 * Панель навигации — заданная настройками доля рабочего стола.
 *
 * Два режима, и они устроены совершенно по-разному:
 *  • [MapMode.EMBEDDED] — здесь живёт настоящая карта оболочки со своим поиском,
 *    маршрутом, карточкой манёвра и голосом. Сторонний навигатор не нужен;
 *  • [MapMode.OVERLAY] — панель рисует стилизованный HUD по данным GPS, а по
 *    нажатию открывает чужой навигатор на весь экран и кладёт панели оболочки
 *    поверх него.
 */
@Composable
fun MapPanel(
    gps: GpsState,
    settings: LauncherSettings,
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Свой экземпляр репозитория, а не проброс сверху: DataStore под ним — синглтон
    // на приложение, так что это просто тонкая обёртка над тем же хранилищем, зато
    // сигнатура MapPanel не тянет за собой лишний параметр через весь рабочий стол.
    val repo = remember(context) { SettingsRepository(context.applicationContext) }
    val overlayActive by MapFrameController.active.collectAsState()
    val navLabel = remember(settings.mapPackage) {
        NavigatorBridge.labelOf(context, settings.mapPackage)
    }

    val launch: () -> Unit = { MapFrameController.launch(context, settings) }

    val embedded = settings.mapMode == MapMode.EMBEDDED
    // Своя карта интерактивна: тащить, щипать, зумить. Значит, панель не может
    // быть одной большой кнопкой «открыть навигатор» — иначе любой жест по карте
    // выкидывал бы в навигатор. В этом режиме открытие висит только на явной
    // кнопке внизу справа.
    var follow by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(16.0) }
    var searchOpen by remember { mutableStateOf(false) }
    var manualZoom by remember { mutableStateOf(false) }
    val route by RouteHub.state.collectAsState()
    val guidance by GuidanceEngine.state.collectAsState()
    val hazard by HazardHub.state.collectAsState()

    // Ошибку построения маршрута обязательно показываем. Раньше она молча
    // ложилась в RouteState.error и нигде не всплывала: пользователь выбирал
    // точку, ничего не происходило, и понять почему было невозможно.
    LaunchedEffect(route.error) {
        val err = route.error
        if (!err.isNullOrBlank()) {
            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
        }
    }

    /** Запустить ведение до выбранной точки. */
    val goTo: (Double, Double, String) -> Unit = { lat, lon, title ->
        if (gps.hasFix) {
            RouteHub.buildTo(
                context = context,
                fromLat = gps.lastLat,
                fromLon = gps.lastLon,
                toLat = lat,
                toLon = lon,
                title = title
            )
            follow = true
        } else {
            Toast.makeText(context, "Нет сигнала GPS — маршрут не построить", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier
            .neonGlow(accent, 26.dp, 0.14f, 16.dp)
            .neonPanel(accent, radius = 26.dp)
            .clip(RoundedCornerShape(26.dp))
            .then(if (embedded) Modifier else Modifier.clickable(onClick = launch))
    ) {
        if (embedded) {
            EmbeddedMap(
                gps = gps,
                accent = accent,
                modifier = Modifier.fillMaxSize(),
                follow = follow,
                onUserPanned = { follow = false },
                zoomRequest = zoom,
                route = route.points,
                rotateByBearing = settings.navRotateMap,
                // Ручной зум должен побеждать автоматический, иначе кнопки «+/−»
                // выглядят сломанными: нажал — и масштаб тут же уехал обратно.
                autoZoom = settings.navAutoZoom && !manualZoom
            )
        } else {
            MapCanvas(accent = accent, accent2 = accent2, moving = gps.speedKmh > 1f)
        }

        // Карточка манёвра — поверх карты, по центру сверху. Появляется только
        // во время ведения и вытесняет обычную шапку панели.
        if (embedded && guidance.active && guidance.instruction.isNotBlank()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ManeuverCard(guidance = guidance, accent = accent, accent2 = accent2)
                // Полосы показываем только когда манёвр уже близко: за километр
                // до поворота перестраиваться рано, а плашка занимает карту.
                if (guidance.lanes.isNotEmpty() && guidance.distanceToManeuverM < 400) {
                    LaneGuide(lanes = guidance.lanes, accent = accent)
                }
            }
        }

        // Кнопки масштаба — вертикальной парой у правого края, как в любом
        // нормальном навигаторе. Раньше зум висел мелкими чипами в общем ряду
        // внизу и на ходу в них было не попасть.
        if (embedded) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ZoomButton(Icons.Rounded.Add, "Приблизить", accent) {
                    manualZoom = true
                    zoom = (zoom + 1.0).coerceAtMost(19.0)
                }
                ZoomButton(Icons.Rounded.Remove, "Отдалить", accent) {
                    manualZoom = true
                    zoom = (zoom - 1.0).coerceAtLeast(3.0)
                }
            }
        }

        // Знак ограничения и камера — слева снизу, крупно и по правилам дорожного
        // знака: белый круг, красная кайма. При превышении круг заливается красным,
        // чтобы это ловилось боковым зрением, без чтения цифры.
        if (embedded && (hazard.speedLimitKmh != null || hazard.cameraAheadM != null)) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 62.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                hazard.speedLimitKmh?.let { limit ->
                    SpeedLimitSign(limit = limit, speeding = hazard.speeding)
                }
                hazard.cameraAheadM?.let { dist ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color(0xCC1A0A12))
                            .border(1.dp, Neon.Red.copy(alpha = 0.7f), RoundedCornerShape(15.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.PhotoCamera, "Камера",
                            tint = Neon.Red, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(7.dp))
                        Text(
                            formatDistance(dist),
                            color = Neon.TextHi,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Выбор варианта маршрута. Показываем только пока не тронулись: менять
        // маршрут на скорости — плохая идея, да и читать три плашки за рулём некогда.
        if (embedded && route.hasAlternatives && gps.speedKmh < 5f) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                route.options.forEachIndexed { i, option ->
                    val sel = i == route.selected
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(if (sel) accent2.copy(alpha = 0.22f) else Color(0xCC060B14))
                            .border(
                                1.dp,
                                (if (sel) accent2 else accent).copy(alpha = if (sel) 0.8f else 0.3f),
                                RoundedCornerShape(13.dp)
                            )
                            .clickable { RouteHub.selectOption(context, i) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (i == 0) "Оптимальный" else "Вариант ${i + 1}",
                            color = if (sel) accent2 else Neon.TextLow,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            option.label,
                            color = Neon.TextHi,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (option.warningLabel.isNotBlank()) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                option.warningLabel,
                                color = Neon.Amber,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xAA060B14))
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Navigation, null,
                        tint = accent, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        navLabel.uppercase(),
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Neon.TextMid
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (gps.hasFix) "КУРС ${gps.bearingDeg.toInt()}°  ·  ${gps.altitudeM.toInt()} м"
                else "ОЖИДАНИЕ СПУТНИКОВ",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (gps.hasFix) accent.copy(alpha = 0.8f) else Neon.TextLow
            )
        }

        // Подсказка про активный режим — по центру, поверх нарисованного HUD.
        // На своей карте её нет: там центр занят машиной, а плашка поверх живой
        // карты только мешала бы и перехватывала касания.
        if (!embedded) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xB3060B14))
                    .border(1.dp, accent2.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .clickable(onClick = launch)
                    .padding(horizontal = 22.dp, vertical = 14.dp)
            ) {
                Text(
                    "Нажмите, чтобы открыть $navLabel с панелями поверх",
                    fontSize = 12.sp,
                    color = accent2,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // «Куда» — вход в собственный поиск: адреса, названия и ближайшие
            // места по категориям. Первым в ряду, потому что это главное действие
            // навигации: остальное — вспомогательное.
            if (embedded) {
                QuickChip("Куда", Icons.Rounded.Search, accent2) { searchOpen = true }
            }

            QuickChip("Домой", Icons.Rounded.Home, accent) {
                if (!settings.hasHomePoint) {
                    Toast.makeText(
                        context,
                        "Точка «Дом» не задана: настройки оболочки → Навигатор",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (embedded) {
                    // Своя карта ведёт сама: строим маршрут, показываем линию,
                    // манёвры и озвучиваем повороты — сторонний навигатор не нужен.
                    goTo(settings.homeLat, settings.homeLon, "Дом")
                } else {
                    // В режиме «Поверх карты» рисует чужое приложение, поэтому
                    // маршрут отдаём ему — своей линии там некуда лечь.
                    NavigatorBridge.buildRoute(
                        context, settings.mapPackage,
                        settings.homeLat, settings.homeLon,
                        gps.lastLat.takeIf { gps.hasFix },
                        gps.lastLon.takeIf { gps.hasFix }
                    )
                }
            }

            if (embedded) {
                if (!follow) {
                    QuickChip("К себе", Icons.Rounded.MyLocation, accent2) { follow = true }
                }
                if (route.hasRoute || route.loading) {
                    QuickChip(
                        when {
                            route.loading -> "Строим маршрут…"
                            guidance.active -> "${guidance.remainingLabel} · ${guidance.etaLabel}"
                            else -> "${formatDistance(route.distanceM)} · " +
                                formatDuration(route.durationSec)
                        },
                        Icons.Rounded.Close,
                        if (route.loading) Neon.TextLow else Neon.Red
                    ) { RouteHub.clear() }
                }
            } else {
                QuickChip("Я здесь", Icons.Rounded.MyLocation, accent) {
                    if (gps.hasFix) {
                        NavigatorBridge.showPoint(
                            context, settings.mapPackage, gps.lastLat, gps.lastLon, 16, "Моя позиция"
                        )
                    } else {
                        Toast.makeText(context, "Нет GPS-фикса", Toast.LENGTH_SHORT).show()
                    }
                }
                if (overlayActive) {
                    QuickChip("Убрать панели", Icons.Rounded.VisibilityOff, Neon.Red) {
                        MapFrameController.stop(context)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .neonGlow(accent2, 16.dp, 0.35f, 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent2.copy(alpha = 0.20f))
                    .border(1.dp, accent2.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .clickable(onClick = launch)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.OpenInFull, null,
                        tint = accent2, modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        when (settings.mapMode) {
                            MapMode.EMBEDDED -> navLabel.uppercase()
                            MapMode.OVERLAY -> "ПОВЕРХ КАРТЫ"
                        },
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = accent2
                    )
                }
            }
        }
    }

    if (searchOpen) {
        NavSearchDialog(
            accent = accent,
            accent2 = accent2,
            currentLat = gps.lastLat,
            currentLon = gps.lastLon,
            hasFix = gps.hasFix,
            favorites = settings.navFavorites,
            history = settings.navSearchHistory,
            onPick = { place ->
                searchOpen = false
                goTo(place.lat, place.lon, place.name)
                scope.launch { runCatching { repo.pushSearchHistory(place.name) } }
            },
            onSaveFavorite = { place ->
                scope.launch {
                    runCatching {
                        val next = (settings.navFavorites
                            .filter { !it.name.equals(place.name, ignoreCase = true) } +
                            FavoritePlace(place.name, place.lat, place.lon)).takeLast(12)
                        repo.setNavFavorites(next)
                    }
                }
                Toast.makeText(context, "«${place.name}» в избранном", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { searchOpen = false }
        )
    }
}

/**
 * Знак ограничения скорости — узнаваемый круг с красной каймой.
 *
 * Рисуется по правилам дорожного знака, а не в неоновой палитре оболочки: это тот
 * редкий случай, когда стиль должен уступить узнаваемости. Водитель считывает этот
 * круг рефлекторно, и перекрашивать его в циан было бы вредительством.
 */
@Composable
private fun SpeedLimitSign(limit: Int, speeding: Boolean) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(if (speeding) Neon.Red else Color.White)
            .border(
                4.dp,
                if (speeding) Color.White else Neon.Red,
                RoundedCornerShape(26.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            limit.toString(),
            color = if (speeding) Color.White else Color.Black,
            fontSize = if (limit >= 100) 17.sp else 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Круглая кнопка масштаба у края карты — крупная, чтобы попадать на ходу. */
@Composable
private fun ZoomButton(
    icon: ImageVector,
    description: String,
    accent: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xCC060B14))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = accent, modifier = Modifier.size(22.dp))
    }
}

/**
 * Карточка следующего манёвра: стрелка, расстояние, куда поворачивать и что будет
 * дальше. Расстояние крупно и слева — за рулём взгляд цепляется именно за него.
 */
@Composable
private fun ManeuverCard(
    guidance: com.neondrive.launcher.nav.GuidanceState,
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6060B14))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            maneuverIcon(guidance.maneuverType, guidance.maneuverModifier),
            null,
            tint = accent,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                if (guidance.rerouting) "Перестраиваю маршрут" else guidance.distanceLabel,
                color = accent,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                guidance.instruction,
                color = Neon.TextHi,
                fontSize = 14.sp,
                maxLines = 1
            )
            if (guidance.thenInstruction.isNotBlank()) {
                Text(
                    "затем ${guidance.thenInstruction}",
                    color = Neon.TextLow,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            // Время прибытия крупнее остатка: с часами на панели оно сравнивается
            // мгновенно, а «через сколько» приходится складывать в уме.
            Text(
                guidance.arrivalLabel,
                color = accent2,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "${guidance.remainingLabel} · ${guidance.etaLabel}",
                color = Neon.TextLow,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Подсказка полос перед манёвром.
 *
 * Полосы, из которых манёвр выполнить нельзя, приглушены до едва заметных, а
 * нужные подсвечены акцентом. Так решение читается за долю секунды: смотришь не
 * на стрелки, а на то, что светится.
 */
@Composable
private fun LaneGuide(
    lanes: List<com.neondrive.launcher.nav.RouteLane>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6060B14))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        lanes.forEach { lane ->
            Icon(
                laneIcon(lane.indications),
                null,
                tint = if (lane.valid) accent else Neon.TextLow.copy(alpha = 0.35f),
                modifier = Modifier.size(if (lane.valid) 26.dp else 22.dp)
            )
        }
    }
}

/** Иконка полосы по её разметке из OSM. */
private fun laneIcon(indications: List<String>): ImageVector {
    val i = indications.joinToString(" ")
    return when {
        i.contains("uturn") -> Icons.Rounded.UTurnLeft
        i.contains("sharp left") -> Icons.Rounded.TurnSharpLeft
        i.contains("sharp right") -> Icons.Rounded.TurnSharpRight
        i.contains("slight left") -> Icons.Rounded.TurnSlightLeft
        i.contains("slight right") -> Icons.Rounded.TurnSlightRight
        i.contains("left") -> Icons.Rounded.TurnLeft
        i.contains("right") -> Icons.Rounded.TurnRight
        else -> Icons.Rounded.Straight
    }
}

/** Стрелка под тип манёвра OSRM. */
private fun maneuverIcon(type: String, modifier: String): ImageVector = when {
    type == "arrive" -> Icons.Rounded.Flag
    type == "roundabout" || type == "rotary" || type == "roundabout turn" ->
        Icons.Rounded.RotateRight
    modifier.contains("uturn") -> Icons.Rounded.UTurnLeft
    modifier.contains("sharp left") -> Icons.Rounded.TurnSharpLeft
    modifier.contains("sharp right") -> Icons.Rounded.TurnSharpRight
    modifier.contains("slight left") -> Icons.Rounded.TurnSlightLeft
    modifier.contains("slight right") -> Icons.Rounded.TurnSlightRight
    modifier.contains("left") -> Icons.Rounded.TurnLeft
    modifier.contains("right") -> Icons.Rounded.TurnRight
    else -> Icons.Rounded.Straight
}

@Composable
private fun QuickChip(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xAA060B14))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, fontSize = 12.sp, color = Neon.TextMid)
    }
}

/** Стилизованная карта-HUD: сетка кварталов, магистраль, маркер машины. */
@Composable
private fun MapCanvas(accent: Color, accent2: Color, moving: Boolean) {
    // «Упрощённая графика» останавливает декоративный дрейф и пульсацию —
    // это чисто фоновая заглушка, ей не обязательно гонять перерисовку вечно.
    val reduced = com.neondrive.launcher.ui.theme.LocalReducedEffects.current
    val scroll: Float
    val pulse: Float
    if (reduced) {
        scroll = 0f
        pulse = 0.5f
    } else {
        val tr = rememberInfiniteTransition(label = "map")
        scroll = tr.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(if (moving) 5200 else 16000), RepeatMode.Restart),
            label = "scroll"
        ).value
        pulse = tr.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1800), RepeatMode.Reverse),
            label = "pulse"
        ).value
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF071019), Color(0xFF04070E))))
    ) {
        val w = size.width
        val h = size.height

        val cell = 96f
        val offset = scroll * cell
        var x = -cell + offset
        while (x < w + cell) {
            drawLine(accent.copy(alpha = 0.055f), Offset(x, 0f), Offset(x, h), 1f)
            x += cell
        }
        var y = -cell + offset
        while (y < h + cell) {
            drawLine(accent.copy(alpha = 0.055f), Offset(0f, y), Offset(w, y), 1f)
            y += cell
        }

        drawLine(
            accent.copy(alpha = 0.13f),
            Offset(0f, h * 0.34f), Offset(w, h * 0.30f), 14f
        )
        drawLine(
            accent.copy(alpha = 0.10f),
            Offset(w * 0.72f, 0f), Offset(w * 0.64f, h), 11f
        )

        val route = Path().apply {
            moveTo(w * 0.5f, h + 40f)
            cubicTo(w * 0.5f, h * 0.72f, w * 0.30f, h * 0.60f, w * 0.33f, h * 0.40f)
            cubicTo(w * 0.36f, h * 0.22f, w * 0.62f, h * 0.22f, w * 0.70f, h * 0.06f)
        }
        drawPath(
            route,
            brush = Brush.verticalGradient(listOf(accent2, accent)),
            style = Stroke(width = 13f, cap = StrokeCap.Round)
        )
        drawPath(
            route,
            color = Color.White.copy(alpha = 0.28f),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )

        val cx = w * 0.5f
        val cy = h * 0.78f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.32f * (0.5f + pulse * 0.5f)), Color.Transparent),
                center = Offset(cx, cy), radius = 78f
            ),
            radius = 78f, center = Offset(cx, cy)
        )
        val car = Path().apply {
            moveTo(cx, cy - 20f)
            lineTo(cx + 14f, cy + 16f)
            lineTo(cx, cy + 8f)
            lineTo(cx - 14f, cy + 16f)
            close()
        }
        drawPath(car, color = accent)
        drawPath(car, color = Color.White.copy(alpha = 0.5f), style = Stroke(1.5f))

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xCC04070E),
                0.28f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to Color(0xDD04070E)
            )
        )

        val scanY = h * ((scroll * 1.3f) % 1f)
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.16f), Color.Transparent)
            ),
            start = Offset(0f, scanY), end = Offset(w, scanY), strokeWidth = 2f
        )
    }
}
