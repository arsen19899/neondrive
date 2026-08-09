package com.neondrive.launcher.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.data.SpeedUnits
import com.neondrive.launcher.ui.theme.Neon
import kotlin.math.roundToInt

/**
 * Компактный спидометр: цифра скорости и подпись единиц измерения внутри
 * тонкой рамки со скруглёнными углами — без циферблата, заливки и свечения.
 *
 * Раньше это была большая кольцевая шкала на всю ширину колонки; теперь —
 * один из трёх маленьких индикаторов в общей строке приборов рабочего стола
 * (заправка · спидометр · погода), поэтому вся «графика» нарочно убрана.
 */
@Composable
fun SpeedoPanel(
    gps: GpsState,
    units: SpeedUnits,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val kmh = gps.speedKmh
    val shown = if (units == SpeedUnits.KMH) kmh else kmh / 1.609344f
    val animated by animateFloatAsState(shown, tween(300), label = "speed")

    Box(
        modifier
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = animated.roundToInt().toString(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (kmh > 140f) Neon.Red else Neon.TextHi,
                letterSpacing = (-1).sp
            )
            Text(
                text = units.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = accent.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
            )
        }
    }
}
