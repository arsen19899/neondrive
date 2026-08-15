package com.neondrive.launcher.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Работа с русским текстом, пришедшим от распознавателя.
 *
 * Всё здесь существует из-за одного факта: распознаватель отдаёт не то, что
 * человек сказал, а то, что он расслышал. Маленькая офлайн-модель в машине с
 * работающим двигателем и музыкой ошибается регулярно, и сравнивать её вывод с
 * образцом посимвольно бессмысленно. Поэтому и нормализация, и расстояние
 * Левенштейна, и грубое отбрасывание окончаний — не украшательство, а
 * необходимый минимум, чтобы «паехали дамой» сработало как «поехали домой».
 */
object RussianText {

    /**
     * Привести фразу к виду, пригодному для сравнения: нижний регистр, «ё» как
     * «е», без знаков препинания, одиночные пробелы.
     *
     * «Ё» схлопывается намеренно: разные модели пишут одно и то же слово то с
     * ней, то без неё, и это ровно та разница, из-за которой не должно ломаться
     * ни одно сравнение.
     */
    fun normalize(s: String): String = buildString(s.length) {
        var lastSpace = true
        for (ch in s.lowercase()) {
            val c = if (ch == 'ё') 'е' else ch
            if (c.isLetterOrDigit()) {
                append(c)
                lastSpace = false
            } else if (!lastSpace) {
                append(' ')
                lastSpace = true
            }
        }
    }.trim()

    fun words(s: String): List<String> =
        normalize(s).split(' ').filter { it.isNotBlank() }

    /* ─────────────────  ПОХОЖЕСТЬ  ───────────────── */

    /** Классическое расстояние Левенштейна на двух строках. */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val swap = prev; prev = cur; cur = swap
        }
        return prev[b.length]
    }

    /**
     * Похожи ли слова настолько, чтобы считать их одним.
     *
     * Допуск растёт с длиной: в слове из четырёх букв одна ошибка меняет смысл
     * («тише» / «выше»), а в слове из двенадцати одна-две буквы — обычный шум
     * распознавания. Фиксированный порог был бы плох в обе стороны сразу.
     *
     * Пороги подобраны не на глаз: при допуске в две ошибки на семи буквах
     * «магазин» и «гагарин» оказываются одним словом, и фраза «построй маршрут
     * до улицы Гагарина» превращалась в поиск ближайшего магазина. Русский язык
     * плотно заселён короткими словами, и на длине до семи букв допускать можно
     * ровно одну ошибку.
     */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val len = max(a.length, b.length)
        if (abs(a.length - b.length) > 3) return false
        val allowed = when {
            len <= 4 -> 0
            len <= 7 -> 1
            len <= 11 -> 2
            else -> 3
        }
        return distance(a, b) <= allowed
    }

    /**
     * Отбросить типичное русское окончание.
     *
     * Полноценной морфологии здесь нет и быть не должно: словарь форм весит
     * больше, чем вся оболочка, а задача узкая — сопоставить «домой» с «дом»,
     * «заправку» с «заправка», «Ленина» с «Ленин». Грубая отрезка последних
     * букв решает её достаточно точно, а ошибки поглощаются [similar].
     */
    fun stem(word: String): String {
        if (word.length <= 3) return word
        val endings = listOf(
            "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ой", "ей",
            "ая", "яя", "ое", "ее", "ые", "ие", "ов", "ев", "ам", "ям", "ах",
            "ях", "ом", "ем", "у", "ю", "а", "я", "ы", "и", "е", "о", "ь"
        )
        for (end in endings) {
            // Корень короче трёх букв уже ничего не значит, но именно три, а не
            // четыре: «дача» и «дачу» — четырёхбуквенные слова в разных
            // падежах, и при пороге в четыре буквы они не сводились к общему
            // «дач», из-за чего избранная точка не находилась по фразе
            // «поехали на дачу».
            if (word.length - end.length >= 3 && word.endsWith(end)) {
                return word.dropLast(end.length)
            }
        }
        return word
    }

    /**
     * Содержит ли фраза слово (с учётом окончаний и ошибок распознавания).
     */
    fun hasWord(phrase: List<String>, vararg variants: String): Boolean {
        val stems = variants.map { stem(normalize(it)) }
        return phrase.any { w ->
            val s = stem(w)
            stems.any { it == s || similar(it, s) }
        }
    }

    /** Начинается ли фраза с одного из вариантов (полное совпадение по началу). */
    fun startsWith(phrase: List<String>, vararg variants: String): Boolean {
        if (phrase.isEmpty()) return false
        val first = stem(phrase.first())
        return variants.any { similar(stem(normalize(it)), first) }
    }

    /* ─────────────────  ЧИСЛИТЕЛЬНЫЕ  ───────────────── */

    private val UNITS = mapOf(
        "ноль" to 0, "один" to 1, "одна" to 1, "два" to 2, "две" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8,
        "девять" to 9, "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12,
        "тринадцать" to 13, "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18,
        "девятнадцать" to 19
    )

    private val TENS = mapOf(
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
        "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90
    )

    private val HUNDREDS = mapOf(
        "сто" to 100, "двести" to 200, "триста" to 300, "четыреста" to 400,
        "пятьсот" to 500, "шестьсот" to 600, "семьсот" to 700, "восемьсот" to 800,
        "девятьсот" to 900
    )

    private fun numeralValue(word: String): Int? =
        UNITS[word] ?: TENS[word] ?: HUNDREDS[word]

    /**
     * Заменить числительные словами на цифры: «дом сто двадцать пять» →
     * «дом 125».
     *
     * Нужно в двух местах, и оба обязательны. Первое — номер дома: геокодер ищет
     * «Ленина 5», а не «Ленина пять», и без замены голосовой ввод адреса
     * промахивался бы на каждом доме. Второе — «громкость шестьдесят».
     *
     * Идущие подряд числительные складываются по разрядам, как в живой речи:
     * сотни, десятки, единицы. «Сто двадцать пять» — одно число, а «пять пять» —
     * два разных, потому что второй разряд не может повториться.
     */
    fun numeralsToDigits(text: String): String {
        val src = words(text)
        if (src.isEmpty()) return text

        val out = ArrayList<String>(src.size)
        var i = 0
        while (i < src.size) {
            val start = i
            var sum = 0
            var used = false
            var lastRank = Int.MAX_VALUE

            while (i < src.size) {
                val v = numeralValue(src[i]) ?: break
                val rank = when {
                    HUNDREDS.containsKey(src[i]) -> 3
                    TENS.containsKey(src[i]) -> 2
                    else -> 1
                }
                // Разряд обязан убывать: «сто двадцать пять» складывается,
                // «пять сто» — нет, это два отдельных числа.
                if (rank >= lastRank) break
                sum += v
                lastRank = rank
                used = true
                i++
            }

            if (used) {
                out += sum.toString()
            } else {
                out += src[start]
                i = start + 1
            }
        }
        return out.joinToString(" ")
    }

    /** Первое число во фразе — цифрами или словом. */
    fun firstNumber(text: String): Int? =
        words(numeralsToDigits(text)).firstNotNullOfOrNull { it.toIntOrNull() }
}

/**
 * Ключевое слово «Елисей».
 *
 * ## Почему вариантов много
 *
 * Имя редкое, а маленькая офлайн-модель обучена на общей речи. В салоне с
 * работающим двигателем она регулярно отдаёт «алисей», «лисей», «елисеи» — первый
 * слог глохнет, безударная гласная смазывается. Требовать точного попадания
 * значило бы заставить водителя выговаривать имя по слогам; принять несколько
 * заранее известных искажений стоит ничего.
 *
 * ## Почему это не делается одним лишь расстоянием Левенштейна
 *
 * Список нужен ещё и как грамматика для режима ожидания ([VoskEngine]): движку
 * передаётся конечный набор допустимых фраз, и именно за счёт этого ожидание
 * ключевого слова почти не грузит процессор. Расстояние применяется вторым
 * шагом, поверх — на случай варианта, которого в списке не оказалось.
 */
object WakeWord {

    const val NAME = "Елисей"

    /**
     * Грамматика для офлайн-движка. Только то, что реально произносится;
     * склонения сюда не нужны — к ассистенту обращаются в звательной форме.
     */
    val GRAMMAR_VARIANTS = listOf(
        "елисей", "алисей", "лисей", "елисеи", "елисе", "элисей"
    )

    /**
     * Найти обращение и вернуть остаток фразы после него.
     *
     * `null` — обращения нет. Пустая строка — обращение есть, но команды после
     * него не прозвучало: это нормальный сценарий «Елисей?» — «Слушаю», когда
     * человек зовёт, ещё не решив, что попросить.
     */
    fun strip(text: String): String? {
        val w = RussianText.words(text)
        if (w.isEmpty()) return null

        // Обращение почти всегда первое, но человек может сказать и «так,
        // Елисей, поехали», поэтому ищем в первых трёх словах, а не только в
        // начале. Дальше третьего слова искать нельзя: «позвони Елисею» — это
        // команда с именем внутри, а не обращение.
        val limit = min(3, w.size)
        for (i in 0 until limit) {
            if (matches(w[i])) {
                return w.drop(i + 1).joinToString(" ")
            }
        }
        return null
    }

    /** Похоже ли слово на обращение. */
    fun matches(word: String): Boolean {
        val n = RussianText.normalize(word)
        if (n in GRAMMAR_VARIANTS) return true
        return GRAMMAR_VARIANTS.any { RussianText.similar(it, n) }
    }
}
