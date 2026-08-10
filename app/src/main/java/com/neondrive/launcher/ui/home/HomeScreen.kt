package com.neondrive.launcher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 * Две раскладки в зависимости от пропорций экрана — от компактных ГУ 7" до
 * портретных и ландшафтных планшетов 15":
 *  • Альбомная (ширина больше высоты) — слева направо: колонка на 25 % ширины
 *    (спидометр сверху, плеер снизу), карта на оставшиеся ~2/3 и вертикальный
 *    док с часами сбоку;
 *  • Портретная (высота больше ширины) — сверху вниз: горизонтальная полоса
 *    дока, приборы и плеер компактной высоты, карта занимает всё оставшееся
 *    место по вертикали.
 * Сторона дока в альбомной раскладке меняется в настройках — под правый или левый руль;
 * в портретной док всегда сверху, чтобы не спорить с эргономикой руля.
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
        // Портретный экран (планшет/ГУ, поставленный на попа) шире, чем выше —
        // наоборот, ландшафтный. Порог 1:1 сам решает, какую раскладку строить;
        // на любой промежуточной пропорции (почти квадратный экран) она просто
        // выбирает ближайшую по факту без специального «третьего» варианта.
        val isPortrait = maxHeight > maxWidth

        val dock: @Composable (Modifier) -> Unit = { m ->
            SideDock(
                accent = accent,
                use24h = settings.show24h,
                current = current,
                onPhone = onPhone,
                onNavigation = onNavigation,
                onEqualizer = onEqualizer,
                onAndroidSettings = onAndroidSettings,
                onAllApps = onAllApps,
                onLauncherSettings = onLauncherSettings,
                horizontal = isPortrait,
                modifier = m
            )
        }

        val driveInfo: @Composable () -> Unit = {
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
        }

        val player: @Composable (Modifier) -> Unit = { m ->
            PlayerPanel(
                now = now,
                source = source,
                accent = accent,
                accent2 = accent2,
                volumePercent = volumePercent,
                modifier = m.fillMaxWidth(),
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

        val map: @Composable (Modifier) -> Unit = { m ->
            // Карта — всё остальное пространство. Пока настоящий навигатор поднят
            // во фрейме, панель-заглушка не нужна: реальное окно уже стоит на её месте.
            if (!mapCollapsed) {
                MapPanel(
                    gps = gps,
                    settings = settings,
                    accent = accent,
                    accent2 = accent2,
                    modifier = m
                )
            }
        }

        if (isPortrait) {
            // Сверху вниз: горизонтальная полоса дока, приборы и плеер компактной
            // высоты (не резиновые — иначе на высоком экране плеер растянется
            // на пол-экрана впустую), карта забирает весь остаток по вертикали.
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                dock(Modifier.fillMaxWidth())
                driveInfo()
                player(Modifier.heightIn(min = 150.dp, max = 260.dp))
                map(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        } else {
            val columnWidth = maxWidth * 0.25f

            // Колонка приборов: спидометр сверху, плеер снизу.
            // Когда навигатор занял место карты, колонка растягивается на всё
            // освободившееся пространство вместо фиксированной четверти экрана.
            val instruments: @Composable RowScope.() -> Unit = {
                Column(
                    Modifier
                        .then(if (mapCollapsed) Modifier.weight(1f) else Modifier.width(columnWidth))
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    driveInfo()
                    player(Modifier.weight(1f))
                }
            }

            val mapCell: @Composable RowScope.() -> Unit = {
                map(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            Row(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (settings.sidebarSide == SidebarSide.LEFT) dock(Modifier.fillMaxHeight())

                // Сторона карты относительно приборов настраивается отдельно от
                // стороны дока: на разных ГУ удобной оказывается разная комбинация
                // (руль слева/справа, экран смещён к водителю). Свободная полоса,
                // в которую вписываются вторичные экраны при поднятом фрейме,
                // считается по фактическим границам панели карты, поэтому оба
                // варианта отрабатывают одинаково — см. FrameSafeArea в NeonRoot.
                if (settings.mapSide == SidebarSide.LEFT) {
                    mapCell()
                    instruments()
                } else {
                    instruments()
                    mapCell()
                }

                if (settings.sidebarSide == SidebarSide.RIGHT) dock(Modifier.fillMaxHeight())
            }
        }
    }
}
