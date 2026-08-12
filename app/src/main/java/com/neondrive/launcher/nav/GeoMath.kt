package com.neondrive.launcher.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Геометрия для ведения по маршруту.
 *
 * Всё считается в метрах на локальной плоской проекции: на масштабах, с которыми
 * работает навигация (сотни метров до манёвра, десятки метров до линии маршрута),
 * кривизна Земли даёт погрешность заметно меньше точности самого GPS. Настоящая
 * сферическая математика тут только в [distanceM] — она вызывается и на длинных
 * отрезках, где разница уже есть.
 */
object GeoMath {

    private const val EARTH_R = 6_371_000.0

    /** Расстояние между двумя точками по формуле гаверсинуса, м. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Азимут из точки 1 в точку 2, градусы 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Ближайшая точка маршрута к текущей позиции.
     *
     * Возвращает индекс отрезка и расстояние до линии в метрах. Именно по этому
     * расстоянию определяется сход с маршрута, поэтому меряется расстояние до
     * ОТРЕЗКА, а не до ближайшей вершины: на длинной прямой вершины могут стоять
     * через сотни метров, и расстояние до вершины показало бы «сход» там, где
     * машина едет ровно по дороге.
     *
     * [fromIndex] — подсказка «мы были примерно здесь». Поиск идёт в окне вокруг
     * неё, а не по всему треку: движение почти монотонно вперёд, и просмотр всего
     * маршрута на каждый фикс GPS — лишняя работа для процессора ГУ.
     *
     * ## Почему окно, а не «от подсказки и до конца»
     *
     * Раньше поиск начинался ровно с [fromIndex] и шёл только вперёд. Один
     * выброс GPS — и подсказка уезжала на десятки отрезков вперёд, вернуться
     * назад она уже не могла НИКОГДА: реальная позиция оставалась позади окна
     * поиска, привязка липла к случайному куску трассы, а вместе с ней уезжали и
     * остаток пути, и текущий манёвр. Внешне это и выглядело как «навигатор
     * потерялся»: маршрут нарисован, машина едет, а подсказки не про этот
     * перекрёсток.
     *
     * Поэтому окно смотрит и немного назад ([BACK_SEGMENTS]), и ограниченно
     * вперёд ([FWD_SEGMENTS]) — а если в окне ничего близкого не нашлось, трек
     * пересматривается целиком. Полный проход раз в секунду по нескольким тысячам
     * точек дешевле, чем потерянное ведение.
     */
    fun nearestOnRoute(
        points: List<RoutePoint>,
        lat: Double,
        lon: Double,
        fromIndex: Int = 0
    ): Nearest {
        if (points.size < 2) return Nearest(0, Double.MAX_VALUE, 0.0)

        val latScale = 111_320.0
        val lonScale = 111_320.0 * cos(Math.toRadians(lat))
        val px = lon * lonScale
        val py = lat * latScale

        val hint = fromIndex.coerceIn(0, points.size - 2)
        val from = max(0, hint - BACK_SEGMENTS)
        val to = min(points.size - 2, hint + FWD_SEGMENTS)

        val windowed = scan(points, px, py, latScale, lonScale, from, to)
        // Привязка в окне убедительная — дальше не смотрим.
        if (windowed.distanceM <= RESCAN_M) return windowed

        // Иначе честно пересматриваем весь трек: возможно, машина вернулась на
        // маршрут в другом месте или подсказка успела уехать.
        val full = scan(points, px, py, latScale, lonScale, 0, points.size - 2)
        return if (full.distanceM < windowed.distanceM) full else windowed
    }

    /** Сколько отрезков назад и вперёд от подсказки просматривать. */
    private const val BACK_SEGMENTS = 12
    private const val FWD_SEGMENTS = 250

    /** Хуже этого расстояния до линии привязка считается неубедительной. */
    private const val RESCAN_M = 50.0

    private fun scan(
        points: List<RoutePoint>,
        px: Double,
        py: Double,
        latScale: Double,
        lonScale: Double,
        from: Int,
        to: Int
    ): Nearest {
        var bestIndex = from
        var bestDist = Double.MAX_VALUE
        var bestT = 0.0

        for (i in from..to) {
            val ax = points[i].lon * lonScale
            val ay = points[i].lat * latScale
            val bx = points[i + 1].lon * lonScale
            val by = points[i + 1].lat * latScale

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq <= 0.0) 0.0 else
                (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)

            val cx = ax + t * dx
            val cy = ay + t * dy
            val d = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))

            if (d < bestDist) {
                bestDist = d
                bestIndex = i
                bestT = t
            }
        }

        return Nearest(bestIndex, bestDist, bestT)
    }

    data class Nearest(
        /** Индекс начала отрезка маршрута. */
        val segmentIndex: Int,
        /** Расстояние от точки до линии маршрута, м. */
        val distanceM: Double,
        /** Положение проекции внутри отрезка, 0..1. */
        val t: Double
    )

    /** Длина маршрута от проекции текущей позиции до конца, м. */
    fun remainingAlong(points: List<RoutePoint>, nearest: Nearest): Double {
        if (points.size < 2) return 0.0
        val i = nearest.segmentIndex.coerceIn(0, points.size - 2)
        var total = 0.0
        // Хвост текущего отрезка
        val a = points[i]
        val b = points[i + 1]
        total += distanceM(a.lat, a.lon, b.lat, b.lon) * (1.0 - nearest.t)
        for (k in i + 1 until points.size - 1) {
            total += distanceM(points[k].lat, points[k].lon, points[k + 1].lat, points[k + 1].lon)
        }
        return total
    }

    /**
     * Накопленная длина трека: `cum[i]` — сколько метров от начала маршрута до
     * точки `i`. Считается один раз на маршрут; дальше любое «сколько проехали» и
     * «сколько до манёвра» — это вычитание двух чисел, а не проход по списку.
     */
    fun cumulative(points: List<RoutePoint>): DoubleArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + distanceM(
                points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon
            )
        }
        return cum
    }

    /** Сколько метров вдоль маршрута пройдено до проекции [nearest]. */
    fun alongOf(cum: DoubleArray, nearest: Nearest): Double {
        if (cum.size < 2) return 0.0
        val i = nearest.segmentIndex.coerceIn(0, cum.size - 2)
        return cum[i] + (cum[i + 1] - cum[i]) * nearest.t
    }

    /** Сама точка проекции — куда «прилипает» машина на линии маршрута. */
    fun pointAt(points: List<RoutePoint>, nearest: Nearest): RoutePoint {
        if (points.size < 2) return RoutePoint(0.0, 0.0)
        val i = nearest.segmentIndex.coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        return RoutePoint(
            lat = a.lat + (b.lat - a.lat) * nearest.t,
            lon = a.lon + (b.lon - a.lon) * nearest.t
        )
    }

    /** Направление участка маршрута под машиной, градусы. */
    fun bearingAt(points: List<RoutePoint>, nearest: Nearest): Double {
        if (points.size < 2) return 0.0
        val i = nearest.segmentIndex.coerceIn(0, points.size - 2)
        val a = points[i]
        val b = points[i + 1]
        return bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }

    /**
     * Часть маршрута, которую ещё предстоит проехать: проекция машины плюс всё,
     * что дальше по треку.
     *
     * Именно это рисуется на карте. Пройденный хвост не нужен — он не несёт
     * информации, но тянется за машиной и на городском маршруте перекрывает
     * половину экрана линией, по которой уже проехали.
     */
    fun routeAhead(points: List<RoutePoint>, segmentIndex: Int, t: Double): List<RoutePoint> =
        routeAhead(points, Nearest(segmentIndex, 0.0, t))

    fun routeAhead(points: List<RoutePoint>, nearest: Nearest): List<RoutePoint> {
        if (points.size < 2) return points
        val i = nearest.segmentIndex.coerceIn(0, points.size - 2)
        val head = pointAt(points, nearest)
        val out = ArrayList<RoutePoint>(points.size - i)
        out.add(head)
        for (k in i + 1 until points.size) out.add(points[k])
        return out
    }

    /** Разница азимутов со знаком, −180..180. */
    fun angleDiff(from: Double, to: Double): Double {
        var d = (to - from + 540.0) % 360.0 - 180.0
        if (abs(d) < 1e-9) d = 0.0
        return d
    }

    /** Округление расстояния до «человеческого» шага для озвучки. */
    fun roundForSpeech(meters: Double): Int = when {
        meters >= 1000 -> (meters / 500).toInt() * 500
        meters >= 200 -> (meters / 100).toInt() * 100
        meters >= 50 -> (meters / 50).toInt() * 50
        else -> max(10, min(50, (meters / 10).toInt() * 10))
    }
}
