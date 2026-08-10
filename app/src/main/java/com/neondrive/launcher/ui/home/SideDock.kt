package com.neondrive.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.neondrive.launcher.nav.MapFrameController
import com.neondrive.launcher.ui.NeonScreen
import com.neondrive.launcher.ui.common.DockButton
import com.neondrive.launcher.ui.theme.neonPanel

/**
 * Док — «пульт» оболочки: часы и шесть плиток (телефон, навигация, эквалайзер,
 * настройки Android, все приложения, настройки оболочки).
 *
 * Две раскладки под одну и ту же логику, чтобы работать что на широком ландшафтном
 * экране ГУ, что на портретном планшете:
 *  • [horizontal] = false — классический вертикальный столбец сбоку (широкий экран);
 *  • [horizontal] = true  — горизонтальная полоса сверху/снизу (портретный экран),
 *    часы и плитки идут в ряд, при нехватке места полоса скроллится по горизонтали.
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
    modifier: Modifier = Modifier,
    horizontal: Boolean = false
) {
    // Плитка «Навигация» — тумблер фрейма, поэтому подсвечиваем её по состоянию
    // самого фрейма, а не по текущему экрану: так сразу видно, считает ли оболочка
    // карту поднятой, и понятно, что повторное нажатие её свернёт.
    val navFrameActive by MapFrameController.active.collectAsState()

    if (horizontal) {
        // Высота — не фиксированная: ClockCard рисует три строки текста (время,
        // дата, день недели), и на некоторых плотностях/масштабах шрифта системы
        // им не хватало жёстких 92dp — нижняя строка (день недели) обрезалась
        // панелью. minHeight гарантирует прежний компактный вид на типичных
        // экранах, а wrapContentHeight у Row ниже даёт контенту вырасти, если
        // ему тесно, вместо того чтобы обрезаться.
        Box(
            modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp)
                .neonPanel(accent, radius = 22.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ClockCard(accent = accent, use24h = use24h, compact = true, fillWidth = false)

                Box(
                    Modifier
                        .padding(vertical = 6.dp)
                        .width(1.dp)
                        .height(48.dp)
                        .background(accent.copy(alpha = 0.25f))
                )

                DockButton(Icons.Rounded.Phone, "Телефон", false, accent, onClick = onPhone)
                DockButton(
                    Icons.Rounded.Navigation, "Навигация",
                    navFrameActive, accent, onClick = onNavigation
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
        return
    }

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
                navFrameActive, accent, onClick = onNavigation
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
