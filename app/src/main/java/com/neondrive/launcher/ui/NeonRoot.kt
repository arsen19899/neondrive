package com.neondrive.launcher.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.neondrive.launcher.automation.AutomationService
import com.neondrive.launcher.automation.SpeedProvider
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MapMode
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.nav.MapFrameController
import com.neondrive.launcher.ui.apps.AllAppsScreen
import com.neondrive.launcher.ui.eq.EqualizerScreen
import com.neondrive.launcher.ui.home.HomeScreen
import com.neondrive.launcher.ui.music.MusicScreen
import com.neondrive.launcher.ui.phone.PhoneScreen
import com.neondrive.launcher.ui.settings.SettingsScreen
import com.neondrive.launcher.ui.theme.NeonAccent
import com.neondrive.launcher.ui.theme.NeonBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    // Автозапуск навигации во фрейме. Для плавающего окна сначала дожидаемся,
    // пока панель карты сообщит свои экранные границы.
    LaunchedEffect(settings.mapAutoStart, settings.mapMode, settings.mapPackage) {
        runCatching {
            MapFrameController.autoStartIfNeeded(context, settings) {
                MapFrameController.frameBounds.first { !it.isEmpty }
            }
        }
    }

    NeonBackdrop(
        accent, accent2,
        animated = settings.animatedBackground && !settings.reducedEffects,
        backgroundImagePath = settings.backgroundImagePath,
        backgroundDarken = settings.backgroundDarken
    ) {
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
                    onNavigation = { MapFrameController.launch(context, settings) },
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

                NeonScreen.APPS -> FrameSafeArea(settings) {
                    AllAppsScreen(
                        accent = accent,
                        accent2 = accent2,
                        onBack = { screen = NeonScreen.HOME }
                    )
                }

                NeonScreen.MUSIC -> FrameSafeArea(settings) {
                    MusicScreen(
                        accent = accent,
                        accent2 = accent2,
                        onBack = { screen = NeonScreen.HOME }
                    )
                }

                NeonScreen.EQUALIZER -> FrameSafeArea(settings) {
                    EqualizerScreen(
                        accent = accent,
                        accent2 = accent2,
                        onBack = { screen = NeonScreen.HOME }
                    )
                }

                NeonScreen.PHONE -> FrameSafeArea(settings) {
                    PhoneScreen(
                        accent = accent,
                        accent2 = accent2,
                        onBack = { screen = NeonScreen.HOME }
                    )
                }

                NeonScreen.SETTINGS -> FrameSafeArea(settings) {
                    SettingsScreen(
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
}

/**
 * Обёртка для всех экранов, кроме рабочего стола.
 *
 * Пока навигатор поднят «во фрейме» (MapMode.FRAME + MapFrameController.active),
 * его плавающее окно — отдельное окно системы, а не часть нашего UI. Оно висит
 * поверх части экрана ровно там, где раньше была панель карты, и остаётся там
 * независимо от того, что сейчас показывает оболочка под ним. Если рисовать
 * настройки (или любой другой экран) на весь экран как обычно, часть их —
 * там, где раньше была карта — физически перекрыта окном навигатора: не видна
 * и не нажимается.
 *
 * Поэтому в момент, когда навигатор во фрейме активен, контент вписывается
 * только в свободную полосу — ту же, что на рабочем столе занимают док и
 * колонка приборов (её границы — это буквально всё, что не входит в
 * [MapFrameController.frameBounds], последний раз сообщённый панелью карты).
 * В режиме «Поверх карты» и когда фрейм не активен, экран занимает всё место
 * как раньше.
 */
@Composable
private fun FrameSafeArea(
    settings: LauncherSettings,
    content: @Composable () -> Unit
) {
    val frameActive by MapFrameController.active.collectAsState()
    if (!frameActive || settings.mapMode != MapMode.FRAME) {
        content()
        return
    }

    val bounds by MapFrameController.frameBounds.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (bounds.isEmpty) {
            // Границы ещё не запоминались (например, оболочку перезапустили, пока
            // навигатор уже был поднят) — безопаснее показать во весь экран, чем
            // угадать не туда.
            content()
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        // Карта в портретной раскладке занимает всю ширину и нижнюю часть высоты;
        // в ландшафтной — всю высоту и часть ширины сбоку. По этому признаку и
        // определяем, где искать свободную полосу.
        val mapSpansFullWidth = bounds.width() >= screenWidthPx * 0.85f

        Box(Modifier.fillMaxSize().padding(12.dp)) {
            if (mapSpansFullWidth) {
                val freeHeightPx = bounds.top.toFloat().coerceAtLeast(0f)
                val freeHeight = with(density) { freeHeightPx.toDp() }
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(freeHeight)
                ) { content() }
            } else {
                val freeLeftPx = bounds.left.toFloat().coerceAtLeast(0f)
                val freeRightPx = (screenWidthPx - bounds.right).coerceAtLeast(0f)
                val onLeft = freeLeftPx >= freeRightPx
                val freeWidth = with(density) { (if (onLeft) freeLeftPx else freeRightPx).toDp() }
                Box(
                    Modifier
                        .align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                        .width(freeWidth)
                        .fillMaxHeight()
                ) { content() }
            }
        }
    }
}
