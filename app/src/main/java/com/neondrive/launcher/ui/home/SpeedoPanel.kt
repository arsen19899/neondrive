package com.neondrive.launcher.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.data.SpeedUnits
import com.neondrive.launcher.data.SpeedVolumeStep
import com.neondrive.launcher.ui.common.HudLabel
import com.neondrive.launcher.ui.common.NeonCard
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/* ── геометрия шкалы ── */
private const val MAX_KMH = 200f
private const val START_ANGLE = 135f      // 0 — снизу слева
private const val SWEEP = 270f            // максимум — снизу справа
private const val SEGMENTS = 44
private const val RED_ZONE_FROM = 0.7f    // с 140 км/ч сегменты уходят в красное

/**
 * GPS-спидометр. Кольцевая шкала с сегментной «лесенкой», точечными рисками и
 * подписями каждые 20 км/ч. Внизу — четыре ступени схемы «громкость от скорости»:
 * активная светится, так что сразу понятно, почему музыка стала громче.
 */
@Composable
fun SpeedoPanel(
    gps: GpsState,
    units: SpeedUnits,
    accent: Color,
    accent2: Color,
    speedGainPercent: Int,
    modifier: Modifier = Modifier,
    speedSteps: List<SpeedVolumeStep> = emptyList()
) {
    val kmh = gps.speedKmh
    val shown = if (units == SpeedUnits.KMH) kmh else kmh / 1.609344f
    val animated by animateFloatAsState(shown, tween(420), label = "speed")
    val animatedKmh by animateFloatAsState(kmh, tween(420), label = "speedKmh")
    val fraction = (animatedKmh / MAX_KMH).coerceIn(0f, 1f)

    val measurer = rememberTextMeasurer()
    val tickStyle = remember {
        TextStyle(fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }

    val activeStep = remember(speedSteps, animatedKmh) {
        speedSteps.sortedBy { it.fromKmh }.indexOfLast { animatedKmh >= it.fromKmh }
    }

    NeonCard(modifier = modifier, accent = accent) {

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HudLabel("GPS", accent)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    !gps.permissionGranted -> "НЕТ ДОСТУПА"
                    gps.hasFix -> "FIX · ±${gps.accuracyM.roundToInt()} м"
                    else -> "ПОИСК…"
                },
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = if (gps.hasFix) accent.copy(alpha = 0.85f) else Neon.TextLow
            )
        }

        Spacer(Modifier.height(4.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawGauge(
                    fraction = fraction,
                    accent = accent,
                    accent2 = accent2,
                    measurer = measurer,
                    tickStyle = tickStyle
                )
            }

            // Центр: цифра, единицы и ступени громкости
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = animated.roundToInt().toString(),
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (animatedKmh > 140f) Neon.Red else Neon.TextHi,
                    letterSpacing = (-2).sp
                )
                Text(
                    units.label.uppercase(),
                    fontSize = 10.sp,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = accent.copy(alpha = 0.8f)
                )
                if (speedSteps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    StepDots(speedSteps, activeStep, accent, accent2)
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SAT ${gps.satellites}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = Neon.TextLow
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (speedGainPercent > 0) "VOL +$speedGainPercent%" else "VOL БАЗА",
                fontSize = 9.sp,
                fontWeight = if (speedGainPercent > 0) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                color = if (speedGainPercent > 0) accent2 else Neon.TextLow
            )
        }
    }
}

/** Четыре ступени схемы «громкость от скорости» под цифрой. */
@Composable
private fun StepDots(
    steps: List<SpeedVolumeStep>,
    activeIndex: Int,
    accent: Color,
    accent2: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        steps.sortedBy { it.fromKmh }.forEachIndexed { i, _ ->
            val on = i == activeIndex
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (on) 8.dp else 5.dp)
                    .then(if (on) Modifier.neonGlow(accent2, 4.dp, 0.5f, 5.dp) else Modifier)
                    .background(
                        color = if (on) accent2 else accent.copy(alpha = 0.28f),
                        shape = CircleShape
                    )
            )
        }
    }
}

/* ═════════════════  ОТРИСОВКА ШКАЛЫ  ═════════════════ */

private fun DrawScope.drawGauge(
    fraction: Float,
    accent: Color,
    accent2: Color,
    measurer: TextMeasurer,
    tickStyle: TextStyle
) {
    val side = size.minDimension
    val c = Offset(size.width / 2f, size.height / 2f)

    val rWing = side * 0.500f      // угловые «крылья»
    val rRing = side * 0.470f      // тонкое внешнее кольцо
    val rLabel = side * 0.408f     // подписи скорости
    val rDot = side * 0.352f       // точечные риски
    val rSegOut = side * 0.318f    // сегментная лесенка, наружный край
    val rSegIn = side * 0.248f     // …и внутренний
    val rCore = side * 0.218f      // тёмное ядро под цифрой

    /* ── 1. Крылья по углам: «зло» и объём ── */
    drawWings(c, rWing, accent, accent2)

    /* ── 2. Внешнее кольцо ── */
    drawCircle(
        color = accent.copy(alpha = 0.16f),
        radius = rRing,
        center = c,
        style = Stroke(width = side * 0.012f)
    )

    /* ── 3. Точки и подписи ── */
    var v = 0
    while (v <= MAX_KMH.toInt()) {
        val t = v / MAX_KMH
        val a = ((START_ANGLE + SWEEP * t) * PI / 180.0)
        val hot = t >= RED_ZONE_FROM
        val tint = if (hot) Neon.Red else lerp(accent, accent2, t / RED_ZONE_FROM)

        val major = v % 20 == 0
        drawCircle(
            color = tint.copy(alpha = if (major) 0.85f else 0.35f),
            radius = if (major) side * 0.010f else side * 0.006f,
            center = Offset(c.x + rDot * cos(a).toFloat(), c.y + rDot * sin(a).toFloat())
        )

        if (major) {
            val layout = measurer.measure(
                text = v.toString(),
                style = tickStyle.copy(color = if (hot) Neon.Red else Neon.TextMid)
            )
            val lx = c.x + rLabel * cos(a).toFloat() - layout.size.width / 2f
            val ly = c.y + rLabel * sin(a).toFloat() - layout.size.height / 2f
            drawText(layout, topLeft = Offset(lx, ly))
        }
        v += 10
    }

    /* ── 4. Сегментная лесенка ── */
    val segAngle = SWEEP / SEGMENTS
    val gap = segAngle * 0.28f
    val filled = fraction * SEGMENTS

    for (i in 0 until SEGMENTS) {
        val t = i / (SEGMENTS - 1f)
        val on = i < filled
        val hot = t >= RED_ZONE_FROM
        val base = if (hot) lerp(accent2, Neon.Red, (t - RED_ZONE_FROM) / (1f - RED_ZONE_FROM))
        else lerp(accent, accent2, t / RED_ZONE_FROM)

        val a0 = START_ANGLE + segAngle * i + gap / 2f
        val a1 = a0 + segAngle - gap
        val rIn = if (on) rSegIn else rSegIn + (rSegOut - rSegIn) * 0.28f

        val seg = segmentPath(c, rIn, rSegOut, a0, a1)
        drawPath(seg, color = if (on) base else base.copy(alpha = 0.13f))

        // «Ореол» под последними горящими сегментами — эффект накала
        if (on && i > filled - 4) {
            drawPath(seg, color = Color.White.copy(alpha = 0.22f))
        }
    }

    /* ── 5. Стрелка-указатель на конце заливки ── */
    if (fraction > 0.001f) {
        val a = ((START_ANGLE + SWEEP * fraction) * PI / 180.0)
        val tip = Offset(
            c.x + (rSegOut + side * 0.030f) * cos(a).toFloat(),
            c.y + (rSegOut + side * 0.030f) * sin(a).toFloat()
        )
        val tint = if (fraction >= RED_ZONE_FROM) Neon.Red else accent2
        drawCircle(
            brush = Brush.radialGradient(
                listOf(tint.copy(alpha = 0.55f), Color.Transparent),
                center = tip, radius = side * 0.085f
            ),
            radius = side * 0.085f,
            center = tip
        )
        drawCircle(color = Color.White, radius = side * 0.012f, center = tip)
    }

    /* ── 6. Ядро с внутренним свечением ── */
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color(0xFF0A1120), Color(0xFF05080F)),
            center = c, radius = rCore
        ),
        radius = rCore,
        center = c
    )
    drawCircle(
        brush = Brush.sweepGradient(
            listOf(
                accent.copy(alpha = 0.9f),
                accent2.copy(alpha = 0.9f),
                Neon.Red.copy(alpha = 0.6f),
                accent.copy(alpha = 0.9f)
            ),
            center = c
        ),
        radius = rCore,
        center = c,
        style = Stroke(width = side * 0.011f)
    )
    // мягкий ореол вокруг ядра
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.Transparent, accent2.copy(alpha = 0.18f), Color.Transparent),
            center = c, radius = rCore * 1.35f
        ),
        radius = rCore * 1.35f,
        center = c
    )
}

/** Четыре угловых «крыла» — то, что делает прибор злым, а не круглым и добрым. */
private fun DrawScope.drawWings(c: Offset, r: Float, accent: Color, accent2: Color) {
    val sweep = 34f
    val stroke = r * 0.20f
    listOf(
        (-58f) to accent2,
        (32f) to accent,
        (122f) to accent2,
        (212f) to accent
    ).forEach { (start, color) ->
        drawArc(
            brush = Brush.linearGradient(
                listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.04f))
            ),
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
    }
}

/** Один сегмент лесенки — трапеция между двумя радиусами. */
private fun segmentPath(
    c: Offset,
    rIn: Float,
    rOut: Float,
    aStartDeg: Float,
    aEndDeg: Float
): Path {
    val a0 = aStartDeg * PI / 180.0
    val a1 = aEndDeg * PI / 180.0
    return Path().apply {
        moveTo(c.x + rIn * cos(a0).toFloat(), c.y + rIn * sin(a0).toFloat())
        lineTo(c.x + rOut * cos(a0).toFloat(), c.y + rOut * sin(a0).toFloat())
        lineTo(c.x + rOut * cos(a1).toFloat(), c.y + rOut * sin(a1).toFloat())
        lineTo(c.x + rIn * cos(a1).toFloat(), c.y + rIn * sin(a1).toFloat())
        close()
    }
}
