package com.neondrive.launcher

import android.Manifest
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import com.neondrive.launcher.automation.AutomationService
import com.neondrive.launcher.automation.SpeedProvider
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
        requestNeededPermissions()

        // Настройки могут меняться на ходу — реагируем на каждое изменение
        lifecycleScope.launch {
            repo.settings.collect { s ->
                SteeringWheelManager.configure(applicationContext, s)
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
            else -> NeonScreen.HOME
        }

        setContent {
            // Тема живёт от настроек, поэтому читаем их прямо здесь
            val s by repo.settings.collectAsState(initial = LauncherSettings())
            NeonDriveTheme(accent = NeonAccent.fromName(s.accent)) {
                NeonRoot(repo = repo, startScreen = start)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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

    private fun hideSystemBars() {
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
            Manifest.permission.READ_PHONE_STATE
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
        runCatching { permissionLauncher.launch(need.toTypedArray()) }
    }
}
