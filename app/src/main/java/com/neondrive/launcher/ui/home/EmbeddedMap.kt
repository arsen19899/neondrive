package com.neondrive.launcher.ui.home

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.nav.GeoMath
import com.neondrive.launcher.nav.RoutePoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * Настоящая интерактивная карта внутри панели рабочего стола.
 *
 * ## Зачем это существует
 *
 * Показать ЧУЖОЕ приложение (Яндекс.Навигатор) на части экрана можно только
 * через freeform-режим прошивки — других системных способов нет: split-screen
 * домашнему экрану недоступен, а запуск на виртуальный дисплей Android с 10-й
 * версии запрещает для чужих активностей. На бюджетных ГУ freeform выключен, и
 * включить его без adb и перезагрузки нельзя. То есть режим «во фрейме» на таких
 * устройствах не заработает никогда, сколько его ни чини.
 *
 * Отсюда решение: не пытаться уместить чужое окно в панель, а нарисовать карту
 * самим — внутри собственного окна оболочки, где никакие ограничения оконного
 * менеджера не действуют. Тогда «часть экрана — карта, часть — оболочка»
 * работает на любой прошивке, сразу, без настройки.
 *
 * ## Почему osmdroid, а не MapKit / Mapbox
 *
 * Решение принято сознательно, а не от безысходности. У MapKit есть бесплатная
 * лицензия (лимит по DAU, только онлайн, нельзя в платном приложении), и слой
 * пробок входит даже в Lite-версию — по возможностям он лучше. Но: нужен ключ
 * разработчика, то есть регистрация каждым пользователем сборки, и это нативный
 * векторный рендерер, который тянет GPU и десятки мегабайт `.so`. На Cortex-A53
 * с PowerVR GE8322 и реальными двумя гигабайтами это заметная плата.
 * Mapbox вдобавок требует секретный download-токен в `gradle.properties` —
 * его нельзя положить в открытый репозиторий, сборка через Actions сломается.
 *
 * osmdroid рисует растровые тайлы OpenStreetMap обычным `Canvas`: без ключа, без
 * GMS, без нагрузки на GPU и без регистраций. Оболочка работает у любого сразу
 * после установки — для лаунчера головного устройства это важнее пробок.
 *
 * Голосовая навигация по маршруту остаётся за установленным навигатором — он
 * открывается на весь экран, когда действительно нужен. Карта на рабочем столе
 * отвечает за другое: видеть, где ты едешь, не открывая ничего.
 *
 * ## Про тайлы
 *
 * Серверы OpenStreetMap просят приложения представляться — поэтому
 * `userAgentValue` выставляется в имя пакета. Кэш тайлов лежит в приватной
 * `cacheDir`, а не во внешнем хранилище: так не нужно разрешение на запись и
 * ничего не остаётся после удаления приложения.
 */
@android.annotation.SuppressLint("ClickableViewAccessibility")
@Composable
fun EmbeddedMap(
    gps: GpsState,
    accent: Color,
    modifier: Modifier = Modifier,
    /** Тёмная инверсия тайлов — под ночной неоновый интерфейс. */
    nightTiles: Boolean = true,
    /** Следовать за машиной. Сбрасывается, когда пользователь сам двигает карту. */
    follow: Boolean = true,
    onUserPanned: () -> Unit = {},
    zoomRequest: Double = 16.0,
    /** Линия маршрута, посчитанная [com.neondrive.launcher.nav.RouteHub]. */
    route: List<RoutePoint> = emptyList(),
    /** Поворачивать карту по курсу движения вместо «север сверху». */
    rotateByBearing: Boolean = true,
    /** Подстраивать масштаб под скорость. */
    autoZoom: Boolean = true
) {
    val context = LocalContext.current

    // MapView создаётся один раз и живёт в remember, а не заводится внутри
    // factory с записью в state: так нет ни лишней рекомпозиции, ни nullable-
    // состояния, и эффекты ниже всегда имеют дело с готовым объектом.
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Свои кнопки зума не нужны — в панели карты есть чипы «+/−»,
            // а системные оверлеи osmdroid смотрятся чужеродно.
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTilesScaledToDpi(true)
            setUseDataConnection(true)
            setHorizontalMapRepetitionEnabled(false)
            setVerticalMapRepetitionEnabled(false)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(19.0)

            // MapView — обычный ViewGroup, и Android рисует ему полосы прокрутки:
            // у правого края карты появлялась чёрная вертикальная полоса во всю
            // высоту панели. Для карты, которую и так таскают пальцем, скроллбары
            // бессмысленны — выключаем оба.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isScrollbarFadingEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
        }
    }

    // Линия маршрута — отдельный оверлей, который переживает смену маршрута:
    // пересоздавать Polyline на каждое обновление дороже, чем заменить в нём точки.
    val routeOverlay = remember {
        Polyline().apply {
            outlinePaint.strokeWidth = 12f
            outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }

    LaunchedEffect(route, accent) {
        runCatching {
            routeOverlay.outlinePaint.color = accent.toArgb()
            if (route.isEmpty()) {
                routeOverlay.setPoints(emptyList())
                mapView.overlays.remove(routeOverlay)
            } else {
                routeOverlay.setPoints(route.map { GeoPoint(it.lat, it.lon) })
                if (!mapView.overlays.contains(routeOverlay)) {
                    // Линия должна лежать под меткой машины и над тайлами —
                    // достаточно просто добавить её последней, метку рисует Compose
                    // поверх всего MapView.
                    mapView.overlays.add(routeOverlay)
                }
            }
            mapView.invalidate()
        }
    }

    // Ночная инверсия тайлов: дневной OSM в тёмном салоне слепит, а инверсия
    // делает из него почти чёрную карту, попадающую в неоновую палитру оболочки.
    LaunchedEffect(nightTiles) {
        runCatching {
            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (nightTiles) TilesOverlay.INVERT_COLORS else null
            )
            mapView.invalidate()
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                // Любое касание карты означает «смотрю сам» — следование за
                // машиной выключается, иначе карта тут же уезжала бы обратно и
                // подвинуть её было бы невозможно. Слушатель ничего не поглощает
                // (false), штатная обработка жестов osmdroid работает как обычно.
                view.setOnTouchListener { _, _ ->
                    onUserPanned()
                    false
                }
            }
        )

        // Метка машины рисуется композом поверх карты, а не Marker'ом osmdroid:
        // в режиме следования машина всегда в центре, позиционировать нечего, а
        // Canvas дешевле оверлея с битмапом, который osmdroid перерисовывает на
        // каждый сдвиг карты.
        if (follow) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(46.dp)
            ) {
                // Когда карта повёрнута по курсу, машина на ней всегда смотрит
                // вверх — крутить ещё и стрелку значило бы повернуть её дважды.
                val mapIsRotated = rotateByBearing && gps.speedKmh >= 8f
                HeadingArrow(
                    bearingDeg = if (mapIsRotated) 0f else gps.bearingDeg,
                    accent = accent
                )
            }
        }
    }

    // Следование за машиной. animateTo намеренно не используется: на слабом
    // процессоре ГУ анимация на каждое обновление GPS даёт рывки, а выигрыш
    // чисто косметический — точка и так приходит примерно раз в секунду.
    LaunchedEffect(gps.lastLat, gps.lastLon, follow) {
        if (!follow || !gps.hasFix) return@LaunchedEffect
        runCatching { mapView.controller.setCenter(GeoPoint(gps.lastLat, gps.lastLon)) }
    }

    LaunchedEffect(zoomRequest) {
        runCatching { mapView.controller.setZoom(zoomRequest) }
    }

    /*
     * Поворот карты по курсу.
     *
     * osmdroid считает угол против часовой стрелки, азимут GPS — по часовой, отсюда
     * минус. Поворачиваем не на каждый фикс, а при изменении курса больше чем на
     * 4 градуса: поворот перерисовывает все тайлы, и на слабом ГУ дёрганье карты от
     * шума компаса стоит дороже, чем сама навигация. На малой скорости курс от GPS
     * недостоверен — стоя на месте он скачет случайно, поэтому ниже 8 км/ч карта
     * замирает в последнем положении.
     */
    var lastBearing by remember { mutableStateOf(0f) }
    LaunchedEffect(gps.bearingDeg, gps.speedKmh, rotateByBearing, follow) {
        runCatching {
            if (!rotateByBearing || !follow) {
                if (mapView.mapOrientation != 0f) {
                    mapView.mapOrientation = 0f
                    mapView.invalidate()
                }
                return@LaunchedEffect
            }
            if (gps.speedKmh < 8f) return@LaunchedEffect
            val diff = kotlin.math.abs(
                GeoMath.angleDiff(lastBearing.toDouble(), gps.bearingDeg.toDouble())
            )
            if (diff < 4.0) return@LaunchedEffect
            lastBearing = gps.bearingDeg
            mapView.mapOrientation = -gps.bearingDeg
            mapView.invalidate()
        }
    }

    /*
     * Автозум: во дворе нужен масштаб дома, на трассе — километра вперёд. Ступени,
     * а не плавная функция, намеренно: непрерывный пересчёт зума заставлял бы
     * osmdroid перерисовывать тайлы почти постоянно.
     */
    LaunchedEffect(gps.speedKmh.toInt() / 10, autoZoom, follow) {
        if (!autoZoom || !follow) return@LaunchedEffect
        val target = when {
            gps.speedKmh < 20f -> 17.5
            gps.speedKmh < 60f -> 16.5
            gps.speedKmh < 90f -> 15.5
            else -> 14.5
        }
        runCatching { mapView.controller.setZoom(target) }
    }

    DisposableEffect(mapView) {
        runCatching { mapView.onResume() }
        onDispose {
            runCatching { mapView.onPause() }
            runCatching { mapView.onDetach() }
        }
    }
}

/** Стрелка курса в центре карты. */
@Composable
private fun HeadingArrow(bearingDeg: Float, accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        rotate(degrees = bearingDeg) {
            val path = Path().apply {
                moveTo(w / 2f, h * 0.08f)
                lineTo(w * 0.86f, h * 0.92f)
                lineTo(w / 2f, h * 0.72f)
                lineTo(w * 0.14f, h * 0.92f)
                close()
            }
            drawPath(path, color = accent.copy(alpha = 0.85f))
            drawPath(path, color = Color.Black.copy(alpha = 0.55f), style = Stroke(width = 2f))
        }
    }
}

/**
 * Разовая настройка osmdroid. Безопасно вызывать многократно — библиотека держит
 * конфигурацию в синглтоне, повторные вызовы просто перезаписывают те же значения.
 */
private fun configureOsmdroid(context: Context) {
    runCatching {
        val cfg = Configuration.getInstance()
        cfg.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        // Серверы OSM отдают тайлы только приложениям, которые представились.
        cfg.userAgentValue = context.packageName

        // Базовая папка — не приватный cacheDir, а внешняя папка приложения
        // (Android/data/<пакет>/files/osmdroid). Разрешений она не требует, зато
        // видна с компьютера по USB, и это важно: машина далеко не всегда в сети,
        // а osmdroid умеет брать тайлы из офлайн-архива (.mbtiles, .sqlite, .zip),
        // просто лежащего в этой папке. Пользователь может один раз скинуть туда
        // карту своего региона — и карта будет работать вообще без интернета.
        val root = context.getExternalFilesDir(null) ?: context.cacheDir
        val base = File(root, "osmdroid")
        runCatching { base.mkdirs() }
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles")
        // Размеры кэша и число потоков загрузки оставлены заводскими: у osmdroid
        // они и так рассчитаны на слабые устройства (небольшой кэш в памяти, два
        // потока), а типы у этих настроек — short, и подгонять их ради пары
        // мегабайт смысла нет.
    }
}
