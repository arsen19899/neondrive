package com.neondrive.launcher.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Офлайн-распознавание речи моделью Vosk.
 *
 * ## Почему не системный распознаватель
 *
 * `android.speech.SpeechRecognizer` — это не распознаватель, а розетка: реальную
 * работу делает установленный в системе сервис, и на подавляющем большинстве
 * китайских магнитол его нет вовсе, потому что нет сервисов Google. Проверяется
 * это одной строкой (`SpeechRecognizer.isRecognitionAvailable`), и на таких ГУ
 * она честно возвращает false — а значит, голосовое управление, построенное
 * только на нём, на половине устройств не заработает никогда.
 *
 * Второе, не менее важное: непрерывное ожидание ключевого слова через системный
 * API не сделать. `SpeechRecognizer` рассчитан на короткие сеансы «нажал —
 * сказал», сам закрывает микрофон по паузе и на многих прошивках отдаёт
 * ERROR_RECOGNIZER_BUSY при попытке перезапускать его по кругу. Держать открытым
 * микрофон часами умеет только движок, работающий внутри процесса.
 *
 * ## Почему через рефлексию
 *
 * Тот же приём и по той же причине, что в [com.neondrive.launcher.nav.OfflineRouter]:
 * `vosk-android` тянет нативные библиотеки на каждую архитектуру и заметно
 * утяжеляет APK. Связь рефлексивная, поэтому оболочка собирается и работает в
 * обоих состояниях — с зависимостью и без. Без неё этот класс отвечает
 * «недоступен», [VoiceAssistant] уходит на системный движок, и единственное, что
 * теряется, — ожидание слова «Елисей» без нажатия кнопки.
 *
 * ## Две разные задачи — два разных распознавателя
 *
 * Ожидание ключевого слова может длиться всю поездку, и на Cortex-A53 полная
 * модель языка в этом режиме съела бы ядро целиком. Vosk умеет работать по
 * ограниченной грамматике: список допустимых фраз плюс `[unk]` для всего
 * остального. Распознавателю, который знает ровно слово «Елисей» и его частые
 * искажения, считать почти нечего — это и есть режим [ListenPurpose.WAKE_WORD].
 *
 * Как только слово услышано, движок пересоздаётся уже с полной моделью:
 * [ListenPurpose.COMMAND] должен разобрать произвольный адрес, а не выбрать из
 * списка. Секунды такой работы процессор переживает спокойно.
 *
 * ## Где взять модель
 *
 * `https://alphacephei.com/vosk/models` — маленькая русская модель
 * `vosk-model-small-ru-0.22` весит около 45 МБ и предназначена ровно для таких
 * устройств. Распакованную папку положить в
 * `Android/data/com.neondrive.launcher/files/vosk/` — туда же, куда карта и граф,
 * и тем же способом: скачал на компьютере, скинул по USB.
 */
object VoskEngine : SpeechEngine {

    private const val MODEL_CLASS = "org.vosk.Model"
    private const val RECOGNIZER_CLASS = "org.vosk.Recognizer"
    private const val SERVICE_CLASS = "org.vosk.android.SpeechService"
    private const val LISTENER_CLASS = "org.vosk.android.RecognitionListener"

    private const val SAMPLE_RATE = 16000.0f

    /**
     * Грамматика режима ожидания.
     *
     * Кроме самого имени перечислены искажения, которыми маленькая модель
     * регулярно отвечает на «Елисей»: она обучена на общей речи, имя нечастое, и
     * на шуме в салоне первый слог теряется или смягчается. Дешевле принять
     * несколько похожих вариантов, чем заставлять человека выговаривать имя по
     * слогам на скорости 100 км/ч. `[unk]` обязателен — без него движок будет
     * пытаться натянуть перечисленные слова на любой посторонний звук.
     */
    private val WAKE_GRAMMAR: String = buildString {
        append("[")
        append(WakeWord.GRAMMAR_VARIANTS.joinToString(", ") { "\"" + it + "\"" })
        append(", \"[unk]\"]")
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var model: Any? = null

    @Volatile
    private var modelPath: String? = null

    @Volatile
    private var service: Any? = null

    @Volatile
    private var recognizer: Any? = null

    /**
     * Библиотеки нет в сборке. Проверяется один раз: `Class.forName` по
     * отсутствующему классу — дорогая операция, а спрашивать доступность движка
     * оболочка будет на каждой перерисовке экрана настроек.
     */
    private val libraryPresent: Boolean by lazy {
        runCatching { Class.forName(MODEL_CLASS) }.isSuccess &&
            runCatching { Class.forName(SERVICE_CLASS) }.isSuccess
    }

    /* ─────────────────  МОДЕЛЬ НА ДИСКЕ  ───────────────── */

    /** Куда класть распакованную модель. */
    fun modelFolder(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.cacheDir, "vosk")

    /**
     * Найти папку модели.
     *
     * Смотрим и в саму папку `vosk/`, и на один уровень внутрь: архив с сайта
     * распаковывается в подпапку с именем версии (`vosk-model-small-ru-0.22`), и
     * человек, скинувший её как есть, не должен из-за этого получить «модель не
     * найдена». Признак настоящей папки модели — наличие подпапки `am` или файла
     * `conf/model.conf`; по одному лишь имени судить нельзя, модель можно
     * переименовать.
     */
    fun findModel(context: Context): File? {
        val root = modelFolder(context)
        if (!root.isDirectory) return null
        if (looksLikeModel(root)) return root
        return runCatching {
            root.listFiles { f -> f.isDirectory }?.firstOrNull { looksLikeModel(it) }
        }.getOrNull()
    }

    private fun looksLikeModel(dir: File): Boolean =
        File(dir, "am").isDirectory ||
            File(dir, "conf/model.conf").isFile ||
            File(dir, "graph").isDirectory

    /* ─────────────────  ДОСТУПНОСТЬ  ───────────────── */

    override fun isAvailable(context: Context): Boolean =
        libraryPresent && findModel(context) != null

    override fun unavailableReason(context: Context): String = when {
        !libraryPresent ->
            "Библиотека Vosk не включена в сборку. Раскомментируйте зависимость " +
                "vosk-android в app/build.gradle.kts и пересоберите."
        findModel(context) == null ->
            "Нет офлайн-модели распознавания. Скачайте vosk-model-small-ru с " +
                "alphacephei.com/vosk/models и распакуйте в папку vosk/."
        else -> ""
    }

    /** Загружена ли модель в память прямо сейчас. */
    val modelLoaded: Boolean get() = model != null

    /* ─────────────────  ПРОСЛУШИВАНИЕ  ───────────────── */

    override fun start(
        context: Context,
        purpose: ListenPurpose,
        onResult: (SpeechResult) -> Unit,
        onError: (String) -> Unit,
        onReady: () -> Unit
    ) {
        if (!libraryPresent) {
            onError("Vosk не включён в сборку")
            return
        }
        val dir = findModel(context)
        if (dir == null) {
            onError("Модель распознавания не найдена")
            return
        }

        stop()

        // Загрузка модели читает с флеш-памяти сотню мегабайт и на слабом ГУ
        // занимает секунды. На главном потоке это гарантированный ANR, поэтому
        // грузим в фоне, а слушать начинаем уже в главном — SpeechService
        // отдаёт свои колбэки через Handler главного потока.
        Thread {
            val attempt = runCatching { ensureModel(dir) }
            val loaded = attempt.getOrNull()
            if (loaded == null) {
                // Текст исключения нужен целиком. «Не удалось загрузить модель»
                // не отличает битый архив от несобравшейся нативной библиотеки,
                // а в машине посмотреть логи нечем.
                val why = attempt.exceptionOrNull()?.let {
                    it.javaClass.simpleName + ": " + (it.message ?: "без описания")
                } ?: "причина неизвестна"
                main.post { onError("Модель не загрузилась — $why") }
                return@Thread
            }
            main.post { launchService(loaded, purpose, onResult, onError, onReady) }
        }.apply { isDaemon = true }.start()
    }

    private fun ensureModel(dir: File): Any {
        val path = dir.absolutePath
        val existing = model
        if (existing != null && modelPath == path) return existing

        runCatching { closeQuietly(model) }
        model = null

        val cls = Class.forName(MODEL_CLASS)
        val made = cls.getConstructor(String::class.java).newInstance(path)
        model = made
        modelPath = path
        return made
    }

    private fun launchService(
        loadedModel: Any,
        purpose: ListenPurpose,
        onResult: (SpeechResult) -> Unit,
        onError: (String) -> Unit,
        onReady: () -> Unit
    ) {
        val attempt = runCatching {
            val modelCls = Class.forName(MODEL_CLASS)
            val recCls = Class.forName(RECOGNIZER_CLASS)

            val rec = if (purpose == ListenPurpose.WAKE_WORD) {
                recCls.getConstructor(modelCls, Float::class.javaPrimitiveType, String::class.java)
                    .newInstance(loadedModel, SAMPLE_RATE, WAKE_GRAMMAR)
            } else {
                recCls.getConstructor(modelCls, Float::class.javaPrimitiveType)
                    .newInstance(loadedModel, SAMPLE_RATE)
            }
            recognizer = rec

            val svcCls = Class.forName(SERVICE_CLASS)
            val svc = svcCls.getConstructor(recCls, Float::class.javaPrimitiveType)
                .newInstance(rec, SAMPLE_RATE)
            service = svc

            val listener = makeListener(onResult, onError)
            svcCls.getMethod("startListening", Class.forName(LISTENER_CLASS))
                .invoke(svc, listener)
            true
        }

        if (attempt.isSuccess) onReady()

        if (attempt.isFailure) {
            // Самая частая причина — микрофон занят: разговор по телефону,
            // чужое приложение записи, на части прошивок ГУ — штатный
            // «голосовой помощник» магнитолы. Но бывает и другое: нет нативной
            // библиотеки под архитектуру, отозвано разрешение, AudioRecord не
            // открывается на этой прошивке. Раз причин много — показываем ту,
            // что случилась, а не одну на все случаи.
            releaseService()
            val e = attempt.exceptionOrNull()
            val why = if (e == null) {
                "причина неизвестна"
            } else {
                e.javaClass.simpleName + ": " + (e.message ?: "без описания")
            }
            onError("Микрофон не запустился — $why")
        }
    }

    /**
     * Слушатель Vosk собирается динамическим прокси, потому что интерфейса
     * `RecognitionListener` в сборке может не быть вовсе — реализовать его
     * обычным `object :` значило бы жёстко потребовать зависимость.
     */
    private fun makeListener(
        onResult: (SpeechResult) -> Unit,
        onError: (String) -> Unit
    ): Any {
        val iface = Class.forName(LISTENER_CLASS)
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "onPartialResult" -> {
                    val text = extract(args?.getOrNull(0) as? String, "partial")
                    if (text.isNotBlank()) onResult(SpeechResult(text, partial = true))
                }
                "onResult" -> {
                    val text = extract(args?.getOrNull(0) as? String, "text")
                    if (text.isNotBlank()) onResult(SpeechResult(text, partial = false))
                }
                "onFinalResult" -> {
                    val text = extract(args?.getOrNull(0) as? String, "text")
                    if (text.isNotBlank()) onResult(SpeechResult(text, partial = false))
                }
                "onError" -> onError("Ошибка распознавания")
                "onTimeout" -> onError("Тишина")
                // Object-методы прокси обязан обслужить сам, иначе любой
                // toString() на слушателе внутри библиотеки уронит вызов.
                // Сравнивать нужно с самим прокси (первый аргумент invoke), а не
                // с VoskEngine: библиотека держит ссылку именно на прокси.
                "toString" -> return@InvocationHandler "VoskEngine.listener"
                "hashCode" -> return@InvocationHandler System.identityHashCode(proxy)
                "equals" -> return@InvocationHandler args?.getOrNull(0) === proxy
            }
            null
        }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler)
    }

    /** Vosk отдаёт результат строкой JSON: `{"text": "…"}` или `{"partial": "…"}`. */
    private fun extract(json: String?, field: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString(field, "") }.getOrDefault("").trim()
    }

    override fun stop() {
        releaseService()
    }

    private fun releaseService() {
        val svc = service ?: run { recognizer = null; return }
        service = null
        runCatching {
            val cls = Class.forName(SERVICE_CLASS)
            // stop() просит движок доработать буфер и отдать финальный результат,
            // cancel() бросает его молча. Здесь нужен именно cancel: прослушивание
            // останавливают, когда результат уже не нужен — сменили режим, ушли в
            // звонок, выключили тумблер. Финальная фраза после этого была бы
            // выполнена задним числом, чего никто не ждёт.
            cls.getMethod("cancel").invoke(svc)
            cls.getMethod("shutdown").invoke(svc)
        }
        runCatching { closeQuietly(recognizer) }
        recognizer = null
    }

    override fun release() {
        releaseService()
        runCatching { closeQuietly(model) }
        model = null
        modelPath = null
    }

    private fun closeQuietly(any: Any?) {
        if (any == null) return
        runCatching { any.javaClass.getMethod("close").invoke(any) }
    }
}
