package com.neondrive.launcher.nav

import android.app.Application
import android.content.Context
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File

/**
 * Вся карта страны без интернета — из одного векторного файла.
 *
 * ## Почему вектор, а не «скачать тайлы»
 *
 * Растровые тайлы OpenStreetMap на Беларусь до рабочего зума навигации (16–17)
 * — это порядка шестидесяти гигабайт: 3,5 миллиона картинок. Даже урезанный до
 * z14 набор, на котором ещё видны улицы, весит около четырёх гигабайт. Ни в
 * APK, ни на флеш-память бюджетного ГУ это не кладётся, а массовая выкачка с
 * `tile.openstreetmap.org` вдобавок прямо запрещена правилами проекта.
 *
 * Векторная карта того же региона — 304 МБ ОДНИМ файлом, и в нём сразу все
 * зумы: картинка не хранится, а рисуется на устройстве из геометрии дорог.
 * Именно так работают OsmAnd и Locus. Файл кладётся туда же, куда и граф
 * маршрутов ([OfflineRouter]), и по тому же сценарию: скачал на компьютере —
 * скинул по USB.
 *
 * ## Что это даёт вместе с офлайн-графом
 *
 * [OfflineRouter] уже умеет строить маршрут без сети, [HazardHub] держит камеры
 * в своих файлах, поиск по избранному и истории работает офлайн. Не хватало
 * ровно карты — без неё в глуши оболочка показывала серую пустоту с линией
 * маршрута. С этим файлом навигация становится полностью автономной.
 *
 * ## Цена
 *
 * Тайл рисуется на процессоре в момент показа. На Cortex-A53 это заметно
 * медленнее готовой картинки, поэтому нарисованные тайлы складываются в
 * обычный кэш osmdroid: второй проезд по той же улице уже мгновенный.
 * Включается настройкой, а не по факту наличия файла — водитель сам решает,
 * что ему важнее, скорость отрисовки или независимость от сети.
 *
 * ## Где взять файл
 *
 * `https://download.mapsforge.org/maps/v5/europe/belarus.map` — официальный
 * сервер mapsforge, регулярно пересобираемые экстракты OSM по странам.
 * Положить в `Android/data/<пакет>/files/map/`. Файлов может быть несколько:
 * соседние страны просто добавляются рядом и показываются как одна карта.
 */
object OfflineMap {

    /** Куда класть `.map`-файлы. */
    fun mapFolder(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.cacheDir, "map")

    /** Найденные карты. Пусто — файлов нет. */
    fun mapFiles(context: Context): List<File> = runCatching {
        mapFolder(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".map", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }.getOrDefault(emptyList())

    fun isPresent(context: Context): Boolean = mapFiles(context).isNotEmpty()

    /**
     * Почему последняя попытка открыть карту не удалась.
     *
     * Раньше [open] просто возвращал null на любую ошибку, и снаружи «файла
     * нет», «файл битый» и «библиотека не собралась» выглядели одинаково —
     * пустой картой. Отличить их можно было только логом с устройства, до
     * которого в машине не добраться. Теперь причина видна прямо в настройках.
     */
    @Volatile
    var lastError: String = ""
        private set

    /** Человекочитаемый статус для экрана настроек. */
    fun status(context: Context): String {
        val files = mapFiles(context)
        if (files.isEmpty()) {
            return "Карта не найдена. Положите файл .map в Android/data/" +
                "${context.packageName}/files/map/"
        }
        val totalMb = files.sumOf { it.length() } / (1024 * 1024)
        val names = files.joinToString(", ") { it.name }
        val err = lastError
        if (err.isNotBlank()) {
            return "Файл есть ($names, $totalMb МБ), но открыть его не удалось: $err"
        }
        return "Готово: $names — $totalMb МБ, карта работает без интернета"
    }

    /**
     * Открыть карту и собрать поставщик тайлов для [org.osmdroid.views.MapView].
     *
     * Операция ввода-вывода: читает заголовки файлов с флеш-памяти ГУ и
     * запускает поток разбора темы оформления. Вызывать с фонового потока.
     * Возвращает null, если файлов нет или они не читаются — вызывающий код в
     * этом случае остаётся на сетевых тайлах.
     */
    fun open(context: Context): Handle? {
        val files = mapFiles(context)
        if (files.isEmpty()) return null

        val app = context.applicationContext as? Application ?: return null

        return runCatching {
            // Графическая фабрика mapsforge — синглтон на процесс; повторный
            // вызов безопасен, библиотека сама проверяет, что уже создана.
            MapsForgeTileSource.createInstance(app)

            // Намеренно самая старая и стабильная перегрузка из трёх аргументов.
            // У неё же есть вариант с кодом языка подписей, но он появился
            // позже, и завязываться на него ради белорусских названий вместо
            // русских — плохой размен: сломанная сборка хуже подписи «вуліца».
            val source = MapsForgeTileSource.createFromFiles(
                files.toTypedArray(),
                InternalRenderTheme.OSMARENDER,
                THEME_NAME
            )

            // Третий аргумент — куда складывать нарисованные тайлы. null здесь
            // означает «в обычный кэш osmdroid», и это именно то, что нужно:
            // рисование на процессоре ГУ достаточно дорогое, чтобы результат
            // стоило сохранить. Имя темы служит именем тайлсета в кэше, так что
            // с сетевыми тайлами Mapnik они не перемешиваются.
            val provider = MapsForgeTileProvider(
                SimpleRegisterReceiver(app),
                source,
                null
            )
            Handle(provider, source)
        }.onSuccess { lastError = "" }
            .onFailure { e ->
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "без описания")
            }
            .getOrNull()
    }

    /**
     * Открытая карта. Живёт ровно столько же, сколько карта на экране: файл
     * держится открытым, а тема оформления — отдельным потоком, поэтому по
     * закрытии панели всё это надо явно освободить.
     */
    class Handle internal constructor(
        val provider: MapTileProviderBase,
        private val source: MapsForgeTileSource
    ) {
        fun dispose() {
            runCatching { source.dispose() }
        }
    }

    /** Имя темы оформления; оно же — имя тайлсета в кэше osmdroid. */
    private const val THEME_NAME = "neon_offline"
}
