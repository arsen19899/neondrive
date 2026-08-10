package com.neondrive.launcher.ui.home

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.automation.GpsState
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MapMode
import com.neondrive.launcher.nav.MapFrameController
import com.neondrive.launcher.nav.NavigatorBridge
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import com.neondrive.launcher.ui.theme.neonPanel

/**
 * Панель навигации — две трети рабочего стола.
 *
 * Панель рисует собственный HUD по данным GPS, пока настоящее навигационное
 * приложение не поднято, чтобы рабочий стол не выглядел пустым. Когда навигатор
 * поднят «во фрейме», эта панель не рисуется вовсе — её место занимает реальное
 * плавающее окно.
 *
 * Границы ячейки под карту сообщает в [MapFrameController] сам рабочий стол
 * ([HomeScreen]), а не эта панель: по ним навигатор поднимается плавающим окном и
 * по ним же считается свободная полоса для вторичных экранов, а нужны они и тогда,
 * когда панель не нарисована.
 */
@Composable
fun MapPanel(
    gps: GpsState,
    settings: LauncherSettings,
    accent: Color,
    accent2: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val overlayActive by MapFrameController.active.collectAsState()
    val navLabel = remember(settings.mapPackage) {
        NavigatorBridge.labelOf(context, settings.mapPackage)
    }

    val launch: () -> Unit = { MapFrameController.launch(context, settings) }

    // Границы ячейки карты сообщает не эта панель, а сам рабочий стол (HomeScreen):
    // они нужны и тогда, когда панель не рисуется — пока настоящий навигатор стоит
    // на её месте плавающим окном.
    Box(
        modifier
            .neonGlow(accent, 26.dp, 0.14f, 16.dp)
            .neonPanel(accent, radius = 26.dp)
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = launch)
    ) {
        MapCanvas(accent = accent, accent2 = accent2, moving = gps.speedKmh > 1f)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xAA060B14))
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Navigation, null,
                        tint = accent, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        navLabel.uppercase(),
                        fontSize = 10.sp,
                        letterSpacing = 1.4.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Neon.TextMid
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (gps.hasFix) "КУРС ${gps.bearingDeg.toInt()}°  ·  ${gps.altitudeM.toInt()} м"
                else "ОЖИДАНИЕ СПУТНИКОВ",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (gps.hasFix) accent.copy(alpha = 0.8f) else Neon.TextLow
            )
        }

        // Подсказка про активный режим — по центру, поверх HUD
        Box(
            Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xB3060B14))
                .border(1.dp, accent2.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .clickable(onClick = launch)
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Text(
                if (settings.mapMode == MapMode.FRAME)
                    "Нажмите, чтобы открыть $navLabel во фрейме"
                else
                    "Нажмите, чтобы открыть $navLabel с панелями поверх",
                fontSize = 12.sp,
                color = accent2,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickChip("Домой", Icons.Rounded.Home, accent) {
                if (settings.hasHomePoint) {
                    NavigatorBridge.buildRoute(
                        context, settings.mapPackage,
                        settings.homeLat, settings.homeLon,
                        gps.lastLat.takeIf { gps.hasFix },
                        gps.lastLon.takeIf { gps.hasFix }
                    )
                } else {
                    Toast.makeText(
                        context,
                        "Точка «Дом» не задана: настройки оболочки → Навигатор",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            QuickChip("Я здесь", Icons.Rounded.MyLocation, accent) {
                if (gps.hasFix) {
                    NavigatorBridge.showPoint(
                        context, settings.mapPackage, gps.lastLat, gps.lastLon, 16, "Моя позиция"
                    )
                } else {
                    Toast.makeText(context, "Нет GPS-фикса", Toast.LENGTH_SHORT).show()
                }
            }
            if (overlayActive) {
                QuickChip("Убрать панели", Icons.Rounded.VisibilityOff, Neon.Red) {
                    MapFrameController.stop(context)
                }
            }

            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .neonGlow(accent2, 16.dp, 0.35f, 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent2.copy(alpha = 0.20f))
                    .border(1.dp, accent2.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                    .clickable(onClick = launch)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.OpenInFull, null,
                        tint = accent2, modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(7.dp))
                    Text(
                        when (settings.mapMode) {
                            MapMode.FRAME -> "ВО ФРЕЙМЕ"
                            MapMode.OVERLAY -> "ПОВЕРХ КАРТЫ"
                        },
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = accent2
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickChip(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0xAA060B14))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, fontSize = 12.sp, color = Neon.TextMid)
    }
}

/** Стилизованная карта-HUD: сетка кварталов, магистраль, маркер машины. */
@Composable
private fun MapCanvas(accent: Color, accent2: Color, moving: Boolean) {
    // «Упрощённая графика» останавливает декоративный дрейф и пульсацию —
    // это чисто фоновая заглушка, ей не обязательно гонять перерисовку вечно.
    val reduced = com.neondrive.launcher.ui.theme.LocalReducedEffects.current
    val scroll: Float
    val pulse: Float
    if (reduced) {
        scroll = 0f
        pulse = 0.5f
    } else {
        val tr = rememberInfiniteTransition(label = "map")
        scroll = tr.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(if (moving) 5200 else 16000), RepeatMode.Restart),
            label = "scroll"
        ).value
        pulse = tr.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1800), RepeatMode.Reverse),
            label = "pulse"
        ).value
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF071019), Color(0xFF04070E))))
    ) {
        val w = size.width
        val h = size.height

        val cell = 96f
        val offset = scroll * cell
        var x = -cell + offset
        while (x < w + cell) {
            drawLine(accent.copy(alpha = 0.055f), Offset(x, 0f), Offset(x, h), 1f)
            x += cell
        }
        var y = -cell + offset
        while (y < h + cell) {
            drawLine(accent.copy(alpha = 0.055f), Offset(0f, y), Offset(w, y), 1f)
            y += cell
        }

        drawLine(
            accent.copy(alpha = 0.13f),
            Offset(0f, h * 0.34f), Offset(w, h * 0.30f), 14f
        )
        drawLine(
            accent.copy(alpha = 0.10f),
            Offset(w * 0.72f, 0f), Offset(w * 0.64f, h), 11f
        )

        val route = Path().apply {
            moveTo(w * 0.5f, h + 40f)
            cubicTo(w * 0.5f, h * 0.72f, w * 0.30f, h * 0.60f, w * 0.33f, h * 0.40f)
            cubicTo(w * 0.36f, h * 0.22f, w * 0.62f, h * 0.22f, w * 0.70f, h * 0.06f)
        }
        drawPath(
            route,
            brush = Brush.verticalGradient(listOf(accent2, accent)),
            style = Stroke(width = 13f, cap = StrokeCap.Round)
        )
        drawPath(
            route,
            color = Color.White.copy(alpha = 0.28f),
            style = Stroke(width = 4f, cap = StrokeCap.Round)
        )

        val cx = w * 0.5f
        val cy = h * 0.78f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.32f * (0.5f + pulse * 0.5f)), Color.Transparent),
                center = Offset(cx, cy), radius = 78f
            ),
            radius = 78f, center = Offset(cx, cy)
        )
        val car = Path().apply {
            moveTo(cx, cy - 20f)
            lineTo(cx + 14f, cy + 16f)
            lineTo(cx, cy + 8f)
            lineTo(cx - 14f, cy + 16f)
            close()
        }
        drawPath(car, color = accent)
        drawPath(car, color = Color.White.copy(alpha = 0.5f), style = Stroke(1.5f))

        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xCC04070E),
                0.28f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to Color(0xDD04070E)
            )
        )

        val scanY = h * ((scroll * 1.3f) % 1f)
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.16f), Color.Transparent)
            ),
            start = Offset(0f, scanY), end = Offset(w, scanY), strokeWidth = 2f
        )
    }
}
