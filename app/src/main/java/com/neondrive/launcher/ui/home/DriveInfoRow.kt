package com.neondrive.launcher.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
 * Строка приборов на рабочем столе: расстояние до ближайшей заправки слева,
 * компактный спидометр по центру, погода в точке следования справа.
 * Специально невысокая — раньше спидометр был квадратной шкалой во всю
 * колонку, теперь это одна тонкая полоса, под плеер остаётся больше места.
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
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FuelWidget(fuel, accent2, Modifier.weight(1f))
        SpeedoPanel(gps, units, accent, Modifier.weight(1.1f))
        WeatherWidget(weather, accent2, Modifier.weight(1f))
    }
}

/** Расстояние по дорогам до ближайшей АЗС. См. [com.neondrive.launcher.automation.FuelStationHub]. */
@Composable
private fun FuelWidget(state: FuelState, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.LocalGasStation, null,
                tint = accent.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    state.distanceKm != null -> formatKm(state.distanceKm)
                    state.loading -> "…"
                    else -> "—"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Neon.TextHi
            )
            Icon(
                Icons.Rounded.ChevronRight, null,
                tint = accent.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** Погода в точке следования. См. [com.neondrive.launcher.automation.WeatherHub]. */
@Composable
private fun WeatherWidget(state: WeatherState, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                weatherIcon(state.condition), null,
                tint = accent.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = state.tempC?.let { "${if (it > 0) "+" else ""}$it°" }
                    ?: if (state.loading) "…" else "—",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Neon.TextHi
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
