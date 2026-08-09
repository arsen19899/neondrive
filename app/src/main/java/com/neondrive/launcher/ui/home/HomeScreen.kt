package com.neondrive.launcher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neondrive.launcher.automation.FuelStationHub
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.automation.WeatherHub
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MapMode
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.SidebarSide
import com.neondrive.launcher.media.NowPlaying
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.nav.MapFrameController
import com.neondrive.launcher.ui.NeonScreen

/**
 * Рабочий стол.
 *
 * Слева направо: колонка на 25 % ширины (спидометр сверху, плеер снизу),
 * карта на оставшиеся ~2/3 и вертикальный док с часами. Сторона дока меняется
 * в настройках — под правый или левый руль.
 */
@Composable
fun HomeScreen(
    settings: LauncherSettings,
    accent: Color,
    accent2: Color,
    gps: GpsState,
    now: NowPlaying,
    source: MusicSource,
    volumePercent: Int,
    speedGainPercent: Int,
    current: NeonScreen,
    onSource: (MusicSource) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onVolume: (Boolean) -> Unit,
    onOpenLibrary: () -> Unit,
    onPhone: () -> Unit,
    onNavigation: () -> Unit,
    onEqualizer: () -> Unit,
    onAndroidSettings: () -> Unit,
    onAllApps: () -> Unit,
    onLauncherSettings: () -> Unit
) {
    // Состояние лайка и подключения читаем прямо у хаба — оно нужно только плееру
    val liked by PlayerHub.external.liked.collectAsState()
    val canLike by PlayerHub.external.canLike.collectAsState()
    val connecting by PlayerHub.connectingYandex.collectAsState()

    // Пока навигатор поднят «во фрейме», настоящее приложение уже стоит ровно там,
    // где раньше была карта-обманка — панель карты больше не нужна, а освободившееся
    // место отдаём приборам. Док с часами при этом сохраняет свой размер.
    val navFrameActive by MapFrameController.active.collectAsState()
    val mapCollapsed = navFrameActive && settings.mapMode == MapMode.FRAME

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columnWidth = maxWidth * 0.25f

        val dock: @Composable () -> Unit = {
            SideDock(
                accent = accent,
                use24h = settings.show24h,
                current = current,
                onPhone = onPhone,
                onNavigation = onNavigation,
                onEqualizer = onEqualizer,
                onAndroidSettings = onAndroidSettings,
                onAllApps = onAllApps,
                onLauncherSettings = onLauncherSettings
            )
        }

        Row(
            Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (settings.sidebarSide == SidebarSide.LEFT) dock()

            // Колонка приборов: спидометр сверху, плеер снизу.
            // Когда навигатор занял место карты, колонка растягивается на всё
            // освободившееся пространство вместо фиксированной четверти экрана.
            Column(
                Modifier
                    .then(if (mapCollapsed) Modifier.weight(1f) else Modifier.width(columnWidth))
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (settings.showSpeedometer) {
                    val fuel by FuelStationHub.state.collectAsState()
                    val weather by WeatherHub.state.collectAsState()
                    DriveInfoRow(
                        gps = gps,
                        units = settings.units,
                        accent = accent,
                        accent2 = accent2,
                        fuel = fuel,
                        weather = weather,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                PlayerPanel(
                    now = now,
                    source = source,
                    accent = accent,
                    accent2 = accent2,
                    volumePercent = volumePercent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    liked = liked,
                    canLike = canLike,
                    connecting = connecting,
                    onLike = { PlayerHub.toggleLike() },
                    onSource = onSource,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrev = onPrev,
                    onVolume = onVolume,
                    onOpenLibrary = onOpenLibrary
                )
            }

            // Карта — всё остальное пространство. Пока настоящий навигатор поднят
            // во фрейме, панель-заглушка не нужна: реальное окно уже стоит на её месте.
            if (!mapCollapsed) {
                MapPanel(
                    gps = gps,
                    settings = settings,
                    accent = accent,
                    accent2 = accent2,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            if (settings.sidebarSide == SidebarSide.RIGHT) dock()
        }
    }
}
