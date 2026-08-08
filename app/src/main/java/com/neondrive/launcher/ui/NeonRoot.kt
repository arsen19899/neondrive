package com.neondrive.launcher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.neondrive.launcher.automation.AutomationService
import com.neondrive.launcher.automation.SpeedProvider
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.nav.NavigatorBridge
import com.neondrive.launcher.ui.apps.AllAppsScreen
import com.neondrive.launcher.ui.eq.EqualizerScreen
import com.neondrive.launcher.ui.home.HomeScreen
import com.neondrive.launcher.ui.music.MusicScreen
import com.neondrive.launcher.ui.phone.PhoneScreen
import com.neondrive.launcher.ui.settings.SettingsScreen
import com.neondrive.launcher.ui.theme.NeonAccent
import com.neondrive.launcher.ui.theme.NeonBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class NeonScreen { HOME, APPS, SETTINGS, EQUALIZER, MUSIC, PHONE }

@Composable
fun NeonRoot(
    repo: SettingsRepository,
    startScreen: NeonScreen = NeonScreen.HOME
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val settings by repo.settings.collectAsState(initial = LauncherSettings())
    val accentSpec = NeonAccent.fromName(settings.accent)
    val accent = accentSpec.primary
    val accent2 = accentSpec.secondary

    var screen by remember { mutableStateOf(startScreen) }

    val gps by SpeedProvider.state.collectAsState()
    val now by PlayerHub.now.collectAsState()
    val source by PlayerHub.source.collectAsState()
    val automation by AutomationService.status.collectAsState()

    var volume by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            volume = runCatching { PlayerHub.volumePercent() }.getOrDefault(0)
            delay(700)
        }
    }

    // Библиотека подтягивается один раз при запуске оболочки
    LaunchedEffect(Unit) {
        runCatching { PlayerHub.refreshLibrary() }
    }

    NeonBackdrop(accent, accent2, animated = settings.animatedBackground) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            label = "screen",
            modifier = Modifier.fillMaxSize()
        ) { target ->
            when (target) {
                NeonScreen.HOME -> HomeScreen(
                    settings = settings,
                    accent = accent,
                    accent2 = accent2,
                    gps = gps,
                    now = now,
                    source = source,
                    volumePercent = volume,
                    speedGainPercent = automation.speedGainPercent,
                    current = screen,
                    onSource = { s ->
                        when (s) {
                            MusicSource.DEVICE ->
                                PlayerHub.tracks.value.takeIf { it.isNotEmpty() }
                                    ?.let { PlayerHub.playTracks(it, 0) }
                                    ?: run { screen = NeonScreen.MUSIC }
                            MusicSource.RADIO ->
                                PlayerHub.stations.value.firstOrNull()?.let { PlayerHub.playStation(it) }
                            MusicSource.YANDEX -> PlayerHub.switchToYandex(launchApp = true)
                        }
                    },
                    onPlayPause = { PlayerHub.playPause() },
                    onNext = { PlayerHub.next() },
                    onPrev = { PlayerHub.prev() },
                    onVolume = { up -> PlayerHub.nudgeVolume(up); volume = PlayerHub.volumePercent() },
                    onOpenLibrary = { screen = NeonScreen.MUSIC },
                    onPhone = { screen = NeonScreen.PHONE },
                    onNavigation = {
                        NavigatorBridge.openFullscreen(context, settings.mapPackage)
                    },
                    onEqualizer = { screen = NeonScreen.EQUALIZER },
                    onAndroidSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    onAllApps = { screen = NeonScreen.APPS },
                    onLauncherSettings = { screen = NeonScreen.SETTINGS }
                )

                NeonScreen.APPS -> AllAppsScreen(
                    accent = accent,
                    accent2 = accent2,
                    onBack = { screen = NeonScreen.HOME }
                )

                NeonScreen.MUSIC -> MusicScreen(
                    accent = accent,
                    accent2 = accent2,
                    onBack = { screen = NeonScreen.HOME }
                )

                NeonScreen.EQUALIZER -> EqualizerScreen(
                    accent = accent,
                    accent2 = accent2,
                    onBack = { screen = NeonScreen.HOME }
                )

                NeonScreen.PHONE -> PhoneScreen(
                    accent = accent,
                    accent2 = accent2,
                    onBack = { screen = NeonScreen.HOME }
                )

                NeonScreen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    accent = accent,
                    accent2 = accent2,
                    onBack = { screen = NeonScreen.HOME },
                    edit = { block -> scope.launch { block(repo) } }
                )
            }
        }
    }
}
