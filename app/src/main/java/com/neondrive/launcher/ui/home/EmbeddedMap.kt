package com.neondrive.launcher.ui.home

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.nav.GeoMath
import com.neondrive.launcher.nav.OfflineMap
import com.neondrive.launcher.nav.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
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
    /**
     * Линия маршрута, посчитанная [com.neondrive.launcher.nav.RouteHub].
     *
     * Сюда приходит уже обрезанный маршрут — только то, что осталось проехать.
     * Пройденный хвост панель отсекает по привязке машины к треку (см.
     * [com.neondrive.launcher.nav.GeoMath.routeAhead]): линия укорачивается за
     * машиной по мере движения и не тянется через полэкрана туда, где мы уже были.
     */
    route: List<RoutePoint> = emptyList(),
    /** Поворачивать карту по курсу движения вместо «север сверху». */
    rotateByBearing: Boolean = true,
    /** Подстраивать масштаб под скорость. */
    autoZoom: Boolean = true,
    /** Рисовать карту из офлайн-файла вместо тайлов из сети. */
    offlineMap: Boolean = false
) {
    val context = LocalContext.current

    /*
     * Офлайн-карта.
     *
     * Открытие — это чтение заголовков файла на триста мегабайт с флеш-памяти
     * ГУ, поэтому оно уходит в фоновый поток, а карта создаётся уже вокруг
     * готового поставщика тайлов. Пока файл открывается (или если его нет),
     * работают обычные сетевые тайлы — переключение происходит само, без
     * пустого экрана в промежутке.
     */
    var offline by remember { mutableStateOf<OfflineMap.Handle?>(null) }
    LaunchedEffect(offlineMap) {
        if (!offlineMap) {
            offline = null
            return@LaunchedEffect
        }
        val opened = withContext(Dispatchers.IO) { OfflineMap.open(context) }
        offline = opened
    }

    /*
     * MapView создаётся ОДИН раз и живёт ровно столько, сколько панель на экране.
     *
     * Здесь раньше стоял `remember(offline)`: карта пересоздавалась, как только
     * офлайн-файл открывался. Так делать нельзя, и именно отсюда бралась серая
     * сетка вместо карты.
     *
     * Во-первых, `AndroidView` вызывает свой `factory` ровно один раз за всё
     * время жизни узла — новый MapView в него уже не попадал, и на экране
     * навсегда оставался первый, сетевой. Во-вторых, и это хуже, эффект
     * `DisposableEffect(mapView)` в конце функции на смену ключа вызывал
     * `onDetach()` у той самой карты, которая осталась на экране, а `onDetach`
     * отключает поставщик тайлов. Дальше карте нечего было рисовать: сетевые
     * тайлы больше не запрашивались, офлайновые уходили в невидимую вторую
     * карту, и оставался пустой фон osmdroid с разметкой — «сетка».
     *
     * Поставщик тайлов меняется на живой карте (`setTileProvider`) — osmdroid
     * это умеет и сам пересобирает слой тайлов.
     */
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

    /*
     * Переключение «сеть ⇄ офлайн-файл» на живой карте.
     *
     * Установка поставщика и освобождение файла сделаны ОДНИМ эффектом
     * намеренно: закрыть mapsforge-файл раньше, чем карта перестала из него
     * читать, — верный способ уронить поток отрисовки тайлов. Здесь сначала на
     * карту возвращается сетевой поставщик, и только потом закрывается файл.
     */
    DisposableEffect(offline, mapView) {
        val handle = offline
        runCatching {
            mapView.setTileProvider(
                handle?.provider
                    ?: MapTileProviderBasic(context.applicationContext, TileSourceFactory.MAPNIK)
            )
            // setTileProvider собирает новый слой тайлов, поэтому ночную
            // инверсию и пределы масштаба надо выставить заново.
            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (nightTiles) TilesOverlay.INVERT_COLORS else null
            )
            mapView.setMinZoomLevel(3.0)
            mapView.setMaxZoomLevel(19.0)
            mapView.invalidate()
        }
        onDispose {
            if (handle != null) {
                runCatching {
                    mapView.setTileProvider(
                        MapTileProviderBasic(
                            context.applicationContext,
                            TileSourceFactory.MAPNIK
                        )
                    )
                }
                runCatching { handle.dispose() }
            }
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

    LaunchedEffect(route, accent, mapView) {
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
    LaunchedEffect(nightTiles, mapView) {
        runCatching {
            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (nightTiles) TilesOverlay.INVERT_COLORS else null
            )
            mapView.invalidate()
        }
    }

    /*
     * Куда смотрит камера. Одно место, из которого центр и масштаб применяются
     * и по новому фиксу GPS, и после КАЖДОЙ смены размера карты.
     *
     * Второе здесь важнее первого. Проекция osmdroid строится от прямоугольника
     * вьюхи (`getIntrinsicScreenRect`), и пока компоновка не прошла, этот
     * прямоугольник пуст: `setCenter` до первой раскладки просто теряется.
     * Именно поэтому карта после возврата из настроек, смены доли экрана под
     * карту или поворота дисплея показывала случайный кусок мира: панель
     * пересоздавалась, центр применялся слишком рано и больше не применялся
     * никогда — эффект по координатам GPS не срабатывает, пока машина стоит.
     * Помогал только зум или «К себе», то есть ручное действие уже по готовой
     * вьюхе. Слушатель компоновки ниже закрывает это полностью.
     */
    val appliedZoom = remember { mutableStateOf(zoomRequest) }
    val camera = rememberUpdatedState(
        CameraTarget(
            follow = follow && gps.hasFix,
            lat = gps.lastLat,
            lon = gps.lastLon,
            zoom = appliedZoom.value
        )
    )

    DisposableEffect(mapView) {
        val listener = android.view.View.OnLayoutChangeListener {
            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = (right - left) != (oldRight - oldLeft) ||
                (bottom - top) != (oldBottom - oldTop)
            if (sizeChanged) {
                // post — чтобы компоновка успела завершиться: применять центр
                // изнутри самой раскладки бессмысленно ровно по той же причине.
                mapView.post { applyCamera(mapView, camera.value) }
            }
        }
        mapView.addOnLayoutChangeListener(listener)
        onDispose { mapView.removeOnLayoutChangeListener(listener) }
    }

    // Сдвиг и зум карты — чтобы метка машины оставалась на своём месте, когда
    // карту таскают пальцем. Считать её экранные координаты можно только после
    // того, как карта переехала, поэтому нужен сигнал от самой карты.
    var mapTick by remember { mutableStateOf(0) }
    DisposableEffect(mapView) {
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                mapTick++
                return false
            }

            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                mapTick++
                // Щипок пальцами меняет масштаб мимо наших эффектов. Запоминаем
                // его здесь, иначе после смены размера панели карта откатилась
                // бы к последнему масштабу, выставленному программно.
                appliedZoom.value = mapView.zoomLevelDouble
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose { runCatching { mapView.removeMapListener(listener) } }
    }

    // Состояние жеста живёт между вызовами update, поэтому не в лямбде.
    val drag = remember { DragState() }
    val touchSlop = remember(context) {
        android.view.ViewConfiguration.get(context).scaledTouchSlop
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                /*
                 * «Смотрю сам» — это перетаскивание или щипок, а не любое
                 * касание. Раньше следование выключалось на первом же ACTION_DOWN:
                 * достаточно было случайно мазнуть по карте, и она переставала
                 * ездить за машиной, а метка при этом ещё и исчезала. Теперь
                 * нужен настоящий сдвиг пальца дальше системного порога или
                 * второй палец на экране.
                 *
                 * Слушатель ничего не поглощает (false) — штатная обработка
                 * жестов osmdroid работает как обычно.
                 */
                view.setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            drag.downX = ev.x
                            drag.downY = ev.y
                            drag.panned = false
                        }
                        android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                            // Два пальца — это зум или поворот, всегда «смотрю сам».
                            if (!drag.panned) {
                                drag.panned = true
                                onUserPanned()
                            }
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (!drag.panned &&
                                (kotlin.math.abs(ev.x - drag.downX) > touchSlop ||
                                    kotlin.math.abs(ev.y - drag.downY) > touchSlop)
                            ) {
                                drag.panned = true
                                onUserPanned()
                            }
                        }
                    }
                    false
                }
            }
        )

        /*
         * Метка машины рисуется композом поверх карты, а не Marker'ом osmdroid:
         * Canvas дешевле оверлея с битмапом, который osmdroid перерисовывает на
         * каждый сдвиг карты.
         *
         * В режиме следования машина всегда ровно в центре — позиционировать
         * нечего. Когда карту отвели в сторону, метка считается через проекцию:
         * раньше она в этом случае просто пропадала с экрана, и понять, где ты
         * находишься, было нельзя вообще — только вернуться кнопкой.
         */
        val car = remember(mapTick, gps.lastLat, gps.lastLon, gps.hasFix, follow) {
            if (follow || !gps.hasFix) null else runCatching {
                val p = mapView.projection.toPixels(GeoPoint(gps.lastLat, gps.lastLon), null)
                // toPixels поворот карты не учитывает — его накладывают отдельно.
                val r = mapView.projection.rotateAndScalePoint(p.x, p.y, null)
                IntOffset(r.x, r.y)
            }.getOrNull()
        }

        // Когда карта повёрнута по курсу, машина на ней смотрит вверх: поворот
        // экрана уже учёл курс, и крутить ещё и стрелку значило бы повернуть её
        // дважды. Одна формула на оба случая — ориентация карты нулевая, когда
        // поворот выключен.
        val arrowDeg = gps.bearingDeg + mapView.mapOrientation

        if (car == null) {
            if (follow) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(MARKER_SIZE)
                ) { HeadingArrow(bearingDeg = arrowDeg, accent = accent) }
            }
        } else {
            val half = with(LocalDensity.current) { (MARKER_SIZE / 2).roundToPx() }
            Box(
                Modifier
                    .offset { IntOffset(car.x - half, car.y - half) }
                    .size(MARKER_SIZE)
            ) { HeadingArrow(bearingDeg = arrowDeg, accent = accent) }
        }
    }

    // Следование за машиной. animateTo намеренно не используется: на слабом
    // процессоре ГУ анимация на каждое обновление GPS даёт рывки, а выигрыш
    // чисто косметический — точка и так приходит примерно раз в секунду.
    LaunchedEffect(gps.lastLat, gps.lastLon, follow, gps.hasFix) {
        applyCamera(mapView, camera.value)
    }

    LaunchedEffect(zoomRequest) {
        appliedZoom.value = zoomRequest
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
        appliedZoom.value = target
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

/** Размер метки машины. Вынесен: по нему же считается её смещение на экране. */
private val MARKER_SIZE = 46.dp

/** Куда должна смотреть карта. */
private data class CameraTarget(
    val follow: Boolean,
    val lat: Double,
    val lon: Double,
    val zoom: Double
)

/**
 * Применить центр и масштаб к карте.
 *
 * Пока вьюха не разложена, у проекции нет размера и центр применить нельзя —
 * поэтому вызов до первой компоновки просто откладывается: его повторит
 * слушатель компоновки, когда размер появится.
 */
private fun applyCamera(view: MapView, target: CameraTarget) {
    if (view.width == 0 || view.height == 0) return
    runCatching {
        view.controller.setZoom(target.zoom)
        if (target.follow) {
            view.controller.setCenter(GeoPoint(target.lat, target.lon))
        }
    }
}

/** Состояние жеста между вызовами обработчика касаний. */
private class DragState {
    var downX = 0f
    var downY = 0f
    var panned = false
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
