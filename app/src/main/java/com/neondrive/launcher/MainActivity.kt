package com.neondrive.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.neondrive.launcher.automation.AutomationService
import com.neondrive.launcher.automation.FuelStationHub
import com.neondrive.launcher.automation.SpeedProvider
import com.neondrive.launcher.automation.WeatherHub
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.input.SteeringWheelManager
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.ui.NeonRoot
import com.neondrive.launcher.ui.NeonScreen
import com.neondrive.launcher.ui.theme.NeonAccent
import com.neondrive.launcher.ui.theme.NeonDriveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN = "neon_open"
    }

    private lateinit var repo: SettingsRepository

    // ACTION_MEDIA_MOUNTED — защищённая система-широковещательная рассылка, её нельзя
    // объявить в манифесте (implicit broadcast от API 24), только регистрировать в коде,
    // пока активность жива — благо лаунчер живёт почти всё время работы ГУ. Ловим
    // подключение/отключение SD-карты и USB-накопителя и пересканируем библиотеку сразу,
    // не дожидаясь, пока пользователь сам зайдёт во вкладку «Музыка» и нажмёт «обновить».
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // MediaStore индексирует свежеподключённый том не мгновенно — небольшая
            // задержка перед пересканированием ощутимо снижает шанс поймать пустой
            // список сразу после физического подключения флешки.
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1200)
                runCatching { PlayerHub.refreshLibrary() }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            SpeedProvider.start(applicationContext)
            lifecycleScope.launch { runCatching { PlayerHub.refreshLibrary() } }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        PlayerHub.init(applicationContext)
        AutomationService.start(applicationContext)
        FuelStationHub.start(applicationContext)
        WeatherHub.start(applicationContext)
        requestNeededPermissions()

        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addDataScheme("file")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(storageReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(storageReceiver, filter)
            }
        }

        // Настройки могут меняться на ходу — реагируем на каждое изменение
        lifecycleScope.launch {
            repo.settings.collect { s ->
                SteeringWheelManager.configure(applicationContext, s)
                com.neondrive.launcher.nav.GuidanceEngine.apply {
                    setVoiceEnabled(s.navVoice)
                    setVoiceVolume(s.navVoiceVolume)
                    setDuckMusic(s.navDuckMusic)
                }
                com.neondrive.launcher.nav.RouteHub.apply {
                    preferOffline = s.navOfflineRouting
                    avoidUnpaved = s.navAvoidUnpaved
                    avoidToll = s.navAvoidToll
                }
                com.neondrive.launcher.nav.HazardHub.apply {
                    cameraWarnEnabled = s.navCameraWarn
                    speedLimitEnabled = s.navSpeedLimitWarn
                    toleranceKmh = s.navSpeedTolerance
                }
                if (s.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        val start = when (intent?.getStringExtra(EXTRA_OPEN)) {
            "apps" -> NeonScreen.APPS
            "settings" -> NeonScreen.SETTINGS
            "eq" -> NeonScreen.EQUALIZER
            "music" -> NeonScreen.MUSIC
            "phone" -> NeonScreen.PHONE
            else -> NeonScreen.HOME
        }

        setContent {
            // Тема живёт от настроек, поэтому читаем их прямо здесь
            val s by repo.settings.collectAsState(initial = LauncherSettings())
            NeonDriveTheme(accent = NeonAccent.fromName(s.accent), reducedEffects = s.reducedEffects) {
                NeonRoot(repo = repo, startScreen = start)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(storageReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        SpeedProvider.start(applicationContext)
    }

    /** Кнопки руля: сначала пробуем свои назначения, потом отдаём системе. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (SteeringWheelManager.handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Лаунчер — это дом. «Назад» не должен выкидывать в чёрный экран. */
    @Deprecated("Стандартное поведение для домашнего экрана")
    override fun onBackPressed() {
        // намеренно пусто
    }

    /**
     * Прячем системные панели двумя способами сразу.
     *
     * `systemUiVisibility` объявлен устаревшим с Android 11 и на части прошивок
     * (в том числе на ГУ с сильно перепиленным SystemUI) уже игнорируется —
     * оболочка оставалась с видимой полосой навигации, съедающей нижнюю часть
     * рабочего стола. `WindowInsetsControllerCompat` — актуальный путь, но на
     * старых прошивках он опирается на тот же legacy-механизм, поэтому оба
     * вызова оставлены рядом: что-нибудь из двух сработает на любом устройстве.
     */
    private fun hideSystemBars() {
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun requestNeededPermissions() {
        val need = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            // Нужно, чтобы звонок из телефонной книги оболочки уходил напрямую,
            // не открывая экран набора номера системного «Телефона».
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need += Manifest.permission.READ_MEDIA_AUDIO
            need += Manifest.permission.POST_NOTIFICATIONS
        } else {
            need += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Нужно кнопке руля «Принять вызов» (SteeringWheelManager.answerCall) —
            // без него acceptRingingCall() падает по SecurityException.
            need += Manifest.permission.ANSWER_PHONE_CALLS
        }
        runCatching { permissionLauncher.launch(need.toTypedArray()) }
    }
}
