package com.neondrive.launcher.media

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Поиск интернет-радиостанций через публичный каталог radio-browser.info.
 * Открытый API, ключ не нужен — только вежливый User-Agent и таймауты, чтобы
 * зависший хост не подвесил экран поиска.
 *
 * Вызывать строго не с главного потока (см. [PlayerHub.searchRadioStations]).
 */
object RadioBrowserApi {

    data class Result(
        val name: String,
        val streamUrl: String,
        val genre: String,
        val country: String,
        val bitrate: Int
    )

    // Несколько зеркал: у сервиса нет единого стабильного хоста, DNS отдаёт
    // случайный набор серверов сообщества. Пробуем по очереди, пока один не ответит.
    private val MIRRORS = listOf(
        "https://de1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://fr1.api.radio-browser.info"
    )

    fun search(query: String, limit: Int = 40): List<Result> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        for (host in MIRRORS) {
            val url = "$host/json/stations/search?name=$encoded&limit=$limit" +
                "&hidebroken=true&order=clickcount&reverse=true"
            val result = runCatching { fetch(url) }.getOrNull()
            if (result != null) return result
        }
        return emptyList()
    }

    private fun fetch(urlStr: String): List<Result> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", "NeonDrive/1.0 (Android head-unit launcher)")
        }
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val out = ArrayList<Result>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val streamUrl = o.optString("url_resolved").ifBlank { o.optString("url") }
                val name = o.optString("name").trim()
                if (streamUrl.isBlank() || name.isBlank()) continue
                out.add(
                    Result(
                        name = name,
                        streamUrl = streamUrl,
                        genre = o.optString("tags").split(",").map { it.trim() }
                            .firstOrNull { it.isNotBlank() }.orEmpty(),
                        country = o.optString("country"),
                        bitrate = o.optInt("bitrate", 0)
                    )
                )
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
