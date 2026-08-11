package com.neondrive.launcher.nav

import android.content.Context
import java.io.File

/**
 * Офлайн-маршрутизация по заранее скачанному графу GraphHopper.
 *
 * ## Зачем
 *
 * В машине интернета может не быть вовсе: роуминг, глушь, севший телефон-точка.
 * Тайлы карты хотя бы кэшируются, а маршрут без сети сейчас не построить совсем —
 * OSRM живёт на сервере. Офлайн-граф закрывает именно эту дыру: один раз скачал
 * Беларусь, дальше маршруты строятся на самом ГУ.
 *
 * ## Почему через рефлексию
 *
 * GraphHopper — тяжёлая библиотека (несколько мегабайт кода плюс Jackson, JTS,
 * protobuf), и её появление в сборке заметно влияет на всё приложение: размер
 * APK, количество методов в dex, время сборки. Проверить это можно только на
 * живой сборке, а ломать ради необязательной функции работающую оболочку нельзя.
 *
 * Поэтому связь с библиотекой — рефлексивная, а зависимость в `app/build.gradle.kts`
 * лежит закомментированной. Пока её не раскомментировали, этот класс честно
 * отвечает «офлайн-роутер недоступен», и оболочка молча работает через сеть, как
 * и раньше. Раскомментировали, собрали, положили граф — заработало, без единой
 * правки в коде.
 *
 * ## Как получить граф
 *
 * Граф строится на компьютере, а не на магнитоле: импорт `.osm.pbf` Беларуси
 * (~250 МБ) требует нескольких гигабайт оперативной памяти, которых на ГУ нет.
 *
 * ```bash
 * # на компьютере, один раз
 * wget https://download.geofabrik.de/europe/belarus-latest.osm.pbf
 * wget https://github.com/graphhopper/graphhopper/releases/download/1.0/graphhopper-web-1.0.jar
 * java -Xmx4g -jar graphhopper-web-1.0.jar import belarus-latest.osm.pbf
 * # получится папка belarus-latest-gh/ — её и копируем
 * ```
 *
 * Папку `belarus-latest-gh` целиком положить в
 * `Android/data/com.neondrive.launcher/files/graph/` на магнитоле (видно по USB).
 * Версия graphhopper-web при импорте обязана совпадать с версией библиотеки в
 * сборке: формат графа между версиями несовместим.
 */
object OfflineRouter {

    private const val GH_CLASS = "com.graphhopper.GraphHopper"

    @Volatile
    private var hopper: Any? = null

    @Volatile
    private var triedLoad = false

    /** Папка, куда класть распакованный граф. */
    fun graphFolder(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.cacheDir, "graph")

    /** Есть ли библиотека в сборке (зависимость раскомментирована). */
    fun libraryPresent(): Boolean = runCatching { Class.forName(GH_CLASS) }.isSuccess

    /** Лежит ли на устройстве похожий на граф каталог. */
    fun graphPresent(context: Context): Boolean = runCatching {
        val root = graphFolder(context)
        if (!root.isDirectory) return false
        // У графа GraphHopper внутри всегда есть файл properties — по нему и
        // отличаем настоящий каталог от случайно созданной пустой папки.
        root.walkTopDown().maxDepth(2).any { it.isFile && it.name == "properties" }
    }.getOrDefault(false)

    /** Готов ли офлайн-роутер отвечать на запросы. */
    fun isReady(context: Context): Boolean = libraryPresent() && graphPresent(context) && load(context)

    /** Человекочитаемый статус для экрана настроек. */
    fun status(context: Context): String = when {
        !libraryPresent() ->
            "Библиотека не включена в сборку — раскомментируйте graphhopper в app/build.gradle.kts"
        !graphPresent(context) ->
            "Граф не найден. Положите папку с графом в Android/data/" +
                "${context.packageName}/files/graph/"
        load(context) -> "Готов: маршруты строятся без интернета"
        else -> "Граф найден, но не загрузился — вероятно, он собран другой версией GraphHopper"
    }

    /**
     * Загрузить граф. Первый вызов занимает секунды (чтение индексов с флеш-памяти
     * ГУ), поэтому результат кэшируется, а сам вызов должен идти с фонового потока.
     */
    @Synchronized
    private fun load(context: Context): Boolean {
        hopper?.let { return true }
        if (triedLoad) return false
        triedLoad = true
        return runCatching {
            val dir = graphFolder(context).walkTopDown().maxDepth(2)
                .firstOrNull { it.isFile && it.name == "properties" }
                ?.parentFile ?: return false

            val cls = Class.forName(GH_CLASS)
            val instance = cls.getDeclaredConstructor().newInstance()
            cls.getMethod("setGraphHopperLocation", String::class.java)
                .invoke(instance, dir.absolutePath)
            // Импорт на устройстве невозможен по памяти — только загрузка готового.
            val loaded = cls.getMethod("load", String::class.java)
                .invoke(instance, dir.absolutePath) as? Boolean ?: false
            if (loaded) hopper = instance
            loaded
        }.getOrDefault(false)
    }

    /**
     * Построить маршрут офлайн. Возвращает null, если роутер не готов или маршрут
     * не найден — вызывающий код в этом случае идёт в сеть.
     */
    fun route(
        context: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): RouteOption? {
        if (!isReady(context)) return null
        val gh = hopper ?: return null

        return runCatching {
            val reqCls = Class.forName("com.graphhopper.GHRequest")
            val request = reqCls
                .getConstructor(
                    Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType, Double::class.javaPrimitiveType
                )
                .newInstance(fromLat, fromLon, toLat, toLon)

            reqCls.getMethod("setProfile", String::class.java).invoke(request, "car")
            reqCls.getMethod("setLocale", java.util.Locale::class.java)
                .invoke(request, java.util.Locale("ru"))

            val response = gh.javaClass.getMethod("route", reqCls).invoke(gh, request)
                ?: return null
            val hasErrors = response.javaClass.getMethod("hasErrors").invoke(response) as? Boolean
            if (hasErrors != false) return null

            val best = response.javaClass.getMethod("getBest").invoke(response) ?: return null
            parsePath(best)
        }.getOrNull()
    }

    /** Разбор GraphHopper `ResponsePath` в наш [RouteOption]. */
    private fun parsePath(path: Any): RouteOption? {
        val cls = path.javaClass

        val pointList = cls.getMethod("getPoints").invoke(path) ?: return null
        val size = pointList.javaClass.getMethod("getSize").invoke(pointList) as? Int ?: return null
        val getLat = pointList.javaClass.getMethod("getLat", Int::class.javaPrimitiveType)
        val getLon = pointList.javaClass.getMethod("getLon", Int::class.javaPrimitiveType)

        val points = ArrayList<RoutePoint>(size)
        for (i in 0 until size) {
            val lat = getLat.invoke(pointList, i) as? Double ?: continue
            val lon = getLon.invoke(pointList, i) as? Double ?: continue
            points += RoutePoint(lat, lon)
        }
        if (points.size < 2) return null

        val distance = cls.getMethod("getDistance").invoke(path) as? Double ?: 0.0
        val timeMs = cls.getMethod("getTime").invoke(path) as? Long ?: 0L

        // Инструкции GraphHopper уже локализованы — мы просили Locale("ru"), так
        // что переводить типы манёвров вручную, как для OSRM, здесь не нужно.
        val steps = ArrayList<RouteStep>(32)
        runCatching {
            val instructions = cls.getMethod("getInstructions").invoke(path)
            val listSize = instructions!!.javaClass.getMethod("size").invoke(instructions) as Int
            val get = instructions.javaClass.getMethod("get", Int::class.javaPrimitiveType)
            for (i in 0 until listSize) {
                val ins = get.invoke(instructions, i) ?: continue
                val insCls = ins.javaClass
                // getTurnDescription требует объект Translation, сигнатура которого
                // менялась между версиями GraphHopper. Собираем фразу сами из знака
                // манёвра и названия улицы — так оно и от версии не зависит, и
                // формулировки совпадают с онлайн-роутером.
                val name = insCls.getMethod("getName").invoke(ins) as? String ?: ""
                val dist = insCls.getMethod("getDistance").invoke(ins) as? Double ?: 0.0
                val time = insCls.getMethod("getTime").invoke(ins) as? Long ?: 0L
                val sign = insCls.getMethod("getSign").invoke(ins) as? Int ?: 0
                val pts = insCls.getMethod("getPoints").invoke(ins)
                val pLat = pts?.javaClass?.getMethod("getLat", Int::class.javaPrimitiveType)
                    ?.invoke(pts, 0) as? Double ?: continue
                val pLon = pts.javaClass.getMethod("getLon", Int::class.javaPrimitiveType)
                    .invoke(pts, 0) as? Double ?: continue

                val (type, modifier) = signToOsrm(sign)
                steps += RouteStep(
                    instruction = buildInstruction(sign, name),
                    streetName = name,
                    maneuverLat = pLat,
                    maneuverLon = pLon,
                    distanceM = dist,
                    durationSec = time / 1000.0,
                    type = type,
                    modifier = modifier
                )
            }
        }

        return RouteOption(
            points = points,
            steps = steps,
            // Ограничений скорости офлайн-граф в готовом виде не отдаёт: они есть
            // в графе, но достаются через внутренние API, несовместимые между
            // версиями. Знак ограничения в офлайне просто не показывается.
            maxspeeds = emptyList(),
            distanceM = distance,
            durationSec = timeMs / 1000.0
        )
    }

    /**
     * Числовой «знак» GraphHopper в пару «тип + уточнение» OSRM — чтобы стрелка на
     * карточке манёвра рисовалась одним и тем же кодом для обоих роутеров.
     */
    private fun signToOsrm(sign: Int): Pair<String, String> = when (sign) {
        -98 -> "turn" to "uturn"
        -8 -> "turn" to "uturn"
        -7 -> "continue" to "straight"      // keep left
        -3 -> "turn" to "sharp left"
        -2 -> "turn" to "left"
        -1 -> "turn" to "slight left"
        0 -> "continue" to "straight"
        1 -> "turn" to "slight right"
        2 -> "turn" to "right"
        3 -> "turn" to "sharp right"
        4 -> "arrive" to ""
        5 -> "arrive" to ""
        6 -> "roundabout" to ""
        7 -> "continue" to "straight"       // keep right
        8 -> "turn" to "uturn"
        else -> "continue" to "straight"
    }

    private fun buildInstruction(sign: Int, street: String): String {
        val base = when (sign) {
            -8, 8, -98 -> "Развернитесь"
            -7 -> "Держитесь левее"
            -3 -> "Резко налево"
            -2 -> "Поверните налево"
            -1 -> "Плавно налево"
            0 -> "Продолжайте движение"
            1 -> "Плавно направо"
            2 -> "Поверните направо"
            3 -> "Резко направо"
            4, 5 -> return "Вы приехали"
            6 -> "Двигайтесь по кругу"
            7 -> "Держитесь правее"
            else -> "Продолжайте движение"
        }
        return if (street.isNotBlank()) "$base на $street" else base
    }
}
