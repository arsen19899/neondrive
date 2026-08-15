package com.neondrive.launcher.voice

import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.nav.PlaceCategory

/** Экран оболочки, который можно открыть голосом. */
enum class VoiceScreen { HOME, APPS, SETTINGS, EQUALIZER, MUSIC, PHONE }

/**
 * Что оболочка поняла из сказанного.
 *
 * Разбор намеренно отделён от исполнения ([VoiceAssistant]): распознавание фраз
 * — единственная часть голосового управления, которую можно проверить, не
 * заводя машину и не поднимая ни одного сервиса Android.
 */
sealed interface VoiceCommand {

    /* Навигация */
    data class Navigate(val query: String) : VoiceCommand
    data object NavigateHome : VoiceCommand
    data class NavigateFavorite(val name: String) : VoiceCommand
    data class NavigateCategory(val category: PlaceCategory) : VoiceCommand
    data object CancelRoute : VoiceCommand
    data object RouteStatus : VoiceCommand
    data object SpeedStatus : VoiceCommand
    data object WhereAmI : VoiceCommand

    /* Музыка */
    data object MusicPlay : VoiceCommand
    data object MusicPause : VoiceCommand
    data object MusicNext : VoiceCommand
    data object MusicPrev : VoiceCommand
    data object VolumeUp : VoiceCommand
    data object VolumeDown : VoiceCommand
    data class VolumeSet(val percent: Int) : VoiceCommand
    data object MuteToggle : VoiceCommand
    data class SetSource(val source: MusicSource) : VoiceCommand

    /* Телефон */
    data class CallContact(val name: String) : VoiceCommand
    data class CallNumber(val digits: String) : VoiceCommand
    data object AnswerCall : VoiceCommand
    data object EndCall : VoiceCommand

    /* Экраны */
    data class OpenScreen(val screen: VoiceScreen) : VoiceCommand

    /** Позвали по имени, но команду не сказали. */
    data object Empty : VoiceCommand

    /** Расслышали, но не поняли. [raw] показывается человеку, чтобы было видно, что именно услышала оболочка. */
    data class Unknown(val raw: String) : VoiceCommand
}

/**
 * Превращение услышанной фразы в команду.
 *
 * ## Почему это правила, а не «умная» модель
 *
 * Набор команд в машине конечный и известен заранее, а вычислительный бюджет
 * головного устройства — нет. Правила разбирают фразу за микросекунды, не
 * требуют ни сети, ни второй модели в памяти, и, что важнее всего, ошибаются
 * предсказуемо: если «поехали на Ленина» не сработало, причину видно глазами в
 * этом файле.
 *
 * ## Порядок проверок значим
 *
 * Русские команды пересекаются словами. «Включи музыку», «включи радио» и
 * «включи яндекс музыку» начинаются одинаково, «стоп» относится к музыке, а
 * «стоп маршрут» — к навигации. Поэтому более узкие правила стоят выше более
 * общих, а не наоборот, и порядок в [parse] менять нельзя без понимания, что
 * именно перекроет что.
 */
object CommandParser {

    /** Слова, которые в начале запроса ничего не значат и мешают геокодеру. */
    private val FILLER = setOf(
        "маршрут", "маршрутик", "до", "в", "во", "на", "к", "ко", "по", "адресу",
        "адрес", "мне", "нам", "меня", "пожалуйста", "давай", "давайте", "please",
        "куда", "точку", "точка", "место", "поедем", "едем"
    )

    /** Слова, после которых идёт адрес назначения. */
    private val NAV_TRIGGERS = listOf(
        "построй", "постройка", "проложи", "проложить", "построить",
        "поехали", "поеду", "едем", "ехать", "веди", "отвези", "доедем",
        "доехать", "навигация", "навигацию", "маршрут"
    )

    private val CATEGORY_WORDS: List<Pair<PlaceCategory, List<String>>> = listOf(
        PlaceCategory.FUEL to listOf("заправка", "азс", "заправиться", "бензин", "топливо", "заправку"),
        PlaceCategory.CHARGING to listOf("зарядка", "зарядку", "электрозаправка", "зарядиться"),
        PlaceCategory.FOOD to listOf("поесть", "кафе", "ресторан", "еда", "перекусить", "столовая", "покушать"),
        PlaceCategory.SHOP to listOf("магазин", "супермаркет", "продукты", "гипермаркет"),
        PlaceCategory.PARKING to listOf("парковка", "стоянка", "припарковаться", "парковку"),
        PlaceCategory.HOTEL to listOf("гостиница", "отель", "ночлег", "переночевать", "хостел"),
        PlaceCategory.REST to listOf("отдохнуть", "отдых", "передохнуть"),
        PlaceCategory.PHARMACY to listOf("аптека", "аптеку", "лекарства"),
        PlaceCategory.BANK to listOf("банкомат", "банк", "наличные"),
        // «СТО» сюда не входит намеренно: числительные превращаются в цифры
        // раньше разбора (см. RussianText.numeralsToDigits), и «сто» к моменту
        // сравнения — это уже число 100, а не автосервис.
        PlaceCategory.CAR to listOf("автосервис", "шиномонтаж", "мойка", "сервис", "помыть"),
        PlaceCategory.TOILET to listOf("туалет", "уборная")
    )

    /**
     * Разобрать фразу. [text] — уже без обращения «Елисей».
     *
     * [favorites] нужны, чтобы «поехали на дачу» нашло сохранённую точку, а не
     * ушло в геокодер искать населённый пункт со словом «дача» в названии.
     */
    fun parse(text: String, favorites: List<String> = emptyList()): VoiceCommand {
        val normalized = RussianText.numeralsToDigits(text)
        val w = RussianText.words(normalized)
        if (w.isEmpty()) return VoiceCommand.Empty

        val has = { variants: Array<out String> -> RussianText.hasWord(w, *variants) }

        // Глагол поездки ищется здесь, а не в блоке навигации ниже, потому что
        // его наличие меняет разбор команд плеера. «Проложи» и «продолжи»
        // отличаются одной буквой, и «проложи маршрут на проспект» уверенно
        // разбиралось как «продолжи воспроизведение». Расстоянием Левенштейна
        // такие пары не развести — они действительно похожи; развести их можно
        // только смыслом: фраза с глаголом поездки — про поездку.
        val navTrigger = afterNavTrigger(w)

        /* ── Телефон. Стоит первым: «позвони маме» не должно попасть в геокодер ── */

        if (RussianText.startsWith(w, "позвони", "позвонить", "набери", "вызови", "звони")) {
            val rest = dropLeading(w.drop(1), setOf("номер", "номеру", "по", "на"))
            val digits = rest.joinToString("").filter { it.isDigit() }
            // Номер целиком цифрами — набираем как есть; иначе это имя из книги.
            return if (digits.length >= 3 && rest.all { it.all(Char::isDigit) }) {
                VoiceCommand.CallNumber(digits)
            } else {
                val name = rest.joinToString(" ")
                if (name.isBlank()) VoiceCommand.Unknown(text) else VoiceCommand.CallContact(name)
            }
        }
        if (has(arrayOf("ответь", "прими", "возьми")) && !has(arrayOf("маршрут"))) {
            return VoiceCommand.AnswerCall
        }
        if (has(arrayOf("отбой")) ||
            (has(arrayOf("сбрось", "заверши", "положи", "повесь")) &&
                has(arrayOf("вызов", "звонок", "трубку", "трубка")))
        ) {
            return VoiceCommand.EndCall
        }

        /* ── Навигация: отмена и статус ── */

        // «Стоп» и «хватит» сами по себе относятся к музыке — но вместе со
        // словом «маршрут» это отмена поездки, и проверить их надо здесь, выше
        // правил плеера, иначе «стоп маршрут» просто поставит музыку на паузу.
        if (has(arrayOf("маршрут", "навигацию", "навигация", "поездку", "маршрута")) &&
            has(
                arrayOf(
                    "отмени", "отменить", "сбрось", "сбросить", "убери", "удали",
                    "заверши", "стоп", "хватит", "прекрати", "останови"
                )
            )
        ) {
            return VoiceCommand.CancelRoute
        }
        if (has(arrayOf("сколько")) && has(arrayOf("ехать", "осталось", "километров", "минут")) ||
            has(arrayOf("когда")) && has(arrayOf("приедем", "прибудем", "доедем")) ||
            has(arrayOf("далеко")) && has(arrayOf("еще"))
        ) {
            return VoiceCommand.RouteStatus
        }
        if (has(arrayOf("скорость")) || has(arrayOf("быстро")) && has(arrayOf("едем"))) {
            return VoiceCommand.SpeedStatus
        }
        if (has(arrayOf("где")) && has(arrayOf("я", "мы", "находимся", "нахожусь"))) {
            return VoiceCommand.WhereAmI
        }

        /* ── Экраны. Раньше музыки, потому что «открой музыку» — это экран ── */

        if (RussianText.startsWith(w, "открой", "открыть", "покажи", "показать")) {
            val target = screenFor(w.drop(1))
            if (target != null) return VoiceCommand.OpenScreen(target)
        }
        if (has(arrayOf("главный", "главную", "домашний")) && has(arrayOf("экран", "экрану"))) {
            return VoiceCommand.OpenScreen(VoiceScreen.HOME)
        }
        if (has(arrayOf("все", "всех")) && has(arrayOf("приложения", "приложений"))) {
            return VoiceCommand.OpenScreen(VoiceScreen.APPS)
        }

        /* ── Громкость ── */

        if (has(arrayOf("громкость", "громкости"))) {
            val n = RussianText.firstNumber(normalized)
            if (n != null) return VoiceCommand.VolumeSet(n.coerceIn(0, 100))
        }
        if (has(arrayOf("громче", "прибавь", "погромче", "увеличь"))) return VoiceCommand.VolumeUp
        if (has(arrayOf("тише", "убавь", "потише", "уменьши"))) return VoiceCommand.VolumeDown
        if (has(arrayOf("мьют", "замолчи", "тишина")) ||
            has(arrayOf("выключи", "отключи")) && has(arrayOf("звук", "звука"))
        ) {
            return VoiceCommand.MuteToggle
        }

        /* ── Источник музыки и плеер. Только если это не команда о поездке ── */

        if (navTrigger == null) {
            if (has(arrayOf("яндекс"))) return VoiceCommand.SetSource(MusicSource.YANDEX)
            if (has(arrayOf("радио"))) return VoiceCommand.SetSource(MusicSource.RADIO)

            if (has(arrayOf("следующий", "следующая", "дальше", "переключи", "next"))) {
                return VoiceCommand.MusicNext
            }
            if (has(arrayOf("предыдущий", "предыдущая", "прошлый", "верни")) ||
                has(arrayOf("назад")) && has(arrayOf("трек", "песню", "песня"))
            ) {
                return VoiceCommand.MusicPrev
            }
            if (has(arrayOf("пауза", "паузу", "стоп", "останови", "приостанови"))) {
                return VoiceCommand.MusicPause
            }
            if (has(arrayOf("играй", "продолжи", "продолжай")) ||
                has(arrayOf("включи", "запусти", "поставь")) &&
                has(arrayOf("музыку", "музыка", "трек", "песню", "плеер"))
            ) {
                return VoiceCommand.MusicPlay
            }
        }

        /* ── Навигация: куда ехать ── */

        // «Домой» — отдельно и до всего остального, потому что это самая частая
        // поездка и потому что геокодер по слову «дом» вернёт что угодно.
        if (has(arrayOf("домой")) || (has(arrayOf("дом")) && !has(arrayOf("улица", "улице")))) {
            return VoiceCommand.NavigateHome
        }

        val afterTrigger = navTrigger
        val subject = dropLeading(afterTrigger ?: w, FILLER)

        // Избранное проверяем раньше категорий и геокодера: пользовательская
        // «дача» важнее всего, что можно найти в OpenStreetMap по этому слову.
        matchFavorite(subject, favorites)?.let { return VoiceCommand.NavigateFavorite(it) }

        categoryFor(subject)?.let { return VoiceCommand.NavigateCategory(it) }
        // Категория могла прозвучать и без глагола: просто «ближайшая заправка».
        if (afterTrigger == null) categoryFor(w)?.let { return VoiceCommand.NavigateCategory(it) }

        if (afterTrigger != null && subject.isNotEmpty()) {
            return VoiceCommand.Navigate(subject.joinToString(" "))
        }

        return VoiceCommand.Unknown(text.trim())
    }

    /* ─────────────────  ВСПОМОГАТЕЛЬНОЕ  ───────────────── */

    /** Слова после глагола-указателя на поездку; null — глагола не было. */
    private fun afterNavTrigger(w: List<String>): List<String>? {
        for (i in w.indices) {
            val stem = RussianText.stem(w[i])
            if (NAV_TRIGGERS.any { RussianText.similar(RussianText.stem(it), stem) }) {
                return w.drop(i + 1)
            }
        }
        return null
    }

    private fun dropLeading(w: List<String>, skip: Set<String>): List<String> {
        var i = 0
        while (i < w.size && (w[i] in skip || RussianText.stem(w[i]) in skip)) i++
        return w.drop(i)
    }

    private fun categoryFor(w: List<String>): PlaceCategory? {
        if (w.isEmpty()) return null
        for ((category, variants) in CATEGORY_WORDS) {
            if (RussianText.hasWord(w, *variants.toTypedArray())) return category
        }
        return null
    }

    private fun screenFor(w: List<String>): VoiceScreen? = when {
        w.isEmpty() -> null
        RussianText.hasWord(w, "настройки", "настройка") -> VoiceScreen.SETTINGS
        RussianText.hasWord(w, "эквалайзер", "звук", "эквалайзера") -> VoiceScreen.EQUALIZER
        RussianText.hasWord(w, "телефон", "звонки", "контакты") -> VoiceScreen.PHONE
        RussianText.hasWord(w, "музыку", "музыка", "плеер", "треки") -> VoiceScreen.MUSIC
        RussianText.hasWord(w, "приложения", "приложений") -> VoiceScreen.APPS
        RussianText.hasWord(w, "главный", "главную", "домашний") -> VoiceScreen.HOME
        else -> null
    }

    /**
     * Сопоставить сказанное с названием избранной точки.
     *
     * Сравнение по основам слов и с допуском на ошибку: сохранённая «Дача у
     * озера» должна найтись и по «на дачу», и по «дачу озеро». Достаточно, чтобы
     * совпало хотя бы одно значащее слово названия — названия избранного обычно
     * короткие, и требовать полного совпадения значило бы заставлять человека
     * произносить их дословно.
     */
    private fun matchFavorite(spoken: List<String>, favorites: List<String>): String? {
        if (spoken.isEmpty() || favorites.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        for (fav in favorites) {
            val favWords = RussianText.words(fav).filter { it.length >= 3 }
            if (favWords.isEmpty()) continue
            val score = favWords.count { fw ->
                val fs = RussianText.stem(fw)
                spoken.any { sw -> RussianText.similar(fs, RussianText.stem(sw)) }
            }
            if (score > bestScore) {
                bestScore = score
                best = fav
            }
        }
        return if (bestScore > 0) best else null
    }
}
