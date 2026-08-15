package com.neondrive.launcher.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.neondrive.launcher.automation.ForegroundLauncher
import com.neondrive.launcher.automation.SpeedProvider
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.SpeedUnits
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.nav.GuidanceEngine
import com.neondrive.launcher.nav.PlaceSearch
import com.neondrive.launcher.nav.RouteHub
import com.neondrive.launcher.nav.formatDistance
import com.neondrive.launcher.phone.Contact
import com.neondrive.launcher.phone.ContactsRepository
import com.neondrive.launcher.phone.NeonCallState
import com.neondrive.launcher.phone.NeonInCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Чем занят ассистент прямо сейчас. */
enum class VoicePhase {
    /** Выключен или недоступен. */
    OFF,

    /** Микрофон открыт, ждём слово «Елисей». Для человека это состояние невидимо. */
    WAITING,

    /** Слушаем команду. */
    LISTENING,

    /** Команда разобрана, выполняем — ищем адрес, поднимаем контакты. */
    WORKING,

    /** Показываем и произносим ответ. */
    REPLY
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.OFF,
    /** Что услышали — в том числе промежуточная гипотеза, пока человек говорит. */
    val heard: String = "",
    /** Ответ ассистента. */
    val reply: String = "",
    val error: String = "",
    /** Каким движком распознаём — показывается в настройках. */
    val engineLabel: String = ""
) {
    /**
     * Показывать ли оверлей.
     *
     * [VoicePhase.WAITING] сюда не входит намеренно: ожидание ключевого слова
     * длится всю поездку, и постоянная плашка «слушаю» на рабочем столе была бы
     * не информацией, а раздражителем.
     */
    val visible: Boolean
        get() = phase == VoicePhase.LISTENING ||
            phase == VoicePhase.WORKING ||
            phase == VoicePhase.REPLY
}

/**
 * Голосовое управление оболочкой — «Елисей».
 *
 * ## Как это работает
 *
 * Два режима микрофона, между которыми ассистент переключается сам:
 *
 *  1. **Ожидание.** Офлайн-движок слушает по короткой грамматике — он знает
 *     только имя и его искажения. На Cortex-A53 это почти бесплатно, поэтому
 *     микрофон может оставаться открытым всю поездку (см. [VoskEngine]);
 *  2. **Команда.** Услышав имя, ассистент немедленно пересоздаёт распознаватель
 *     с полной моделью языка и слушает произвольную фразу — адрес, имя из
 *     телефонной книги, что угодно.
 *
 * Переключение начинается на ПРОМЕЖУТОЧНОЙ гипотезе, а не на финальной. Это не
 * оптимизация, а необходимость: люди говорят «Елисей, поехали домой» одной
 * фразой, и если ждать, пока движок окончательно закроет слово «Елисей» по
 * паузе, начало команды уже прозвучит в пустоту. Переключение занимает доли
 * секунды, и хвост фразы попадает в полную модель. Если всё же не попал —
 * ассистент через пару секунд молчания сам скажет «Слушаю» и подождёт ещё.
 *
 * ## Почему ожидание выключено по умолчанию
 *
 * Постоянно открытый микрофон — это осознанный выбор владельца машины, а не
 * настройка, которую включают за него. Кнопка на руле и кнопка микрофона в
 * поиске адреса работают всегда, ключевое слово — только когда его включили.
 *
 * ## Что происходит во время разговора
 *
 * Ничего. Микрофон в это время принадлежит телефонии, и попытка его перехватить
 * либо провалится, либо испортит разговор. Ассистент отпускает микрофон на время
 * звонка и возвращается к ожиданию после него.
 */
object VoiceAssistant {

    /** Через сколько молчания подсказать голосом, что ждём команду. */
    private const val PROMPT_AFTER_MS = 2200L

    /** Сколько всего ждать команду, прежде чем сдаться и вернуться к ожиданию. */
    private const val COMMAND_TIMEOUT_MS = 9000L

    /**
     * Сколько ждать, пока движок откроет микрофон.
     *
     * Отдельный и куда более щедрый срок, чем [COMMAND_TIMEOUT_MS]: между
     * нажатием кнопки и открытым микрофоном офлайн-движок читает с флеш-памяти
     * ГУ модель на сорок мегабайт и строит по ней граф. На Cortex-A53 это
     * секунды, иногда полтора десятка. Раньше оба срока были одним: тайм-аут
     * молчания начинал тикать от нажатия кнопки, на медленном устройстве
     * истекал раньше, чем микрофон успевал открыться, и всё заканчивалось
     * молча — ровно то, что снаружи выглядит как мёртвая кнопка.
     */
    private const val ENGINE_READY_TIMEOUT_MS = 30_000L

    /** Сколько держать ответ на экране. */
    private const val REPLY_HOLD_MS = 4500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state

    /**
     * Запросы на смену экрана. Именно поток, а не прямой вызов: ассистент живёт
     * в фоне и не знает ничего про Compose, а [com.neondrive.launcher.ui.NeonRoot]
     * не должен зависеть от голосового управления.
     */
    private val _screenRequests = MutableSharedFlow<VoiceScreen>(extraBufferCapacity = 4)
    val screenRequests: SharedFlow<VoiceScreen> = _screenRequests

    private var appContext: Context? = null
    private var settings = LauncherSettings()

    private var activeEngine: SpeechEngine? = null
    private var commandJob: Job? = null
    private var replyJob: Job? = null
    private var workJob: Job? = null

    /** Идёт сеанс команды — чтобы поздние колбэки старого движка не путались под ногами. */
    private var commandSession = 0

    /**
     * Куда отдать распознанный текст, если идёт диктовка, а не команда.
     *
     * Голосовой ввод адреса — не команда: разбирать «улица Гагарина двенадцать»
     * правилами [CommandParser] незачем и вредно, текст должен попасть в поле
     * поиска ровно таким, каким прозвучал. Отдельный режим, а не отдельный
     * движок: слушает и распознаёт всё то же самое, меняется только адресат
     * результата.
     */
    private var dictationTarget: ((String) -> Unit)? = null

    /* ─────────────────  НАСТРОЙКА  ───────────────── */

    /**
     * Применить настройки. Вызывается из [com.neondrive.launcher.MainActivity] на
     * каждое изменение — так же, как настраиваются кнопки руля и ведение.
     */
    fun configure(context: Context, s: LauncherSettings) {
        appContext = context.applicationContext
        val wasWake = wakeWanted()
        settings = s
        val nowWake = wakeWanted()

        if (!s.voiceEnabled) {
            stopEverything()
            _state.value = VoiceUiState(phase = VoicePhase.OFF)
            return
        }
        if (wasWake != nowWake) {
            if (nowWake) startWakeListening() else stopWakeListening()
        } else if (nowWake && _state.value.phase == VoicePhase.OFF) {
            startWakeListening()
        }
    }

    private fun wakeWanted(): Boolean {
        val ctx = appContext ?: return false
        return settings.voiceEnabled &&
            settings.voiceWakeWord &&
            hasMicPermission(ctx) &&
            VoskEngine.isAvailable(ctx)
    }

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Состояние голосового управления для экрана настроек — одной строкой,
     * без домыслов: что именно доступно и чего не хватает.
     */
    fun statusText(context: Context): String = when {
        !hasMicPermission(context) ->
            "Нет доступа к микрофону. Разрешение запрашивается при запуске оболочки."
        VoskEngine.isAvailable(context) ->
            "Офлайн-модель найдена. Работает без интернета и без сервисов Google."
        SystemSpeechEngine.isAvailable(context) ->
            "Офлайн-модели нет, используется системный распознаватель. " +
                VoskEngine.unavailableReason(context)
        else ->
            VoskEngine.unavailableReason(context) + " " +
                SystemSpeechEngine.unavailableReason(context)
    }

    /** Доступно ли голосовое управление хоть в каком-то виде. */
    fun isUsable(context: Context): Boolean =
        hasMicPermission(context) &&
            (VoskEngine.isAvailable(context) || SystemSpeechEngine.isAvailable(context))

    /** Работает ли ключевое слово (только офлайн-движок это умеет). */
    fun wakeWordPossible(context: Context): Boolean = VoskEngine.isAvailable(context)

    /* ─────────────────  ОЖИДАНИЕ КЛЮЧЕВОГО СЛОВА  ───────────────── */

    private fun startWakeListening() {
        val ctx = appContext ?: return
        if (!wakeWanted()) return
        if (inCall()) {
            // Микрофон занят разговором. Возвращаться будем не по таймеру, а по
            // окончании звонка — см. [onCallEnded].
            _state.value = _state.value.copy(phase = VoicePhase.OFF)
            return
        }

        VoskEngine.start(
            context = ctx,
            purpose = ListenPurpose.WAKE_WORD,
            onResult = { r -> if (containsWakeWord(r.text)) onWakeWord() },
            onError = { msg ->
                // Ошибка ожидания — не повод шуметь: чаще всего это занятый
                // микрофон. Просто гасим состояние, следующий заход произойдёт
                // при смене настроек или после звонка.
                _state.value = _state.value.copy(phase = VoicePhase.OFF, error = msg)
            },
            // Ожиданию ключевого слова момент открытия микрофона не важен:
            // здесь нет ни тайм-аута молчания, ни подсказки голосом.
            onReady = {}
        )
        _state.value = _state.value.copy(
            phase = VoicePhase.WAITING,
            error = "",
            engineLabel = "Офлайн (Vosk)"
        )
    }

    private fun stopWakeListening() {
        VoskEngine.stop()
        if (_state.value.phase == VoicePhase.WAITING) {
            _state.value = _state.value.copy(phase = VoicePhase.OFF)
        }
    }

    private fun containsWakeWord(text: String): Boolean =
        RussianText.words(text).any { WakeWord.matches(it) }

    private fun onWakeWord() {
        val ctx = appContext ?: return
        if (_state.value.phase == VoicePhase.LISTENING) return
        dictationTarget = null
        VoskEngine.stop()
        beginCommand(ctx, announce = false)
    }

    /** Кнопка руля «Голосовой помощник» и кнопка микрофона в поиске адреса. */
    fun pushToTalk(context: Context) {
        appContext = context.applicationContext
        val ctx = appContext ?: return
        if (!settings.voiceEnabled) {
            report("Голосовое управление выключено в настройках")
            return
        }
        if (!hasMicPermission(ctx)) {
            report("Нет доступа к микрофону")
            return
        }
        if (inCall()) {
            report("Идёт разговор — микрофон занят")
            return
        }
        dictationTarget = null
        VoskEngine.stop()
        beginCommand(ctx, announce = true)
    }

    /**
     * Продиктовать текст — кнопка микрофона в поиске адреса.
     *
     * Отличие от [pushToTalk] только в том, что произнесённое не разбирается как
     * команда, а отдаётся в [onText]. Числительные при этом всё же переводятся в
     * цифры: геокодер ищет «Ленина 5», а не «Ленина пять», и без этого голосовой
     * ввод промахивался бы на каждом номере дома.
     */
    fun dictate(context: Context, onText: (String) -> Unit) {
        appContext = context.applicationContext
        val ctx = appContext ?: return
        if (!hasMicPermission(ctx)) return report("Нет доступа к микрофону")
        if (!isUsable(ctx)) return report(statusText(ctx))
        if (inCall()) return report("Идёт разговор — микрофон занят")

        dictationTarget = onText
        VoskEngine.stop()
        beginCommand(ctx, announce = false)
    }

    /* ─────────────────  ПРОСЛУШИВАНИЕ КОМАНДЫ  ───────────────── */

    /**
     * [announce] — сказать «Слушаю» сразу. Так делается по кнопке: человек нажал
     * и ждёт подтверждения. По ключевому слову — наоборот, молчим: команда,
     * скорее всего, уже произносится, и подсказка перебила бы её.
     */
    private fun beginCommand(ctx: Context, announce: Boolean) {
        commandJob?.cancel()
        replyJob?.cancel()
        workJob?.cancel()
        val session = ++commandSession

        val engine = pickEngine(ctx)
        if (engine == null) {
            report(statusText(ctx))
            return
        }
        activeEngine = engine

        // Микрофон в этот момент ещё закрыт: движку нужно загрузить модель.
        // Показываем это состояние отдельно от «слушаю» — иначе человек говорит
        // в закрытый микрофон и считает, что его не слышат.
        _state.value = _state.value.copy(
            phase = VoicePhase.WORKING,
            heard = "",
            reply = "Готовлю распознавание…",
            error = "",
            engineLabel = if (engine === VoskEngine) "Офлайн (Vosk)" else "Системный"
        )

        if (announce) GuidanceEngine.speakAssistant(ctx, "Слушаю")

        // Сторожевой таймер на саму подготовку. Без него сбой, при котором
        // движок не позвал ни onReady, ни onError, оставлял бы оболочку в
        // «готовлю распознавание» навсегда.
        commandJob = scope.launch {
            delay(ENGINE_READY_TIMEOUT_MS)
            if (session == commandSession && _state.value.phase == VoicePhase.WORKING) {
                finishQuietly("Распознавание так и не запустилось")
            }
        }

        engine.start(
            context = ctx,
            purpose = ListenPurpose.COMMAND,
            onResult = { r ->
                if (session != commandSession) return@start
                if (r.partial) {
                    _state.value = _state.value.copy(heard = r.text)
                } else {
                    handleCommandText(ctx, r.text)
                }
            },
            onError = { msg ->
                if (session != commandSession) return@start
                // «Тишина» и «не расслышал» — не ошибки, а обычный исход, когда
                // человек передумал говорить. Показывать их как сбой не нужно.
                finishQuietly(msg)
            },
            onReady = {
                if (session != commandSession) return@start
                _state.value = _state.value.copy(
                    phase = VoicePhase.LISTENING,
                    reply = ""
                )
                // Только теперь имеет смысл отсчитывать молчание: до этого
                // момента микрофона просто не было.
                commandJob?.cancel()
                commandJob = scope.launch {
                    delay(PROMPT_AFTER_MS)
                    if (session == commandSession &&
                        _state.value.phase == VoicePhase.LISTENING &&
                        _state.value.heard.isBlank() &&
                        !announce &&
                        dictationTarget == null
                    ) {
                        GuidanceEngine.speakAssistant(ctx, "Слушаю")
                    }
                    delay(COMMAND_TIMEOUT_MS - PROMPT_AFTER_MS)
                    if (session == commandSession &&
                        _state.value.phase == VoicePhase.LISTENING
                    ) {
                        finishQuietly("")
                    }
                }
            }
        )
    }

    private fun pickEngine(ctx: Context): SpeechEngine? = when {
        VoskEngine.isAvailable(ctx) -> VoskEngine
        SystemSpeechEngine.isAvailable(ctx) -> SystemSpeechEngine
        else -> null
    }

    private fun handleCommandText(ctx: Context, text: String) {
        commandJob?.cancel()
        stopListening()

        // Человек может повторить обращение внутри команды («Елисей, Елисей,
        // поехали домой») или сказать его по кнопке, где оно не требуется.
        val body = WakeWord.strip(text) ?: text

        _state.value = _state.value.copy(phase = VoicePhase.WORKING, heard = body.trim())

        val dictation = dictationTarget
        if (dictation != null) {
            dictationTarget = null
            dictation(RussianText.numeralsToDigits(body).trim())
            finishQuietly("")
            return
        }

        val command = CommandParser.parse(body, settings.navFavorites.map { it.name })

        if (command is VoiceCommand.Empty) {
            // Позвали, но не попросили. Не ответ, а продолжение ожидания.
            beginCommand(ctx, announce = true)
            return
        }

        workJob = scope.launch { execute(ctx, command) }
    }

    private fun stopListening() {
        activeEngine?.stop()
        activeEngine = null
    }

    /** Завершить сеанс без ответа вслух — просто вернуться к ожиданию. */
    private fun finishQuietly(reason: String) {
        commandSession++
        commandJob?.cancel()
        stopListening()

        // «Тишина» и «не расслышал» — обычный исход: человек передумал говорить,
        // шуметь об этом незачем. А вот всё остальное — занятый микрофон, не
        // загрузившаяся модель, отсутствующая нативная библиотека — это сбой, и
        // молчать о нём нельзя: снаружи он выглядит как мёртвая кнопка. Именно
        // из-за этого «жму микрофон — ничего не происходит» и не имело никакого
        // объяснения на экране.
        if (reason.isNotBlank() && reason != "Тишина" && reason != "Не расслышал") {
            report(reason)
            return
        }

        _state.value = _state.value.copy(
            phase = VoicePhase.OFF,
            heard = "",
            reply = "",
            error = reason.takeIf { it.isNotBlank() && it != "Тишина" && it != "Не расслышал" }
                .orEmpty()
        )
        scope.launch {
            delay(400)
            if (wakeWanted()) startWakeListening()
        }
    }

    /** Показать сообщение и произнести его. */
    private fun report(text: String) {
        val ctx = appContext
        _state.value = _state.value.copy(phase = VoicePhase.REPLY, reply = text)
        if (ctx != null) GuidanceEngine.speakAssistant(ctx, text)
        scheduleReturn()
    }

    private fun scheduleReturn() {
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(REPLY_HOLD_MS)
            _state.value = _state.value.copy(phase = VoicePhase.OFF, heard = "", reply = "")
            if (wakeWanted()) startWakeListening()
        }
    }

    /** Промежуточное состояние без озвучки: «ищу…» пока идёт сетевой запрос. */
    private fun progress(text: String) {
        _state.value = _state.value.copy(phase = VoicePhase.WORKING, reply = text)
    }

    /** Вызывается из телефонии, когда разговор закончился. */
    fun onCallEnded() {
        if (wakeWanted() && _state.value.phase == VoicePhase.OFF) startWakeListening()
    }

    private fun inCall(): Boolean =
        NeonInCallService.current.value.state != NeonCallState.NONE

    private fun stopEverything() {
        commandSession++
        dictationTarget = null
        commandJob?.cancel()
        replyJob?.cancel()
        workJob?.cancel()
        stopListening()
        VoskEngine.stop()
    }

    /** Отпустить микрофон и выгрузить модель — при полном закрытии оболочки. */
    fun release() {
        stopEverything()
        VoskEngine.release()
        SystemSpeechEngine.release()
        _state.value = VoiceUiState()
    }

    /* ─────────────────  ВЫПОЛНЕНИЕ  ───────────────── */

    private suspend fun execute(ctx: Context, command: VoiceCommand) {
        val gps = SpeedProvider.state.value

        when (command) {

            /* ── Навигация ── */

            is VoiceCommand.Navigate -> {
                if (!gps.hasFix) return report("Нет сигнала GPS — маршрут не построить")
                progress("Ищу: " + command.query)
                val place = PlaceSearch.byText(command.query, gps.lastLat, gps.lastLon)
                    .firstOrNull()
                if (place == null) {
                    report("Не нашёл «" + command.query + "»")
                } else {
                    goTo(ctx, gps.lastLat, gps.lastLon, place.lat, place.lon, place.name)
                }
            }

            VoiceCommand.NavigateHome -> {
                if (!settings.hasHomePoint) {
                    report("Точка «Дом» не задана. Сохраните её в навигации.")
                } else if (!gps.hasFix) {
                    report("Нет сигнала GPS — маршрут не построить")
                } else {
                    goTo(ctx, gps.lastLat, gps.lastLon, settings.homeLat, settings.homeLon, "Дом")
                }
            }

            is VoiceCommand.NavigateFavorite -> {
                val fav = settings.navFavorites.firstOrNull { it.name == command.name }
                if (fav == null) {
                    report("Не нашёл «" + command.name + "» в избранном")
                } else if (!gps.hasFix) {
                    report("Нет сигнала GPS — маршрут не построить")
                } else {
                    goTo(ctx, gps.lastLat, gps.lastLon, fav.lat, fav.lon, fav.name)
                }
            }

            is VoiceCommand.NavigateCategory -> {
                if (!gps.hasFix) return report("Нет сигнала GPS — не могу искать рядом")
                progress("Ищу: " + command.category.label.lowercase())
                val place = PlaceSearch.byCategory(command.category, gps.lastLat, gps.lastLon)
                    .firstOrNull()
                if (place == null) {
                    report("Рядом ничего не нашлось")
                } else {
                    goTo(
                        ctx, gps.lastLat, gps.lastLon, place.lat, place.lon, place.name,
                        prefix = place.name + ", " + formatDistance(place.straightM) + ". "
                    )
                }
            }

            VoiceCommand.CancelRoute -> {
                if (!RouteHub.state.value.hasDestination) {
                    report("Маршрут и так не построен")
                } else {
                    RouteHub.clear()
                    report("Маршрут отменён")
                }
            }

            VoiceCommand.RouteStatus -> {
                val g = GuidanceEngine.state.value
                val r = RouteHub.state.value
                report(
                    when {
                        g.active && g.remainingM > 0 ->
                            "Осталось " + g.remainingLabel + ", " + g.etaLabel +
                                ". На месте в " + g.arrivalLabel
                        r.hasRoute ->
                            "Маршрут " + formatDistance(r.distanceM) + ", ведение не запущено"
                        else -> "Маршрут не построен"
                    }
                )
            }

            VoiceCommand.SpeedStatus -> {
                if (!gps.hasFix) return report("Нет сигнала GPS")
                val units = settings.units
                val value = (gps.speedKmh / SpeedUnits.KMH.factorFromMs * units.factorFromMs)
                report(value.roundToInt().toString() + " " + units.label)
            }

            VoiceCommand.WhereAmI -> {
                if (!gps.hasFix) return report("Нет сигнала GPS")
                progress("Определяю адрес…")
                val where = PlaceSearch.reverse(gps.lastLat, gps.lastLon)
                report(where?.let { "Вы на " + it } ?: "Не удалось определить адрес — нет сети")
            }

            /* ── Музыка ── */

            VoiceCommand.MusicPlay -> {
                PlayerHub.play()
                report("Включаю")
            }

            VoiceCommand.MusicPause -> {
                PlayerHub.pause()
                report("Пауза")
            }

            VoiceCommand.MusicNext -> {
                PlayerHub.next()
                report("Следующий")
            }

            VoiceCommand.MusicPrev -> {
                PlayerHub.prev()
                report("Предыдущий")
            }

            VoiceCommand.VolumeUp -> {
                PlayerHub.nudgeVolume(true)
                report("Громкость " + PlayerHub.volumePercent() + " процентов")
            }

            VoiceCommand.VolumeDown -> {
                PlayerHub.nudgeVolume(false)
                report("Громкость " + PlayerHub.volumePercent() + " процентов")
            }

            is VoiceCommand.VolumeSet -> {
                PlayerHub.setVolumePercent(command.percent)
                report("Громкость " + command.percent + " процентов")
            }

            VoiceCommand.MuteToggle -> {
                PlayerHub.toggleMute()
                report("Готово")
            }

            is VoiceCommand.SetSource -> switchSource(command.source)

            /* ── Телефон ── */

            is VoiceCommand.CallContact -> {
                if (!ContactsRepository.hasPermission(ctx)) {
                    return report("Нет доступа к контактам")
                }
                progress("Ищу в контактах…")
                val match = bestContact(ContactsRepository.load(ctx), command.name)
                if (match == null) {
                    report("Не нашёл «" + command.name + "» в контактах")
                } else {
                    report("Звоню: " + match.name)
                    dial(ctx, match.number)
                }
            }

            is VoiceCommand.CallNumber -> {
                report("Набираю номер")
                dial(ctx, command.digits)
            }

            VoiceCommand.AnswerCall -> {
                answerCall(ctx)
                report("Отвечаю")
            }

            VoiceCommand.EndCall -> {
                endCall(ctx)
                report("Сбросил")
            }

            /* ── Экраны ── */

            is VoiceCommand.OpenScreen -> {
                ForegroundLauncher.bringToFront(ctx)
                _screenRequests.tryEmit(command.screen)
                report("Открываю")
            }

            /* ── Остальное ── */

            is VoiceCommand.Unknown ->
                report(
                    if (command.raw.isBlank()) "Не расслышал"
                    else "Не понял: " + command.raw
                )

            VoiceCommand.Empty -> report("Слушаю")
        }
    }

    private fun goTo(
        ctx: Context,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        title: String,
        prefix: String = ""
    ) {
        RouteHub.buildTo(
            context = ctx,
            fromLat = fromLat,
            fromLon = fromLon,
            toLat = toLat,
            toLon = toLon,
            title = title
        )
        ForegroundLauncher.bringToFront(ctx)
        _screenRequests.tryEmit(VoiceScreen.HOME)
        report(prefix + "Строю маршрут до: " + title)
    }

    /**
     * Смена источника музыки повторяет поведение плитки источников на рабочем
     * столе: не «переключить флаг», а действительно начать играть — иначе
     * команда «включи радио» молча меняла бы подпись под обложкой.
     */
    private fun switchSource(source: MusicSource) {
        when (source) {
            MusicSource.DEVICE -> {
                val tracks = PlayerHub.tracks.value
                if (tracks.isEmpty()) return report("На устройстве нет музыки")
                PlayerHub.playTracks(tracks, 0)
                report("Музыка с устройства")
            }
            MusicSource.RADIO -> {
                val station = PlayerHub.stations.value.firstOrNull()
                if (station == null) return report("Нет сохранённых станций")
                PlayerHub.playStation(station)
                report("Радио: " + station.name)
            }
            MusicSource.YANDEX -> {
                PlayerHub.switchToYandex(launchApp = true)
                report("Яндекс Музыка")
            }
        }
    }

    /**
     * Найти контакт по услышанному имени.
     *
     * Сравнение по словам с допуском: «позвони Ване» должно найти «Иван Петров»,
     * а «позвони Марине Сергеевне» — «Марина». Требуется совпадение хотя бы
     * одного слова имени; при равенстве побеждает избранный контакт — если в
     * книге два Ивана, звонить логичнее тому, кому звонят часто.
     */
    private fun bestContact(contacts: List<Contact>, spokenName: String): Contact? {
        val spoken = RussianText.words(spokenName).filter { it.length >= 2 }
        if (spoken.isEmpty() || contacts.isEmpty()) return null

        var best: Contact? = null
        var bestScore = 0
        for (c in contacts) {
            val parts = RussianText.words(c.name).filter { it.length >= 2 }
            var score = parts.count { part ->
                val ps = RussianText.stem(part)
                spoken.any { RussianText.similar(ps, RussianText.stem(it)) }
            } * 2
            if (score > 0 && c.starred) score += 1
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (bestScore > 0) best else null
    }

    /* ─────────────────  ТЕЛЕФОНИЯ  ───────────────── */

    /**
     * Звонок уходит тем же путём, что из телефонной книги оболочки, — напрямую в
     * Telecom, минуя системный номеронабиратель. Без разрешения CALL_PHONE
     * откатываемся на ACTION_DIAL с подставленным номером, чтобы не падать с
     * SecurityException.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun dial(ctx: Context, number: String) {
        if (number.isBlank()) return
        val canCall = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (canCall) {
            val placed = runCatching {
                val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    ?: return@runCatching false
                tm.placeCall(Uri.parse("tel:" + Uri.encode(number)), null)
                true
            }.getOrDefault(false)
            if (placed) return
        }
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun answerCall(ctx: Context) {
        if (NeonInCallService.current.value.state == NeonCallState.RINGING) {
            NeonInCallService.answer()
            return
        }
        runCatching {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm?.acceptRingingCall()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun endCall(ctx: Context) {
        if (!NeonInCallService.current.value.isEmpty) {
            NeonInCallService.hangup()
            return
        }
        runCatching {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tm?.endCall()
        }
    }
}
