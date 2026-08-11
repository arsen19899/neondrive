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
     * [fromIndex] позволяет не искать заново с начала маршрута: движение
     * монотонно вперёд, и просмотр всего трека на каждый фикс GPS — лишняя работа
     * для процессора ГУ.
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

        var bestIndex = fromIndex.coerceIn(0, points.size - 2)
        var bestDist = Double.MAX_VALUE
        var bestT = 0.0

        for (i in bestIndex until points.size - 1) {
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
            // Дальше уходить нет смысла: если уже нашли что-то близкое, а
            // расстояние начало расти на километр — впереди другой участок
            // маршрута (петля, разворот), и он не наш.
            if (bestDist < 30.0 && d > bestDist + 1000.0) break
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
