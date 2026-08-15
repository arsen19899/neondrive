package com.neondrive.launcher.assets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.neondrive.launcher.MainActivity
import com.neondrive.launcher.R
import com.neondrive.launcher.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * Докачка недостающих файлов: карт, модели распознавания речи, базы камер.
 *
 * ## Почему сервис, а не корутина в экране настроек
 *
 * Карта страны — триста мегабайт. На интернете, раздаваемом с телефона в
 * движущейся машине, это десятки минут. За такое время активити успеет
 * пересоздаться от чего угодно — поворота экрана, нехватки памяти, переключения
 * на навигатор, — и загрузка, привязанная к экрану, оборвалась бы на середине.
 * Сервис переднего плана с уведомлением — единственный способ, которым Android
 * разрешает длинную фоновую работу, и заодно единственный, при котором человек
 * видит, что оболочка что-то качает, даже уйдя с экрана настроек.
 *
 * ## Докачка, а не «начать заново»
 *
 * Связь в машине рвётся постоянно: выехали из зоны, телефон переключил вышку,
 * ушли в тоннель. Файл пишется в `.part` рядом с целью, и при обрыве следующая
 * попытка продолжает с того же места через заголовок `Range`. Без этого триста
 * мегабайт на дорожной связи не скачать никогда: каждый обрыв начинал бы всё
 * сначала.
 *
 * ## Согласие
 *
 * Сервис ничего не решает сам. Он разбирает очередь, в которую файлы кладёт
 * [AssetHub.request] — и только после того, как человек нажал «Скачать» в
 * диалоге, где написано, что именно, откуда и сколько весит. Автоматических
 * загрузок «на всякий случай» здесь нет и быть не должно: интернет в машине
 * почти всегда мобильный и почти всегда платный.
 */
class DownloadService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.neondrive.launcher.DOWNLOAD_START"

        private const val CHANNEL_ID = "neon_download"
        private const val NOTIF_ID = 0x4E44

        private const val USER_AGENT =
            "NeonDrive-CarLauncher/1.5 (Android head-unit launcher)"

        /** Сколько свободного места требовать сверх размера файла. */
        private const val SPACE_MARGIN = 32L * 1024L * 1024L

        private const val BUFFER = 64 * 1024

        /** Как часто обновлять прогресс. Чаще — лишняя работа для слабого ГУ. */
        private const val PROGRESS_INTERVAL_MS = 400L
    }

    private var working = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()
        // startForeground обязан быть вызван в первые секунды после запуска,
        // иначе система убивает сервис с ForegroundServiceDidNotStartInTime.
        startForeground(NOTIF_ID, buildNotification("Загрузка файлов", "Подготовка…", -1))

        if (!working) {
            working = true
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { drain() }
                working = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Разбор очереди по одному файлу. */
    private fun drain() {
        while (true) {
            val id = AssetHub.queue.poll() ?: break
            if (AssetHub.isCancelled(id)) continue
            val asset = AssetCatalog.byId(id) ?: continue

            runCatching { process(asset) }
                .onFailure { e ->
                    if (AssetHub.isCancelled(id)) {
                        AssetHub.update(id) { it.copy(phase = DownloadPhase.CANCELLED) }
                    } else {
                        AssetHub.update(id) {
                            it.copy(
                                phase = DownloadPhase.ERROR,
                                error = e.message ?: "Не удалось скачать"
                            )
                        }
                    }
                }
        }
    }

    private fun process(asset: Asset) {
        when (asset.kind) {
            AssetKind.OVERPASS_CSV -> fetchOverpass(asset)
            AssetKind.FILE, AssetKind.ARCHIVE -> fetchFile(asset)
        }
        AssetHub.update(asset.id) { it.copy(phase = DownloadPhase.DONE) }
        afterInstall(asset)
    }

    /* ─────────────────  ОБЫЧНЫЙ ФАЙЛ  ───────────────── */

    private fun fetchFile(asset: Asset) {
        val dir = asset.targetDir(this).apply { mkdirs() }
        val target = File(dir, asset.fileName)
        val part = File(dir, asset.fileName + ".part")

        var lastError = "Ни один из адресов не ответил"
        for (url in asset.urls) {
            if (AssetHub.isCancelled(asset.id)) throw IOException("Отменено")
            val ok = runCatching { streamTo(asset, url, part, dir) }
                .getOrElse { lastError = it.message ?: "Ошибка сети"; false }
            if (!ok) continue

            when (asset.kind) {
                AssetKind.ARCHIVE -> {
                    AssetHub.update(asset.id) { it.copy(phase = DownloadPhase.UNPACKING) }
                    notify(asset.title, "Распаковка…", -1)
                    unpack(part, dir)
                    part.delete()
                }
                else -> {
                    target.delete()
                    if (!part.renameTo(target)) {
                        throw IOException("Файл скачался, но не переименовался — нет места?")
                    }
                }
            }
            return
        }
        throw IOException(lastError)
    }

    /**
     * Одна попытка скачивания. `true` — файл получен целиком.
     *
     * Возврат `false` вместо исключения на неподходящий ответ сервера сделан
     * намеренно: адресов несколько, и «этот не ответил» — рядовая ситуация, а
     * не ошибка, о которой надо докладывать человеку.
     */
    private fun streamTo(asset: Asset, url: String, part: File, dir: File): Boolean {
        val host = runCatching { URL(url).host }.getOrDefault("")
        AssetHub.update(asset.id) {
            it.copy(phase = DownloadPhase.CONNECTING, sourceHost = host)
        }

        val existing = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
            // Просим продолжить с места обрыва. Сервер вправе не уметь — тогда
            // он ответит 200 и полным файлом, и мы начнём заново.
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return false
            }
            // Сервер, у которого файла нет, часто отдаёт страницу с ошибкой и
            // кодом 200. Без этой проверки в map/ лёг бы HTML на пару килобайт,
            // а оболочка потом сообщала бы о повреждённой карте.
            val type = conn.contentType.orEmpty().lowercase()
            if (type.startsWith("text/html")) return false

            val resuming = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            // getContentLengthLong появился в API 24, а оболочка живёт с 23 —
            // читаем заголовок напрямую.
            val remaining = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (remaining > 0L) {
                if (resuming) existing + remaining else remaining
            } else 0L

            if (total > 0L && dir.usableSpace < total - existing + SPACE_MARGIN) {
                throw IOException(
                    "Не хватает места: нужно ${(total - existing) / 1024 / 1024} МБ"
                )
            }

            if (!resuming && existing > 0L) part.delete()

            AssetHub.update(asset.id) {
                it.copy(
                    phase = DownloadPhase.DOWNLOADING,
                    bytes = if (resuming) existing else 0L,
                    total = total,
                    sourceHost = host
                )
            }

            var written = if (resuming) existing else 0L
            var lastPost = 0L

            conn.inputStream.use { input ->
                FileOutputStream(part, resuming).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        if (AssetHub.isCancelled(asset.id)) throw IOException("Отменено")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPost >= PROGRESS_INTERVAL_MS) {
                            lastPost = now
                            val done = written
                            AssetHub.update(asset.id) { it.copy(bytes = done) }
                            val pct = if (total > 0L) ((done * 100L) / total).toInt() else -1
                            notify(asset.title, "${done / 1024 / 1024} МБ", pct)
                        }
                    }
                }
            }

            // Оборванное соединение выглядит как обычный конец потока, поэтому
            // единственный надёжный признак целостности — совпадение размера.
            if (total > 0L && written < total) {
                throw IOException("Загрузка оборвалась — попробуйте ещё раз, скачанное сохранено")
            }
            if (asset.kind == AssetKind.ARCHIVE && !looksLikeZip(part)) {
                part.delete()
                return false
            }

            AssetHub.update(asset.id) { it.copy(bytes = written, total = maxOf(total, written)) }
            return true
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun looksLikeZip(f: File): Boolean = runCatching {
        f.inputStream().use { it.read() == 0x50 && it.read() == 0x4B }
    }.getOrDefault(false)

    /* ─────────────────  РАСПАКОВКА  ───────────────── */

    /**
     * Распаковать архив в папку.
     *
     * Каждый путь из архива проверяется на выход за пределы целевой папки. Это
     * не паранойя, а известный класс уязвимости («zip slip»): запись вида
     * `../../shared_prefs/что-нибудь` позволила бы подменённому архиву писать
     * куда угодно в данные приложения. Архив приходит по сети с чужого сервера,
     * так что доверять именам внутри него нельзя.
     */
    private fun unpack(zip: File, dir: File) {
        val root = dir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(dir, entry.name)
                if (!out.canonicalPath.startsWith(root + File.separator) &&
                    out.canonicalPath != root
                ) {
                    throw IOException("Архив пытается писать за пределы своей папки")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fo -> zin.copyTo(fo, BUFFER) }
                }
                zin.closeEntry()
            }
        }
    }

    /* ─────────────────  КАМЕРЫ ЧЕРЕЗ OVERPASS  ───────────────── */

    private fun fetchOverpass(asset: Asset) {
        val dir = asset.targetDir(this).apply { mkdirs() }
        val target = File(dir, asset.fileName)

        var lastError = "Ни одно зеркало Overpass не ответило"
        for (mirror in asset.urls) {
            if (AssetHub.isCancelled(asset.id)) throw IOException("Отменено")
            val host = runCatching { URL(mirror).host }.getOrDefault("")
            AssetHub.update(asset.id) {
                it.copy(phase = DownloadPhase.DOWNLOADING, sourceHost = host, total = 0L)
            }
            notify(asset.title, "Запрос к $host…", -1)

            val text = runCatching { postOverpass(mirror, asset.overpassQuery) }
                .getOrElse { lastError = it.message ?: "Ошибка сети"; null }
                ?: continue

            // Overpass на ошибку отвечает страницей, а не CSV. Признак валидного
            // ответа — строки, начинающиеся с числа: это долгота.
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.first().let { c -> c.isDigit() || c == '-' } }
                .toList()

            if (lines.size < 5) {
                lastError = "Зеркало вернуло пустой ответ"
                continue
            }

            target.writeText(lines.joinToString("\n"))
            AssetHub.update(asset.id) {
                it.copy(bytes = target.length(), total = target.length())
            }
            return
        }
        throw IOException(lastError)
    }

    private fun postOverpass(mirror: String, query: String): String {
        val conn = (URL(mirror).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            conn.outputStream.use {
                it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
            }
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /* ─────────────────  ПОСЛЕ УСТАНОВКИ  ───────────────── */

    /**
     * Включить то, ради чего файл качали.
     *
     * Человек, нажавший «скачать карту», хочет ездить по офлайн-карте, а не
     * искать потом тумблер в настройках — «без участия пользователя» ровно про
     * это. Тумблер при этом никуда не девается: рисование векторной карты
     * грузит процессор ГУ, и выключить её обратно можно в любой момент.
     *
     * Ожидание ключевого слова «Елисей» сюда НЕ входит, хотя модель только что
     * скачалась: постоянно открытый микрофон — отдельное решение, которое
     * человек принимает сам, и подсовывать его вместе с загрузкой файла нельзя.
     */
    private fun afterInstall(asset: Asset) {
        if (!asset.id.startsWith("map-")) return
        runCatching {
            val repo = SettingsRepository(applicationContext)
            kotlinx.coroutines.runBlocking { repo.setNavOfflineMap(true) }
        }
    }

    /* ─────────────────  УВЕДОМЛЕНИЕ  ───────────────── */

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Загрузка файлов",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(title: String, text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, "settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_neon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (percent in 0..100) setProgress(100, percent, false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun notify(title: String, text: String, percent: Int) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIF_ID, buildNotification(title, text, percent))
        }
    }
}
