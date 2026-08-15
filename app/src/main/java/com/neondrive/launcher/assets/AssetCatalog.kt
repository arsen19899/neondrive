package com.neondrive.launcher.assets

import android.content.Context
import com.neondrive.launcher.nav.HazardHub
import com.neondrive.launcher.nav.OfflineMap
import com.neondrive.launcher.voice.VoskEngine
import java.io.File

/** Что делать с файлом после того, как он скачался. */
enum class AssetKind {
    /** Положить как есть. */
    FILE,

    /** Распаковать zip в целевую папку и удалить архив. */
    ARCHIVE,

    /** Не файл, а запрос к Overpass: ответ сохраняется как CSV. */
    OVERPASS_CSV
}

/**
 * Один докачиваемый файл.
 *
 * [urls] — список адресов, а не один. Причина простая: все источники здесь
 * бесплатные и общественные, живут на пожертвованиях и отваливаются
 * поодиночке. Тот же приём и по той же причине, что со списком зеркал Overpass
 * в [HazardHub] и OSRM в RouteHub. Адреса перебираются по очереди до первого
 * ответившего.
 *
 * [approxBytes] нужен до начала закачки — чтобы показать человеку размер в
 * диалоге согласия и заранее проверить, хватит ли места. Точный размер придёт
 * в заголовке Content-Length, но спрашивать разрешение надо ДО запроса.
 */
data class Asset(
    val id: String,
    val title: String,
    val description: String,
    val kind: AssetKind,
    val urls: List<String>,
    val approxBytes: Long,
    /** Куда класть. Папка создаётся сама. */
    val targetDir: (Context) -> File,
    /** Имя файла в целевой папке. Для [AssetKind.ARCHIVE] — имя временного архива. */
    val fileName: String,
    /** Уже установлено? */
    val isInstalled: (Context) -> Boolean,
    /** Тело запроса для [AssetKind.OVERPASS_CSV]. */
    val overpassQuery: String = "",
    /** Адрес страницы, куда отправить человека, если автозагрузка не удалась. */
    val manualUrl: String = ""
) {
    val approxMb: Int get() = (approxBytes / (1024L * 1024L)).toInt()
}

/**
 * Всё, что оболочка умеет докачать сама.
 *
 * ## Почему это вообще нужно
 *
 * Три файла не помещаются в APK: карта страны — 304 МБ, модель распознавания
 * речи — 45 МБ, база камер меняется чаще, чем выходят сборки. Раньше их
 * приходилось скачивать на компьютере и закидывать по USB — то есть человек,
 * поставивший APK на магнитолу прямо в машине, половину функций получить не
 * мог в принципе.
 *
 * ## Чего здесь нет и почему
 *
 * Графа маршрутов GraphHopper. Его нельзя скачать — он не существует в готовом
 * виде: граф собирается из карты OSM импортом, который требует нескольких
 * гигабайт оперативной памяти и десятков минут работы процессора. На магнитоле
 * этого нет, а готовых графов под конкретную версию GraphHopper никто в сети не
 * публикует. Он и остаётся единственным, что делается на компьютере скриптом
 * `build-graph.bat`.
 */
object AssetCatalog {

    /* ─────────────────  КАРТЫ  ───────────────── */

    /**
     * Страны, чьи карты можно скачать.
     *
     * Список короткий и это сознательно: Беларусь и соседи, то есть всё, куда
     * реально доезжают из Минска на машине. Карты складываются в одну папку и
     * показываются как одна карта, так что перед поездкой достаточно докачать
     * ещё одну страну.
     *
     * Размеры приблизительные, по состоянию сервера mapsforge — точный придёт в
     * Content-Length. Здесь они нужны только чтобы человек до нажатия понимал,
     * во что ввязывается.
     */
    private val MAP_REGIONS = listOf(
        Triple("belarus", "Беларусь", 320L),
        Triple("poland", "Польша", 900L),
        Triple("lithuania", "Литва", 180L),
        Triple("latvia", "Латвия", 150L),
        Triple("ukraine", "Украина", 700L)
    )

    fun mapAssets(): List<Asset> = MAP_REGIONS.map { (slug, title, mb) ->
        Asset(
            id = "map-$slug",
            title = "Карта: $title",
            description = "Векторная карта OpenStreetMap для навигации без интернета",
            kind = AssetKind.FILE,
            urls = listOf(
                "https://download.mapsforge.org/maps/v5/europe/$slug.map",
                "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/europe/$slug.map"
            ),
            approxBytes = mb * 1024L * 1024L,
            targetDir = { ctx -> OfflineMap.mapFolder(ctx) },
            fileName = "$slug.map",
            isInstalled = { ctx -> File(OfflineMap.mapFolder(ctx), "$slug.map").length() > 1_000_000L },
            manualUrl = "https://download.mapsforge.org/maps/v5/europe/"
        )
    }

    /* ─────────────────  МОДЕЛЬ РАСПОЗНАВАНИЯ РЕЧИ  ───────────────── */

    /**
     * Русская модель Vosk для голосовых команд «Елисей».
     *
     * Именно маленькая (`small`), а не большая: большая весит около полутора
     * гигабайт и рассчитана на сервер, а маленькая сделана ровно под такие
     * устройства — держится в памяти целиком и работает в реальном времени на
     * слабом процессоре.
     *
     * Зеркал несколько, потому что имя файла на сайте alphacephei привязано к
     * версии модели и со временем меняется. Если ни один адрес не ответит,
     * оболочка отправит человека на страницу со списком моделей, а не сделает
     * вид, что всё сломалось.
     */
    fun voskModel(): Asset = Asset(
        id = "vosk-ru",
        title = "Модель распознавания речи",
        description = "Русская офлайн-модель Vosk — голосовые команды без интернета",
        kind = AssetKind.ARCHIVE,
        urls = listOf(
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
            "https://huggingface.co/alphacep/vosk-model-small-ru/resolve/main/vosk-model-small-ru-0.22.zip",
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.4.zip"
        ),
        approxBytes = 46L * 1024L * 1024L,
        targetDir = { ctx -> VoskEngine.modelFolder(ctx) },
        fileName = "vosk-model.zip",
        isInstalled = { ctx -> VoskEngine.findModel(ctx) != null },
        manualUrl = "https://alphacephei.com/vosk/models"
    )

    /* ─────────────────  КАМЕРЫ  ───────────────── */

    /**
     * Камеры контроля скорости из OpenStreetMap.
     *
     * Не база от радар-детектора, а выгрузка открытых данных под ODbL: без
     * ключей, без регистрации, без юридических вопросов. Стационарные «Стрелки»
     * в Беларуси размечены в OSM неплохо.
     *
     * Формат ответа задан прямо в запросе — `долгота,широта,maxspeed`, ровно то,
     * что читает [HazardHub]. Третья колонка становится контролируемым
     * ограничением: оболочка вытаскивает из неё цифры. Тега нет — колонка
     * пустая, и камера остаётся без ограничения, что честнее выдуманного числа.
     *
     * Зачем это, если [HazardHub] и так спрашивает Overpass на каждый маршрут:
     * тот запрос идёт по коридору вдоль маршрута и требует сети в момент
     * поездки. Скачанный файл работает в глуши, где сети нет, — а это ровно те
     * места, где о камере узнать больше неоткуда.
     */
    fun cameras(countryCode: String = "BY", countryTitle: String = "Беларусь"): Asset = Asset(
        id = "cameras-$countryCode",
        title = "Камеры: $countryTitle",
        description = "Стационарные камеры контроля скорости из OpenStreetMap",
        kind = AssetKind.OVERPASS_CSV,
        urls = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        ),
        approxBytes = 200L * 1024L,
        targetDir = { ctx -> HazardHub.poiFolder(ctx) },
        fileName = "osm-cameras-${countryCode.lowercase()}.csv",
        isInstalled = { ctx ->
            File(HazardHub.poiFolder(ctx), "osm-cameras-${countryCode.lowercase()}.csv").length() > 100L
        },
        overpassQuery = """
            [out:csv(::lon,::lat,maxspeed;false)][timeout:600];
            area["ISO3166-1"="$countryCode"][admin_level=2]->.a;
            (
              node["highway"="speed_camera"](area.a);
              node["enforcement"="maxspeed"](area.a);
            );
            out;
        """.trimIndent()
    )

    /** Всё разом — для экрана настроек. */
    fun all(): List<Asset> = mapAssets() + voskModel() + cameras()

    fun byId(id: String): Asset? = all().firstOrNull { it.id == id }
}
