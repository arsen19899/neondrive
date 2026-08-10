package com.neondrive.launcher.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.automation.FuelState
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.automation.WeatherCondition
import com.neondrive.launcher.automation.WeatherState
import com.neondrive.launcher.data.SpeedUnits
import com.neondrive.launcher.ui.theme.Neon
import kotlin.math.roundToInt

/**
 * Строка приборов: расстояние до ближайшей заправки слева, компактный спидометр
 * по центру, погода в точке следования справа.
 *
 * Два требования, которые раньше нарушались:
 *  1. У каждого виджета непрозрачный тёмный фон, а не только рамка — на рабочем
 *     столе поверх тёмной подложки разница незаметна, но в режиме «поверх карты»
 *     виджеты рисуются прямо над живой картой, и без заливки светлые участки карты
 *     насквозь просвечивали через текст, делая его нечитаемым.
 *  2. Текст никогда не переносится на вторую строку — на любой ширине экрана,
 *     от 7" ГУ до 15" планшета. [BoxWithConstraints] снаружи подбирает размер
 *     шрифта под доступную ширину, а `maxLines = 1` с `TextOverflow.Clip` — это
 *     последняя страховка на случай совсем экстремальных пропорций: лучше обрезать
 *     последний символ, чем сломать строку на два ряда.
 */
@Composable
fun DriveInfoRow(
    gps: GpsState,
    units: SpeedUnits,
    accent: Color,
    accent2: Color,
    fuel: FuelState,
    weather: WeatherState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Пороги подобраны по ширине одного виджета (строка делится на 3 части
        // неравными весами 1 / 1.15 / 1), а не всей строки — так решение не
        // зависит от того, встроена ли строка в узкую боковую колонку рабочего
        // стола или в широкую панель поверх карты.
        val perWidgetWidth = maxWidth / 3.15f
        val compact = perWidgetWidth < 100.dp
        val tiny = perWidgetWidth < 78.dp

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FuelWidget(fuel, accent2, compact, tiny, Modifier.weight(1f))
            SpeedoPanel(gps, units, accent, compact, tiny, Modifier.weight(1.15f))
            WeatherWidget(weather, accent2, compact, tiny, Modifier.weight(1f))
        }
    }
}

/** Общий непрозрачный фон для приборных виджетов — как у HUD-плашек на карте. */
private fun Modifier.widgetSurface(accent: Color): Modifier = this
    .background(Color(0xE6060B14), RoundedCornerShape(14.dp))
    .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))

/** Расстояние по дорогам до ближайшей АЗС. См. [com.neondrive.launcher.automation.FuelStationHub]. */
@Composable
private fun FuelWidget(
    state: FuelState,
    accent: Color,
    compact: Boolean,
    tiny: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .widgetSurface(accent)
            .padding(horizontal = if (tiny) 6.dp else 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.LocalGasStation, null,
                tint = accent.copy(alpha = 0.85f),
                modifier = Modifier.size(if (tiny) 13.dp else 16.dp)
            )
            Spacer(Modifier.width(if (tiny) 3.dp else 6.dp))
            Text(
                text = when {
                    state.distanceKm != null -> formatKm(state.distanceKm)
                    state.loading -> "…"
                    else -> "—"
                },
                fontSize = if (tiny) 12.sp else if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Neon.TextHi,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
            if (!tiny) {
                Icon(
                    Icons.Rounded.ChevronRight, null,
                    tint = accent.copy(alpha = 0.45f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

/** Погода в точке следования. См. [com.neondrive.launcher.automation.WeatherHub]. */
@Composable
private fun WeatherWidget(
    state: WeatherState,
    accent: Color,
    compact: Boolean,
    tiny: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .widgetSurface(accent)
            .padding(horizontal = if (tiny) 6.dp else 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                weatherIcon(state.condition), null,
                tint = accent.copy(alpha = 0.85f),
                modifier = Modifier.size(if (tiny) 13.dp else 16.dp)
            )
            Spacer(Modifier.width(if (tiny) 3.dp else 6.dp))
            Text(
                text = state.tempC?.let { "${if (it > 0) "+" else ""}$it°" }
                    ?: if (state.loading) "…" else "—",
                fontSize = if (tiny) 12.sp else if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Neon.TextHi,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}

private fun weatherIcon(c: WeatherCondition): ImageVector = when (c) {
    WeatherCondition.CLEAR -> Icons.Rounded.WbSunny
    WeatherCondition.PARTLY_CLOUDY -> Icons.Rounded.WbCloudy
    WeatherCondition.CLOUDY -> Icons.Rounded.Cloud
    WeatherCondition.FOG -> Icons.Rounded.Cloud
    WeatherCondition.RAIN -> Icons.Rounded.Umbrella
    WeatherCondition.SNOW -> Icons.Rounded.AcUnit
    WeatherCondition.THUNDER -> Icons.Rounded.Bolt
    WeatherCondition.UNKNOWN -> Icons.Rounded.WbSunny
}

private fun formatKm(km: Float): String = if (km < 10f) "%.1f км".format(km) else "${km.roundToInt()} км"
