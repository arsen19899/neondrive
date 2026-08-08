package com.neondrive.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neondrive.launcher.ui.NeonScreen
import com.neondrive.launcher.ui.common.DockButton
import com.neondrive.launcher.ui.theme.neonPanel

/**
 * Вертикальный док — «пульт» оболочки. Сверху часы и дата, ниже шесть плиток:
 * телефон, навигация, эквалайзер, настройки Android, все приложения, настройки оболочки.
 */
@Composable
fun SideDock(
    accent: Color,
    use24h: Boolean,
    current: NeonScreen,
    onPhone: () -> Unit,
    onNavigation: () -> Unit,
    onEqualizer: () -> Unit,
    onAndroidSettings: () -> Unit,
    onAllApps: () -> Unit,
    onLauncherSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .width(96.dp)
            .fillMaxHeight()
            .neonPanel(accent, radius = 26.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ClockCard(accent = accent, use24h = use24h, compact = true)

            Box(
                Modifier
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(accent.copy(alpha = 0.25f))
            )

            DockButton(Icons.Rounded.Phone, "Телефон", false, accent, onClick = onPhone)
            DockButton(
                Icons.Rounded.Navigation, "Навигация",
                current == NeonScreen.HOME, accent, onClick = onNavigation
            )
            DockButton(
                Icons.Rounded.Equalizer, "Эквалайзер",
                current == NeonScreen.EQUALIZER, accent, onClick = onEqualizer
            )
            DockButton(Icons.Rounded.Settings, "Система", false, accent, onClick = onAndroidSettings)
            DockButton(
                Icons.Rounded.Apps, "Приложения",
                current == NeonScreen.APPS, accent, onClick = onAllApps
            )
            DockButton(
                Icons.Rounded.Tune, "Оболочка",
                current == NeonScreen.SETTINGS, accent, onClick = onLauncherSettings
            )
        }
    }
}
