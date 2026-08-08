package com.neondrive.launcher.ui.eq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.media.AudioFxController
import com.neondrive.launcher.ui.common.HudLabel
import com.neondrive.launcher.ui.common.NeonCard
import com.neondrive.launcher.ui.common.NeonScreenScaffold
import com.neondrive.launcher.ui.common.NeonSlider
import com.neondrive.launcher.ui.common.NeonToggle
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(accent: Color, accent2: Color, onBack: () -> Unit) {
    val eq by AudioFxController.state.collectAsState()
    LaunchedEffect(Unit) { AudioFxController.refresh() }

    NeonScreenScaffold(
        title = "Эквалайзер",
        subtitle = if (eq.available) "${eq.bands.size} полос · системные аудиоэффекты"
        else "Аудиоэффекты недоступны на этом устройстве",
        accent = accent,
        onBack = onBack,
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudLabel(if (eq.enabled) "ВКЛ" else "ВЫКЛ", accent)
                Spacer(Modifier.size(10.dp))
                NeonToggle(eq.enabled, accent) { AudioFxController.setEnabled(it) }
            }
        }
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Полосы
            NeonCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                accent = accent
            ) {
                HudLabel("Полосы", accent)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    eq.bands.forEach { band ->
                        BandSlider(
                            hz = band.centerHz,
                            level = band.levelMb.toInt(),
                            min = eq.minLevelMb.toInt(),
                            max = eq.maxLevelMb.toInt(),
                            accent = accent,
                            accent2 = accent2
                        ) { AudioFxController.setBand(band.index, it.toShort()) }
                    }
                    if (eq.bands.isEmpty()) {
                        Text(
                            "Полосы не обнаружены",
                            color = Neon.TextLow,
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip("Плоско", accent, false) { AudioFxController.flatten() }
                    eq.presets.forEachIndexed { i, name ->
                        Chip(name, accent2, eq.currentPreset == i) { AudioFxController.applyPreset(i) }
                    }
                }
            }

            // Эффекты
            NeonCard(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
                accent = accent2
            ) {
                HudLabel("Эффекты", accent2)
                Spacer(Modifier.height(14.dp))

                FxSlider("Бас", eq.bassBoost, 0..1000, accent2) { AudioFxController.setBassBoost(it) }
                FxSlider("Объём", eq.virtualizer, 0..1000, accent) { AudioFxController.setVirtualizer(it) }
                FxSlider("Громкость (loudness)", eq.loudness, 0..1500, accent2) {
                    AudioFxController.setLoudness(it)
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Эффекты применяются к встроенному плееру оболочки. На части головных " +
                        "устройств прошивка отдаёт глобальную аудиосессию — тогда настройки " +
                        "действуют и на сторонние приложения, включая Яндекс.Музыку.",
                    color = Neon.TextLow,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun BandSlider(
    hz: Int,
    level: Int,
    min: Int,
    max: Int,
    accent: Color,
    accent2: Color,
    onChange: (Int) -> Unit
) {
    val fraction = ((level - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "%+d".format(level / 100),
            color = accent2.copy(alpha = 0.9f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(34.dp)
                .weight(1f)
                .clip(RoundedCornerShape(17.dp))
                .background(Color(0xFF0C1424))
                .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(17.dp))
                .pointerInput(hz, min, max) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val heightPx = size.height.toFloat().coerceAtLeast(1f)
                        val delta = -dragAmount / heightPx * (max - min)
                        onChange((level + delta).roundToInt().coerceIn(min, max))
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                    .neonGlow(accent2, 17.dp, 0.18f, 6.dp)
                    .background(
                        Brush.verticalGradient(listOf(accent2, accent.copy(alpha = 0.5f))),
                        RoundedCornerShape(17.dp)
                    )
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (hz >= 1000) "${hz / 1000}k" else "$hz",
            color = Neon.TextLow,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun FxSlider(
    label: String,
    value: Int,
    range: IntRange,
    accent: Color,
    onChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Neon.TextMid, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "${(value * 100f / range.last).roundToInt()}%",
                color = accent,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        NeonSlider(
            value = value.toFloat(),
            range = range.first.toFloat()..range.last.toFloat(),
            accent = accent
        ) { onChange(it.roundToInt()) }
    }
}

@Composable
private fun Chip(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) color.copy(alpha = 0.2f) else Color(0x330C1424))
            .border(
                1.dp,
                if (selected) color.copy(alpha = 0.8f) else color.copy(alpha = 0.25f),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) color else Neon.TextMid,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
