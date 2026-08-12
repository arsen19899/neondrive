package com.neondrive.launcher.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.data.FavoritePlace
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.nav.GuidanceEngine
import com.neondrive.launcher.nav.HazardHub
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
 * Здесь живёт вся навигация оболочки: настоящая карта, поиск, маршрут, карточка
 * манёвра, полосы и голос. Сторонний навигатор не участвует.
 *
 * Режим «Поверх карты» (чужой навигатор на весь экран, панели оболочки поверх
 * него в отдельных окнах) удалён вместе со всей обвязкой: оверлейным сервисом,
 * разрешением на рисование поверх окон и нарисованной картой-заглушкой. Своя
 * карта делает то же самое и лучше — без разрешений, без чужих приложений и без
 * второго набора панелей, который приходилось поддерживать параллельно.
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

    /*
     * Восстановление маршрута после перезапуска оболочки.
     *
     * Оболочка на магнитоле перезапускается чаще обычного приложения: не хватило
     * памяти, водитель заглушил машину на заправке, прошивка пересоздала активити.
     * Терять из-за этого проложенный маршрут нельзя — набирать адрес заново за
     * рулём мучительно. Точка назначения лежит в настройках, и как только появится
     * фикс GPS, маршрут строится от нового текущего положения.
     *
     * Условие `!route.hasDestination` защищает от повторного построения: эффект
     * перезапустится на первом же фиксе, а маршрут к тому моменту уже есть.
     */
    LaunchedEffect(gps.hasFix, settings.lastDestLat) {
        if (!gps.hasFix) return@LaunchedEffect
        if (route.hasDestination || route.loading) return@LaunchedEffect
        val lat = settings.lastDestLat
        val lon = settings.lastDestLon
        if (lat.isNaN() || lon.isNaN()) return@LaunchedEffect
        RouteHub.buildTo(
            context = context,
            fromLat = gps.lastLat,
            fromLon = gps.lastLon,
            toLat = lat,
            toLon = lon,
            title = settings.lastDestTitle
        )
    }

    // Точку назначения запоминаем и забываем вместе с самим маршрутом.
    LaunchedEffect(route.destLat, route.destLon, route.hasDestination) {
        runCatching {
            if (route.hasDestination) {
                repo.setLastDestination(route.destLat, route.destLon, route.destTitle)
            } else {
                repo.clearLastDestination()
            }
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

    BoxWithConstraints(
        modifier
            .neonGlow(accent, 26.dp, 0.14f, 16.dp)
            .neonPanel(accent, radius = 26.dp)
            .clip(RoundedCornerShape(26.dp))
    ) {
        // Панель карты бывает и в половину экрана, и в треть. На узкой панели
        // подписи на кнопках не помещаются, поэтому ниже определённой ширины
        // управление сжимается до одних иконок, а шрифты уменьшаются. Порог
        // подобран по самой длинной подписи ряда — «Куда» плюс «Домой» плюс зум.
        val compact = maxWidth < 420.dp

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

        // Карточка манёвра — единственное, что занимает верх карты. Раньше рядом
        // с ней жила шапка с названием навигатора и курсом: на узкой панели они
        // налезали друг на друга, а пользы не несли — куда ехать, говорит сама
        // карточка, а название чужого приложения к своей навигации отношения не имеет.
        if (guidance.active && guidance.instruction.isNotBlank()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ManeuverCard(
                    guidance = guidance,
                    accent = accent,
                    accent2 = accent2,
                    compact = compact
                )
                // Полосы показываем только когда манёвр уже близко: за километр
                // до поворота перестраиваться рано, а плашка занимает карту.
                if (guidance.lanes.isNotEmpty() && guidance.distanceToManeuverM < 400) {
                    LaneGuide(lanes = guidance.lanes, accent = accent, compact = compact)
                }
            }
        }

        // Выбор варианта маршрута. Показываем только пока не тронулись: менять
        // маршрут на скорости — плохая идея, да и читать три плашки за рулём некогда.
        if (route.hasAlternatives && gps.speedKmh < 5f && !guidance.active) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
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
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option.label,
                            color = if (sel) accent2 else Neon.TextMid,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (option.warningLabel.isNotBlank()) {
                            Spacer(Modifier.size(8.dp))
                            Text(option.warningLabel, color = Neon.Amber, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Всё управление прижато к низу. Знак ограничения и камера — строкой над
        // ним, чтобы не уезжали при горизонтальной прокрутке кнопок: пропустить
        // ограничение скорости из-за того, что оно «уехало вбок», недопустимо.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hazard.speedLimitKmh != null || hazard.cameraAheadM != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    hazard.speedLimitKmh?.let { limit ->
                        SpeedLimitSign(
                            limit = limit,
                            speeding = hazard.speeding,
                            compact = compact
                        )
                    }
                    hazard.cameraAheadM?.let { dist ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(13.dp))
                                .background(Color(0xCC1A0A12))
                                .border(1.dp, Neon.Red.copy(alpha = 0.7f), RoundedCornerShape(13.dp))
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.PhotoCamera, "Камера",
                                tint = Neon.Red, modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(5.dp))
                            Text(
                                formatDistance(dist),
                                color = Neon.TextHi,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Маршрут — отдельной строкой над кнопками, у левого края.
            //
            // Раньше расстояние, время и время прибытия жили чипом внутри ряда
            // управления. На узких экранах этот чип уезжал за границу вместе с
            // прокруткой ряда, и водитель не видел ни сколько ехать, ни когда
            // приедет, пока не домотает кнопки вбок. Теперь это отдельная строка,
            // которая никуда не прокручивается.
            if (route.hasRoute || route.loading) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xCC060B14))
                        .border(1.dp, accent2.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .clickable { RouteHub.clear() }
                        .padding(start = 11.dp, end = 9.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when {
                            route.loading -> "Строим маршрут…"
                            guidance.active -> buildString {
                                append(guidance.remainingLabel)
                                append(" · ")
                                append(guidance.etaLabel)
                                if (guidance.arrivalLabel.isNotBlank()) {
                                    append(" · в ")
                                    append(guidance.arrivalLabel)
                                }
                            }
                            else -> "${formatDistance(route.distanceM)} · " +
                                formatDuration(route.durationSec)
                        },
                        color = Neon.TextHi,
                        fontSize = if (compact) 12.sp else 13.sp,
                        maxLines = 1,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.size(8.dp))
                    Icon(
                        Icons.Rounded.Close, "Сбросить маршрут",
                        tint = Neon.Red, modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Единственный ряд управления. Прокручивается по горизонтали: на
            // трети экрана всё сразу не помещается ни при каком сжатии, а
            // обрезать кнопки молча — хуже, чем дать их домотать.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MapButton(Icons.Rounded.Search, "Куда", accent2, compact) { searchOpen = true }
                    MapButton(Icons.Rounded.Home, "Домой", accent, compact) {
                        if (!settings.hasHomePoint) {
                            Toast.makeText(
                                context,
                                "Точка «Дом» не задана: настройки оболочки → Навигатор",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            goTo(settings.homeLat, settings.homeLon, "Дом")
                        }
                    }
                    if (!follow) {
                        MapButton(Icons.Rounded.MyLocation, "К себе", accent2, compact) {
                            follow = true
                        }
                    }
                    MapButton(Icons.Rounded.Remove, "", accent, true) {
                        manualZoom = true
                        zoom = (zoom - 1.0).coerceAtLeast(3.0)
                    }
                    MapButton(Icons.Rounded.Add, "", accent, true) {
                        manualZoom = true
                        zoom = (zoom + 1.0).coerceAtMost(19.0)
                    }

                // Кнопки «открыть сторонний навигатор» здесь намеренно нет.
                // Она дублировала плитку «Навигация» в доке, занимала место в
                // единственном ряду управления и подписывалась именем чужого
                // приложения на карте, которая ему ничем не обязана. Нужен
                // навигатор с пробками — он в доке, одним касанием.
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
private fun SpeedLimitSign(limit: Int, speeding: Boolean, compact: Boolean = false) {
    val d = if (compact) 42.dp else 52.dp
    Box(
        Modifier
            .size(d)
            .clip(RoundedCornerShape(d / 2))
            .background(if (speeding) Neon.Red else Color.White)
            .border(
                if (compact) 3.dp else 4.dp,
                if (speeding) Color.White else Neon.Red,
                RoundedCornerShape(d / 2)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            limit.toString(),
            color = if (speeding) Color.White else Color.Black,
            fontSize = when {
                compact && limit >= 100 -> 14.sp
                compact -> 16.sp
                limit >= 100 -> 17.sp
                else -> 20.sp
            },
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Кнопка управления картой.
 *
 * В компактном режиме (узкая панель) остаётся только иконка: подпись «Куда» на
 * трети экрана всё равно обрезалась бы до «Ку…», а иконка лупы понятна без слов.
 * Размер при этом не уменьшается — попасть пальцем на ходу важнее, чем сэкономить
 * пару пикселей.
 */
@Composable
private fun MapButton(
    icon: ImageVector,
    label: String,
    color: Color,
    compact: Boolean,
    onClick: () -> Unit
) {
    val iconOnly = compact || label.isBlank()
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC060B14))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = if (iconOnly) 11.dp else 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label.ifBlank { null }, tint = color, modifier = Modifier.size(20.dp))
        if (!iconOnly) {
            Spacer(Modifier.size(7.dp))
            Text(label, fontSize = 13.sp, color = Neon.TextMid, maxLines = 1)
        }
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
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xE6060B14))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            .padding(
                horizontal = if (compact) 11.dp else 16.dp,
                vertical = if (compact) 9.dp else 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            maneuverIcon(guidance.maneuverType, guidance.maneuverModifier),
            null,
            tint = accent,
            modifier = Modifier.size(if (compact) 24.dp else 30.dp)
        )
        Spacer(Modifier.size(if (compact) 8.dp else 12.dp))
        Column {
            Text(
                if (guidance.rerouting) "Перестраиваю маршрут" else guidance.distanceLabel,
                color = accent,
                fontSize = if (compact) 17.sp else 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                guidance.instruction,
                color = Neon.TextHi,
                fontSize = if (compact) 12.sp else 14.sp,
                maxLines = 2
            )
            // «затем …» — первое, чем жертвуем на узкой панели: подсказка полезная,
            // но следующий манёвр важнее того, что будет после него.
            if (!compact && guidance.thenInstruction.isNotBlank()) {
                Text(
                    "затем ${guidance.thenInstruction}",
                    color = Neon.TextLow,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.size(if (compact) 10.dp else 16.dp))
        Column(horizontalAlignment = Alignment.End) {
            // Время прибытия крупнее остатка: с часами на панели оно сравнивается
            // мгновенно, а «через сколько» приходится складывать в уме.
            Text(
                guidance.arrivalLabel,
                color = accent2,
                fontSize = if (compact) 15.sp else 18.sp,
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
    compact: Boolean = false,
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
                modifier = Modifier.size(
                    when {
                        compact && lane.valid -> 21.dp
                        compact -> 18.dp
                        lane.valid -> 26.dp
                        else -> 22.dp
                    }
                )
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
