package com.neondrive.launcher.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.neondrive.launcher.MainActivity
import com.neondrive.launcher.R
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.NotificationReaction
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.input.SteeringWheelManager
import com.neondrive.launcher.media.PlayerHub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Фоновый «мозг» оболочки. Всё, что должно работать, даже когда лаунчер свёрнут:
 *
 *  • автозапуск музыки после включения ГУ;
 *  • пауза / приглушение при уведомлениях с подключённого телефона;
 *  • возврат музыки после разговора;
 *  • подтяжка громкости по скорости (4 ступени);
 *  • опрос резистивных кнопок руля через ADC.
 */
class AutomationService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "neon_automation"
        private const val NOTIF_ID = 0x4E45

        const val ACTION_START = "com.neondrive.launcher.AUTOMATION_START"
        const val ACTION_AUTOPLAY_NOW = "com.neondrive.launcher.AUTOPLAY_NOW"

        private val _status = MutableStateFlow(AutomationStatus())
        val status: StateFlow<AutomationStatus> = _status

        fun start(context: Context) {
            val i = Intent(context, AutomationService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    data class AutomationStatus(
        val speedGainPercent: Int = 0,
        val baseVolumePercent: Int = 0,
        val ducking: Boolean = false,
        val pausedByNotification: Boolean = false,
        val pausedByCall: Boolean = false,
        val inCall: Boolean = false
    )

    private lateinit var repo: SettingsRepository
    private var settings = LauncherSettings()

    private val audio: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** Базовая громкость в ступенях потока — от неё считаем прибавку по скорости. */
    private var baseVolumeRaw: Int = -1
    private var lastAppliedRaw: Int = -1
    private var pausedByCall = false
    private var pausedByNotification = false
    private var duckUntil = 0L
    private var wasInCall = false
    private var autoplayDone = false

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository(applicationContext)
        PlayerHub.init(applicationContext)
        startForeground(NOTIF_ID, buildNotification())

        lifecycleScope.launch {
            repo.settings.collect { s ->
                settings = s
                SteeringWheelManager.configure(applicationContext, s)
            }
        }
        lifecycleScope.launch { notificationLoop() }
        lifecycleScope.launch { mainLoop() }
        SpeedProvider.start(applicationContext)
        registerScreenReceiver()
    }

    /* ─────────────────  ПРОБУЖДЕНИЕ ЭКРАНА  ───────────────── */

    private var screenReceiver: ScreenOnReceiver? = null

    private fun registerScreenReceiver() {
        val receiver = ScreenOnReceiver { settings.startOnScreenOn }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onSuccess { screenReceiver = receiver }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_AUTOPLAY_NOW) {
            lifecycleScope.launch { runAutoplay(force = true) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        SpeedProvider.stop()
        SteeringWheelManager.stopAdc()
        screenReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        screenReceiver = null
        super.onDestroy()
    }

    /* ─────────────────  ГЛАВНЫЙ ЦИКЛ  ───────────────── */

    private suspend fun mainLoop() {
        // Даём системе подняться, ГУ обычно «доезжает» несколько секунд после старта
        delay(1500)
        runAutoplay(force = false)

        while (true) {
            handleCallState()
            handleDuckRelease()
            handleSpeedVolume()
            publishStatus()
            delay(700)
        }
    }

    /* ── 1. Автопроигрывание ── */

    private suspend fun runAutoplay(force: Boolean) {
        if (autoplayDone && !force) return
        // Настройки читаем напрямую: коллектор мог ещё не успеть отдать первое значение
        val s = runCatching { repo.settings.first() }.getOrDefault(settings)
        settings = s
        if (!s.autoplay) return
        autoplayDone = true
        delay(s.autoplayDelaySec * 1000L)
        if (PlayerHub.isPlaying) return

        when (s.autoplaySource) {
            MusicSource.DEVICE -> {
                if (PlayerHub.tracks.value.isEmpty()) runCatching { PlayerHub.refreshLibrary() }
                PlayerHub.tracks.value.takeIf { it.isNotEmpty() }
                    ?.let { PlayerHub.playTracks(it, 0) }
            }
            MusicSource.RADIO -> PlayerHub.stations.value.firstOrNull()
                ?.let { PlayerHub.playStation(it) }
            // Подключение и запуск берёт на себя сам хаб: он поднимет приложение
            // и дождётся появления медиасессии
            MusicSource.YANDEX -> PlayerHub.switchToYandex(launchApp = true, autoPlay = true)
        }
    }

    /* ── 2. Реакция на уведомления ── */

    private suspend fun notificationLoop() {
        NeonNotificationListener.events.collect { event ->
            val s = settings
            if (s.notificationReaction == NotificationReaction.IGNORE) return@collect
            if (s.onlyPairedDeviceNotifications && !event.fromPairedDevice) return@collect
            if (!PlayerHub.isPlaying && !pausedByNotification) return@collect

            when (s.notificationReaction) {
                NotificationReaction.DUCK -> {
                    PlayerHub.duck(s.duckPercent)
                    duckUntil = System.currentTimeMillis() + s.duckHoldMs
                }
                NotificationReaction.PAUSE -> {
                    if (PlayerHub.isPlaying) {
                        PlayerHub.pause()
                        pausedByNotification = true
                        // Возобновляем сами: уведомление не сообщает, когда «закончилось»
                        duckUntil = System.currentTimeMillis() + s.duckHoldMs
                    }
                }
                NotificationReaction.IGNORE -> Unit
            }
            publishStatus()
        }
    }

    private fun handleDuckRelease() {
        if (duckUntil == 0L || System.currentTimeMillis() < duckUntil) return
        duckUntil = 0L
        if (PlayerHub.isDucked) PlayerHub.unduck()
        if (pausedByNotification && !inCallNow()) {
            pausedByNotification = false
            if (settings.autoplay) PlayerHub.play()
        }
    }

    /* ── 3. Телефонный вызов ── */

    private fun inCallNow(): Boolean {
        val mode = runCatching { audio.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private suspend fun handleCallState() {
        val inCall = inCallNow()
        if (inCall && !wasInCall) {
            // Вызов начался
            if (PlayerHub.isPlaying) {
                PlayerHub.pause()
                pausedByCall = true
            }
        } else if (!inCall && wasInCall) {
            // Разговор завершён: если автопроигрывание включено — музыка возвращается сама
            if (pausedByCall && settings.autoplay && settings.resumeAfterCall) {
                delay(settings.resumeAfterCallDelaySec * 1000L)
                if (!inCallNow()) PlayerHub.play()
            }
            pausedByCall = false
        }
        wasInCall = inCall
    }

    /* ── 4. Громкость от скорости ── */

    private fun handleSpeedVolume() {
        val s = settings
        if (!s.speedVolumeEnabled) {
            // Возвращаем то, что накрутили, и забываем базу
            if (lastAppliedRaw >= 0 && baseVolumeRaw >= 0 && PlayerHub.volumeRaw() == lastAppliedRaw) {
                PlayerHub.setVolumeRaw(baseVolumeRaw)
            }
            baseVolumeRaw = -1
            lastAppliedRaw = -1
            lastGain = 0
            return
        }
        if (PlayerHub.isDucked) return

        val max = PlayerHub.maxVolumeRaw()
        val current = PlayerHub.volumeRaw()

        // Уровень изменил не мы — значит крутил пользователь, принимаем его за базовый
        if (lastAppliedRaw < 0 || current != lastAppliedRaw) {
            baseVolumeRaw = (current - gainToSteps(lastGain, max)).coerceIn(0, max)
        }

        val gain = s.gainForSpeed(SpeedProvider.state.value.speedKmh)
        val target = (baseVolumeRaw + gainToSteps(gain, max)).coerceIn(0, max)

        if (target != current) {
            // Не более одной ступени за такт — подъём слышен как плавный
            val next = if (target > current) current + 1 else current - 1
            PlayerHub.setVolumeRaw(next)
            lastAppliedRaw = next
        } else {
            lastAppliedRaw = current
        }
        lastGain = gain
    }

    /** Прибавка в процентах → целое число ступеней шкалы громкости. */
    private fun gainToSteps(gainPercent: Int, max: Int): Int =
        (gainPercent / 100f * max).roundToInt()

    private var lastGain = 0

    private fun publishStatus() {
        _status.value = AutomationStatus(
            speedGainPercent = lastGain,
            baseVolumePercent = (baseVolumeRaw.coerceAtLeast(0) * 100f / PlayerHub.maxVolumeRaw())
                .roundToInt(),
            ducking = PlayerHub.isDucked,
            pausedByNotification = pausedByNotification,
            pausedByCall = pausedByCall,
            inCall = wasInCall
        )
    }

    /* ─────────────────  УВЕДОМЛЕНИЕ СЕРВИСА  ───────────────── */

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_automation),
                    NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setContentTitle("NeonDrive")
            .setContentText("Автоматика активна")
            .setSmallIcon(R.drawable.ic_stat_neon)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}

/** Мелкое расширение: округление процентов без импорта в каждом месте. */
internal fun Float.pct(): Int = this.roundToInt().coerceIn(0, 100)
