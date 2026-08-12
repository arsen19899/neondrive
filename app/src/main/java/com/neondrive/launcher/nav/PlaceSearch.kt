package com.neondrive.launcher.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Найденное место. [straightM] — по прямой от текущей позиции, для сортировки и подписи. */
data class Place(
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val straightM: Double
) {
    val distanceLabel: String get() = formatDistance(straightM)
}

/**
 * Категории мест «по дороге». Набор подобран под то, зачем реально сворачивают
 * с маршрута, а не под полноту классификатора OSM.
 *
 * [filters] — куски Overpass QL. Их несколько на категорию, потому что в OSM одна
 * и та же сущность размечается по-разному: кафе это и `amenity=cafe`, и
 * `amenity=fast_food`, а точка может быть узлом (`node`) или контуром здания
 * (`way`), и спрашивать надо про оба.
 */
enum class PlaceCategory(val label: String, val filters: List<String>) {
    FUEL("Заправки", listOf("""["amenity"="fuel"]""")),
    CHARGING("Зарядка", listOf("""["amenity"="charging_station"]""")),
    FOOD("Поесть", listOf("""["amenity"~"restaurant|cafe|fast_food"]""")),
    SHOP("Магазины", listOf("""["shop"~"supermarket|convenience|mall"]""")),
    PARKING("Парковка", listOf("""["amenity"="parking"]""")),
    HOTEL("Ночлег", listOf("""["tourism"~"hotel|motel|guest_house|hostel"]""")),
    REST("Отдых", listOf("""["highway"~"rest_area|services"]""", """["tourism"~"picnic_site|viewpoint"]""")),
    PHARMACY("Аптека", listOf("""["amenity"="pharmacy"]""")),
    BANK("Банкомат", listOf("""["amenity"~"atm|bank"]""")),
    CAR("Автосервис", listOf("""["shop"~"car_repair|tyres"]""", """["amenity"="car_wash"]""")),
    TOILET("Туалет", listOf("""["amenity"="toilets"]"""))
}

/**
 * Поиск мест для собственной навигации оболочки — чтобы задать точку назначения
 * не открывая чужой навигатор.
 *
 * Два независимых механизма под две разные задачи:
 *
 *  • **Текстовый поиск** — [Photon](https://photon.komoot.io/). Он построен именно
 *    под поиск по мере набора: отвечает на обрывок слова, умеет смещать выдачу к
 *    заданной точке (`lat`/`lon`) и не требует ключа. Nominatim остаётся запасным
 *    вариантом: он точнее на полных адресах, но у него жёсткое ограничение в один
 *    запрос в секунду, и для набора по буквам он не предназначен.
 *
 *  • **Поиск по категории** — Overpass API: «все заправки в радиусе N вокруг меня».
 *    Текстовый геокодер такое делает плохо, а Overpass именно для этого и создан.
 *
 * ## Честно про ограничения
 *
 * Это открытые сообществом сервисы, а не коммерческий поиск Яндекса. Полнота и
 * свежесть данных в России ниже: свежий ТЦ или маленькое кафе может отсутствовать,
 * а поиск по названию организации («Пятёрочка на Гагарина») сработает хуже, чем
 * в Яндекс.Картах. Адреса и заметные объекты находятся уверенно.
 *
 * Все публичные инстансы просят вежливого обращения: реальный User-Agent, без
 * шквала запросов. Поэтому в интерфейсе стоит задержка перед отправкой набранного
 * текста, а не запрос на каждую букву.
 */
object PlaceSearch {

    private const val USER_AGENT = "NeonDrive-CarLauncher/1.3 (Android head-unit launcher)"

    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"

    private val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    /* ─────────────────  ТЕКСТОВЫЙ ПОИСК  ───────────────── */

    suspend fun byText(query: String, nearLat: Double, nearLon: Double): List<Place> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < 3) return@withContext emptyList()
            val photon = runCatching { photon(q, nearLat, nearLon) }.getOrNull()
            if (!photon.isNullOrEmpty()) return@withContext photon
            runCatching { nominatim(q, nearLat, nearLon) }.getOrDefault(emptyList())
        }

    private fun photon(q: String, lat: Double, lon: Double): List<Place> {
        val url = PHOTON + "?q=" + URLEncoder.encode(q, "UTF-8") +
            "&lat=$lat&lon=$lon&limit=25&lang=ru"
        val text = get(url) ?: return emptyList()
        val features = JSONObject(text).optJSONArray("features") ?: return emptyList()

        val out = ArrayList<Place>(features.length())
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            // GeoJSON: [долгота, широта]
            val plon = coords.optDouble(0)
            val plat = coords.optDouble(1)
            val p = f.optJSONObject("properties") ?: continue

            // У точки может не быть собственного имени — тогда показываем адрес.
            // Раньше `continue` стоял прямо внутри `ifBlank { }`: выход из цикла
            // изнутри inline-лямбды Kotlin считает экспериментальной возможностью
            // и на этой версии компилятора отказывается собирать. Разворачиваем в
            // обычную проверку — заодно читается яснее.
            val streetAddress = listOfNotNull(
                p.optString("street").ifBlank { null },
                p.optString("housenumber").ifBlank { null }
            ).joinToString(", ")
            val name = p.optString("name").ifBlank { streetAddress }
            if (name.isBlank()) continue

            out += Place(
                name = name,
                subtitle = listOfNotNull(
                    p.optString("street").ifBlank { null }?.takeIf { it != name },
                    p.optString("city").ifBlank { null }
                        ?: p.optString("county").ifBlank { null },
                    p.optString("state").ifBlank { null }
                ).distinct().joinToString(", "),
                lat = plat,
                lon = plon,
                straightM = GeoMath.distanceM(lat, lon, plat, plon)
            )
        }
        return out.sortedBy { it.straightM }
    }

    private fun nominatim(q: String, lat: Double, lon: Double): List<Place> {
        // viewbox смещает выдачу к нам, bounded=0 — не отсекает всё остальное,
        // если рядом ничего похожего нет.
        val d = 0.7
        val url = NOMINATIM + "?format=jsonv2&limit=25&accept-language=ru" +
            "&q=" + URLEncoder.encode(q, "UTF-8") +
            "&viewbox=${lon - d},${lat + d},${lon + d},${lat - d}&bounded=0"
        val text = get(url) ?: return emptyList()
        val arr = org.json.JSONArray(text)
        val out = ArrayList<Place>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val plat = o.optDouble("lat", Double.NaN)
            val plon = o.optDouble("lon", Double.NaN)
            if (plat.isNaN() || plon.isNaN()) continue
            val display = o.optString("display_name")
            out += Place(
                name = o.optString("name").ifBlank { display.substringBefore(",") },
                subtitle = display.substringAfter(",").trim(),
                lat = plat,
                lon = plon,
                straightM = GeoMath.distanceM(lat, lon, plat, plon)
            )
        }
        return out.sortedBy { it.straightM }
    }

    /* ─────────────────  ПОИСК ПО КАТЕГОРИИ  ───────────────── */

    /**
     * Ближайшие места категории вокруг точки. Радиус расширяется автоматически:
     * в городе 5 км хватает с запасом, а на трассе ближайшая заправка может быть
     * за тридцать километров, и пустой список там был бы неправильным ответом.
     */
    suspend fun byCategory(
        category: PlaceCategory,
        lat: Double,
        lon: Double
    ): List<Place> = withContext(Dispatchers.IO) {
        for (radius in listOf(5000, 20000, 60000)) {
            val found = overpassAnyMirror(category, lat, lon, radius)
            if (!found.isNullOrEmpty()) {
                return@withContext found.sortedBy { it.straightM }.take(30)
            }
        }
        emptyList()
    }

    private fun overpassAnyMirror(
        category: PlaceCategory,
        lat: Double,
        lon: Double,
        radiusM: Int
    ): List<Place>? {
        for (mirror in OVERPASS_MIRRORS) {
            val r = runCatching { overpass(mirror, category, lat, lon, radiusM) }.getOrNull()
            if (!r.isNullOrEmpty()) return r
        }
        return null
    }

    private fun overpass(
        baseUrl: String,
        category: PlaceCategory,
        lat: Double,
        lon: Double,
        radiusM: Int
    ): List<Place>? {
        // nwr = node/way/relation разом: половина заправок и магазинов размечена
        // контуром здания, а не точкой. `out center` для контуров отдаёт центр
        // геометрии — именно его и можно вести как точку назначения.
        val body = buildString {
            append("[out:json][timeout:20];(")
            category.filters.forEach { f ->
                append("nwr$f(around:$radiusM,$lat,$lon);")
            }
            append(");out center 60;")
        }

        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 7000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use {
                it.write(("data=" + URLEncoder.encode(body, "UTF-8")).toByteArray())
            }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val elements = JSONObject(text).optJSONArray("elements") ?: return null

            val out = ArrayList<Place>(elements.length())
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val center = el.optJSONObject("center")
                val plat = el.optDouble("lat", center?.optDouble("lat", Double.NaN) ?: Double.NaN)
                val plon = el.optDouble("lon", center?.optDouble("lon", Double.NaN) ?: Double.NaN)
                if (plat.isNaN() || plon.isNaN()) continue

                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name").orEmpty().ifBlank { category.label }
                val street = listOfNotNull(
                    tags?.optString("addr:street")?.ifBlank { null },
                    tags?.optString("addr:housenumber")?.ifBlank { null }
                ).joinToString(", ")
                val brand = tags?.optString("brand").orEmpty()

                out += Place(
                    name = name,
                    subtitle = street.ifBlank { brand }.ifBlank { category.label },
                    lat = plat,
                    lon = plon,
                    straightM = GeoMath.distanceM(lat, lon, plat, plon)
                )
            }
            return out
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /* ─────────────────  СЛУЖЕБНОЕ  ───────────────── */

    private fun get(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 10000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
