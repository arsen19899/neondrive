package com.neondrive.launcher.input

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.view.KeyEvent
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.SwcAction
import com.neondrive.launcher.media.PlayerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Кнопки руля.
 *
 * На головных устройствах руль подключается двумя способами:
 *  1. Ядро уже разложило резистивную «лесенку» в KeyEvent'ы — тогда достаточно
 *     перехватывать клавиши в активити и в MediaButtonReceiver.
 *  2. Ядро отдаёт «сырое» значение АЦП в sysfs-ноду — тогда мы читаем её сами
 *     и сопоставляем значение с обученной кнопкой (с допуском по разбросу).
 *
 * Оба режима поддерживаются, второй включается в настройках.
 */
object SteeringWheelManager {

    private var settings = LauncherSettings()
    private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var adcJob: Job? = null

    /** Режим обучения: события не выполняются, а отдаются в UI. */
    private val _learning = MutableStateFlow(false)
    val learning: StateFlow<Boolean> = _learning

    data class Captured(val code: Int, val fromAdc: Boolean, val label: String)

    private val _captured = MutableSharedFlow<Captured>(extraBufferCapacity = 8)
    val captured: SharedFlow<Captured> = _captured

    private val _lastAction = MutableStateFlow<SwcAction?>(null)
    val lastAction: StateFlow<SwcAction?> = _lastAction

    private var downAt = HashMap<Int, Long>()
    private var longFired = HashSet<Int>()

    /**
     * Опрос ADC-ноды уже пробовали и она не читается на этом ГУ. Флаг гасит
     * повторные попытки: без него любое изменение любой настройки заново
     * запускало трёхсекундный цикл неудачных чтений в фоне. Сбрасывается, когда
     * пользователь сам меняет путь или переключает тумблер ADC-режима — то есть
     * когда есть смысл попробовать снова.
     */
    private var adcUnavailable = false

    fun configure(context: Context, s: LauncherSettings) {
        appContext = context.applicationContext
        val adcChanged = s.swcAdcEnabled != settings.swcAdcEnabled || s.swcAdcPath != settings.swcAdcPath
        settings = s
        if (adcChanged) adcUnavailable = false
        if (adcChanged || (s.swcAdcEnabled && adcJob == null && !adcUnavailable)) {
            stopAdc()
            if (s.swcAdcEnabled) startAdc()
        }
        if (!s.swcAdcEnabled) stopAdc()
    }

    fun setLearning(on: Boolean) {
        _learning.value = on
    }

    /* ─────────────────  KEYEVENT-РЕЖИМ  ───────────────── */

    /** Возвращает true, если событие обработано и его не надо пускать дальше. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!settings.swcEnabled) return false
        val code = event.keyCode

        if (_learning.value) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                _captured.tryEmit(Captured(code, false, KeyEvent.keyCodeToString(code)))
            }
            return true
        }

        val shortAction = settings.swcShort[code]
        val longAction = settings.swcLong[code]
        if (shortAction == null && longAction == null) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    downAt[code] = System.currentTimeMillis()
                    longFired.remove(code)
                } else if (longAction != null && !longFired.contains(code)) {
                    val held = System.currentTimeMillis() - (downAt[code] ?: 0L)
                    if (held >= settings.swcLongPressMs) {
                        longFired.add(code)
                        perform(longAction)
                    }
                }
                // Громкость удобно повторять автоповтором
                if (event.repeatCount > 0 && shortAction != null &&
                    (shortAction == SwcAction.VOL_UP || shortAction == SwcAction.VOL_DOWN)
                ) {
                    perform(shortAction)
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                val held = System.currentTimeMillis() - (downAt.remove(code) ?: 0L)
                if (longFired.remove(code)) return true
                if (longAction != null && held >= settings.swcLongPressMs) {
                    perform(longAction)
                } else if (shortAction != null) {
                    perform(shortAction)
                }
                return true
            }
        }
        return false
    }

    /* ─────────────────  ADC-РЕЖИМ  ───────────────── */

    /**
     * Ноды sysfs, в которые ядра разных ГУ выкладывают «сырое» значение АЦП
     * резистивного руля. Единого стандарта нет: путь зависит от платформы
     * (Unisoc, Allwinner, Rockchip, MTK) и от того, как вендор назвал драйвер.
     * Пробуем по очереди и берём первую читаемую — раньше был жёстко зашит один
     * путь `/sys/class/adc_key/value`, и на всех остальных прошивках ADC-режим
     * молча не работал, без единого признака в интерфейсе.
     *
     * Отдельно: на Android 10+ SELinux запрещает обычному приложению читать
     * большинство нод в /sys, поэтому даже существующий файл может оказаться
     * недоступен. Это ограничение системы, а не оболочки; в таком случае
     * остаётся KeyEvent-режим, который на подавляющем большинстве ГУ и работает.
     */
    val KNOWN_ADC_NODES: List<String> = listOf(
        "/sys/class/adc_key/value",
        "/sys/class/switch/adc_key/value",
        "/sys/devices/platform/adc_key/value",
        "/sys/devices/virtual/misc/adc_key/value",
        "/sys/class/misc/adckey/value",
        "/sys/class/sprd_adc/value",
        "/sys/kernel/swkey/value",
        "/sys/class/hwmon/hwmon0/device/adc_val",
        "/sys/devices/platform/soc/soc:swkey/value"
    )

    /** Первая читаемая ADC-нода из известных, либо null. Для кнопки «найти» в настройках. */
    fun detectAdcNode(): String? = KNOWN_ADC_NODES.firstOrNull { path ->
        runCatching { File(path).let { it.exists() && it.canRead() } }.getOrDefault(false)
    }

    fun startAdc() {
        // Настроенный путь важнее автоопределения, но если он не читается —
        // не сидим молча, а пробуем известные альтернативы.
        val configured = settings.swcAdcPath
        val path = if (runCatching { File(configured).canRead() }.getOrDefault(false)) {
            configured
        } else {
            detectAdcNode() ?: configured
        }
        adcJob = scope.launch {
            var lastValue = -1
            var idleSince = 0L
            // Если ноды нет или SELinux не даёт её прочитать (обычное дело на
            // Android 10+ для стороннего приложения), опрос бессмысленен. Раньше
            // цикл всё равно крутился вечно — 16 неудачных open() в секунду на
            // фоне, круглые сутки, на и без того слабом процессоре ГУ. Теперь
            // после серии подряд неудачных чтений опрос просто останавливается;
            // включить заново можно тумблером в настройках.
            var misses = 0
            while (true) {
                val raw = readAdc(path)
                if (raw == null) {
                    if (++misses >= MAX_ADC_MISSES) {
                        adcUnavailable = true
                        adcJob = null
                        return@launch
                    }
                } else {
                    misses = 0
                    // «Отпущено» на большинстве ГУ — это очень большое или нулевое значение
                    val isIdle = raw <= 0 || raw >= 4000
                    if (!isIdle && abs(raw - lastValue) > settings.swcAdcTolerance) {
                        lastValue = raw
                        onAdcValue(raw)
                        idleSince = System.currentTimeMillis()
                    } else if (isIdle && System.currentTimeMillis() - idleSince > 120) {
                        lastValue = -1
                    }
                }
                delay(60)
            }
        }
    }

    /** Сколько подряд неудачных чтений считать признаком «ноды нет». ~3 секунды. */
    private const val MAX_ADC_MISSES = 50

    fun stopAdc() {
        adcJob?.cancel()
        adcJob = null
    }

    private fun readAdc(path: String): Int? = runCatching {
        val f = File(path)
        if (!f.canRead()) return null
        f.readText().trim().filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toInt()
    }.getOrNull()

    private fun onAdcValue(value: Int) {
        if (_learning.value) {
            _captured.tryEmit(Captured(value, true, "ADC $value"))
            return
        }
        val tol = settings.swcAdcTolerance
        val action = settings.swcAdcMap.entries
            .minByOrNull { abs(it.key - value) }
            ?.takeIf { abs(it.key - value) <= tol }
            ?.value ?: return
        perform(action)
    }

    /* ─────────────────  ВЫПОЛНЕНИЕ  ───────────────── */

    fun perform(action: SwcAction) {
        _lastAction.value = action
        val ctx = appContext ?: return
        when (action) {
            SwcAction.NONE -> Unit
            SwcAction.PLAY_PAUSE -> PlayerHub.playPause()
            SwcAction.NEXT -> PlayerHub.next()
            SwcAction.PREV -> PlayerHub.prev()
            SwcAction.VOL_UP -> PlayerHub.nudgeVolume(true)
            SwcAction.VOL_DOWN -> PlayerHub.nudgeVolume(false)
            SwcAction.MUTE -> PlayerHub.toggleMute()
            SwcAction.SOURCE_NEXT -> PlayerHub.cycleSource()
            SwcAction.ANSWER_CALL -> answerCall(ctx)
            SwcAction.END_CALL -> endCall(ctx)
            SwcAction.VOICE -> launchIntent(ctx, Intent(Intent.ACTION_VOICE_COMMAND))
            SwcAction.HOME -> launchIntent(
                ctx,
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            )
            SwcAction.NAVIGATION -> launchPackage(ctx, settings.mapPackage)
            SwcAction.APPS -> launchIntent(
                ctx,
                Intent(ctx, com.neondrive.launcher.MainActivity::class.java)
                    .putExtra(com.neondrive.launcher.MainActivity.EXTRA_OPEN, "apps")
            )
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun answerCall(ctx: Context) {
        // Если звонок сейчас у нашего NeonInCallService (обычный случай, пока
        // включён автомобильный режим — см. NeonApp.enableCarMode), отвечаем
        // через него: это тот же объект Call, что рисует CallOverlay, без
        // отдельного разрешения ANSWER_PHONE_CALLS. TelecomManager.acceptRingingCall —
        // запасной путь на случай, если звонок всё же поднял системный «Телефон».
        if (com.neondrive.launcher.phone.NeonInCallService.current.value.state ==
            com.neondrive.launcher.phone.NeonCallState.RINGING
        ) {
            com.neondrive.launcher.phone.NeonInCallService.answer()
            return
        }
        runCatching {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                tm?.acceptRingingCall()
            } else {
                sendMediaKey(ctx, KeyEvent.KEYCODE_HEADSETHOOK)
            }
        }.onFailure { sendMediaKey(ctx, KeyEvent.KEYCODE_HEADSETHOOK) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun endCall(ctx: Context) {
        if (!com.neondrive.launcher.phone.NeonInCallService.current.value.isEmpty) {
            com.neondrive.launcher.phone.NeonInCallService.hangup()
            return
        }
        runCatching {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                tm?.endCall()
            } else {
                sendMediaKey(ctx, KeyEvent.KEYCODE_HEADSETHOOK)
            }
        }.onFailure { sendMediaKey(ctx, KeyEvent.KEYCODE_HEADSETHOOK) }
    }

    private fun sendMediaKey(ctx: Context, code: Int) {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
    }

    private fun launchIntent(ctx: Context, intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun launchPackage(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) launchIntent(ctx, i)
    }
}
