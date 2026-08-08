package com.neondrive.launcher.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Скруглённая «стеклянная» панель с неоновым контуром и внутренним свечением. */
fun Modifier.neonPanel(
    accent: Color,
    radius: Dp = 22.dp,
    borderAlpha: Float = 0.45f,
    fill: Color = Neon.Panel
): Modifier = this
    .background(
        brush = Brush.verticalGradient(
            listOf(fill.copy(alpha = 0.92f), fill.copy(alpha = 0.66f))
        ),
        shape = RoundedCornerShape(radius)
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            listOf(accent.copy(alpha = borderAlpha), accent.copy(alpha = borderAlpha * 0.18f))
        ),
        shape = RoundedCornerShape(radius)
    )

/** Мягкое наружное свечение под элементом. */
fun Modifier.neonGlow(
    accent: Color,
    radius: Dp = 22.dp,
    strength: Float = 0.30f,
    spread: Dp = 18.dp
): Modifier = this.drawBehind {
    val px = spread.toPx()
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = strength), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = (size.minDimension / 2f) + px * 2f
        ),
        topLeft = Offset(-px, -px),
        size = Size(size.width + px * 2, size.height + px * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
            (radius + spread).toPx(), (radius + spread).toPx()
        )
    )
}

/** Тонкая неоновая обводка-«трассер» по периметру. */
fun Modifier.neonEdge(accent: Color, radius: Dp = 22.dp, width: Dp = 1.dp): Modifier =
    this.drawBehind {
        drawRoundRect(
            brush = Brush.sweepGradient(
                listOf(
                    accent.copy(alpha = 0.85f),
                    Color.Transparent,
                    accent.copy(alpha = 0.55f),
                    Color.Transparent,
                    accent.copy(alpha = 0.85f)
                )
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx(), radius.toPx()),
            style = Stroke(width = width.toPx())
        )
    }

/**
 * Фон рабочего стола: тёмный градиент + перспективная сетка + пара «пятен» неона.
 * Рисуется один раз на весь экран — дешевле, чем анимировать каждую панель.
 */
@Composable
fun NeonBackdrop(
    accent: Color,
    accent2: Color,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000), RepeatMode.Reverse),
        label = "drift"
    )
    val t = if (animated) drift else 0.5f

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    0f to Neon.Void,
                    0.55f to Neon.VoidSoft,
                    1f to Color(0xFF0D0718)
                )
            )
            .drawBehind {
                // Пятна неона
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * (0.18f + t * 0.06f), size.height * 0.12f),
                        radius = size.minDimension * 0.75f
                    ),
                    radius = size.minDimension * 0.75f,
                    center = Offset(size.width * (0.18f + t * 0.06f), size.height * 0.12f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent2.copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(size.width * (0.92f - t * 0.05f), size.height * 0.9f),
                        radius = size.minDimension * 0.7f
                    ),
                    radius = size.minDimension * 0.7f,
                    center = Offset(size.width * (0.92f - t * 0.05f), size.height * 0.9f)
                )

                // Перспективная сетка у нижней кромки
                val horizon = size.height * 0.68f
                val lineColor = Neon.Grid
                var i = 0
                var y = horizon
                while (y < size.height) {
                    drawLine(
                        color = lineColor.copy(alpha = 0.10f + i * 0.012f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    i++
                    y += 10f + i * 5f
                }
                for (x in -8..8) {
                    val bottomX = size.width / 2f + x * (size.width / 8f)
                    drawLine(
                        color = lineColor.copy(alpha = 0.09f),
                        start = Offset(size.width / 2f, horizon),
                        end = Offset(bottomX, size.height),
                        strokeWidth = 1f
                    )
                }
            },
        content = content
    )
}

/** Размытая «подложка» для крупных акцентных элементов (счётчик скорости и т. п.). */
fun Modifier.softBloom(color: Color, radius: Dp = 24.dp): Modifier =
    this.blur(radius).background(color.copy(alpha = 0.25f))
