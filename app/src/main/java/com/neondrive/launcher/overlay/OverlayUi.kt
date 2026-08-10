package com.neondrive.launcher.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.MainActivity
import com.neondrive.launcher.automation.FuelStationHub
import com.neondrive.launcher.automation.SpeedProvider
import com.neondrive.launcher.automation.WeatherHub
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.ui.home.ClockCard
import com.neondrive.launcher.ui.home.DriveInfoRow
import com.neondrive.launcher.ui.home.PlayerPanel
import com.neondrive.launcher.ui.theme.NeonAccent
import com.neondrive.launcher.ui.theme.NeonDriveTheme
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import com.neondrive.launcher.ui.theme.neonPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Колонка приборов поверх карты: спидометр сверху, плеер снизу.
 * Разметка намеренно повторяет рабочий стол, чтобы переход между режимами
 * не ощущался сменой интерфейса.
 */
@Composable
fun OverlayColumn(
    settingsFlow: StateFlow<LauncherSettings>,
    onOpenLauncher: () -> Unit
) {
    val settings by settingsFlow.collectAsState()
    val accentSpec = NeonAccent.fromName(settings.accent)
    val gps by SpeedProvider.state.collectAsState()
    val now by PlayerHub.now.collectAsState()
    val source by PlayerHub.source.collectAsState()
    val liked by PlayerHub.external.liked.collectAsState()
    val canLike by PlayerHub.external.canLike.collectAsState()
    val connecting by PlayerHub.connectingYandex.collectAsState()
    val fuel by FuelStationHub.state.collectAsState()
    val weather by WeatherHub.state.collectAsState()

    var volume by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            volume = runCatching { PlayerHub.volumePercent() }.getOrDefault(0)
            delay(700)
        }
    }

    NeonDriveTheme(accent = accentSpec, reducedEffects = settings.reducedEffects) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (settings.showSpeedometer) {
                DriveInfoRow(
                    gps = gps,
                    units = settings.units,
                    accent = accentSpec.primary,
                    accent2 = accentSpec.secondary,
                    fuel = fuel,
                    weather = weather,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            PlayerPanel(
                now = now,
                source = source,
                accent = accentSpec.primary,
                accent2 = accentSpec.secondary,
                volumePercent = volume,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                liked = liked,
                canLike = canLike,
                connecting = connecting,
                onLike = { PlayerHub.toggleLike() },
                onSource = { s ->
                    when (s) {
                        MusicSource.DEVICE -> PlayerHub.tracks.value
                            .takeIf { it.isNotEmpty() }?.let { PlayerHub.playTracks(it, 0) }
                        MusicSource.RADIO -> PlayerHub.stations.value
                            .firstOrNull()?.let { PlayerHub.playStation(it) }
                        MusicSource.YANDEX -> PlayerHub.switchToYandex()
                    }
                },
                onPlayPause = { PlayerHub.playPause() },
                onNext = { PlayerHub.next() },
                onPrev = { PlayerHub.prev() },
                onVolume = { up -> PlayerHub.nudgeVolume(up); volume = PlayerHub.volumePercent() },
                onOpenLibrary = onOpenLauncher
            )
        }
    }
}

/** Компактный док поверх карты: часы и самое нужное на ходу. */
@Composable
fun OverlayDock(
    settingsFlow: StateFlow<LauncherSettings>,
    onOpenLauncher: () -> Unit,
    onHide: () -> Unit
) {
    val settings by settingsFlow.collectAsState()
    val accentSpec = NeonAccent.fromName(settings.accent)
    val accent = accentSpec.primary
    val now by PlayerHub.now.collectAsState()
    val context = LocalContext.current

    NeonDriveTheme(accent = accentSpec, reducedEffects = settings.reducedEffects) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .neonPanel(accent, radius = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ClockCard(accent = accent, use24h = settings.show24h, compact = true)

                Box(
                    Modifier
                        .padding(horizontal = 14.dp, vertical = 2.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(accent.copy(alpha = 0.25f))
                )

                OverlayTile(Icons.Rounded.Home, "Рабочий стол", accent, onOpenLauncher)
                OverlayTile(
                    if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (now.isPlaying) "Пауза" else "Играть",
                    accentSpec.secondary
                ) { PlayerHub.playPause() }
                OverlayTile(Icons.Rounded.SkipNext, "Дальше", accent) { PlayerHub.next() }
                OverlayTile(Icons.Rounded.Phone, "Телефон", accent) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(context, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN, "phone")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                OverlayTile(Icons.Rounded.Apps, "Приложения", accent) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(context, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN, "apps")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                OverlayTile(Icons.Rounded.Close, "Убрать", Neon.Red, onHide)
            }
        }
    }
}

@Composable
private fun OverlayTile(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp)
                .neonGlow(color, 18.dp, 0.18f, 10.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xE60A0F1A))
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = color, modifier = Modifier.size(24.dp))
        }
        Text(label, fontSize = 9.sp, color = Neon.TextLow, modifier = Modifier.padding(top = 4.dp))
    }
}
