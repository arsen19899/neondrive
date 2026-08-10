package com.neondrive.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* ─────────────────────────  ПАЛИТРА  ───────────────────────── */

object Neon {
    val Void = Color(0xFF04060C)
    val VoidSoft = Color(0xFF0A0F1A)
    val Panel = Color(0xCC0B1220)
    val PanelSolid = Color(0xFF0B1220)
    val Grid = Color(0x2200F0FF)

    val Cyan = Color(0xFF00F0FF)
    val Magenta = Color(0xFFFF2FD0)
    val Violet = Color(0xFF8A5CFF)
    val Lime = Color(0xFFB6FF3C)
    val Amber = Color(0xFFFFB300)
    val Red = Color(0xFFFF3B5C)

    val TextHi = Color(0xFFEAF6FF)
    val TextMid = Color(0xB3EAF6FF)
    val TextLow = Color(0x66EAF6FF)
}

/** Акцент оболочки — меняется в настройках. */
enum class NeonAccent(val label: String, val primary: Color, val secondary: Color) {
    CYAN("Кибер-циан", Neon.Cyan, Neon.Magenta),
    MAGENTA("Розовый неон", Neon.Magenta, Neon.Cyan),
    VIOLET("Ультрафиолет", Neon.Violet, Neon.Cyan),
    LIME("Кислотный", Neon.Lime, Neon.Magenta),
    AMBER("Янтарь", Neon.Amber, Neon.Red);

    companion object {
        fun fromName(n: String?): NeonAccent =
            entries.firstOrNull { it.name == n } ?: CYAN
    }
}

data class NeonPalette(
    val accent: Color,
    val accent2: Color,
    val bg: Color = Neon.Void,
    val panel: Color = Neon.Panel,
    val text: Color = Neon.TextHi,
    val textDim: Color = Neon.TextMid
)

val LocalNeon = staticCompositionLocalOf { NeonPalette(Neon.Cyan, Neon.Magenta) }

/** «Упрощённая графика» из настроек — гасит декоративные бесконечные анимации на слабых ГУ. */
val LocalReducedEffects = staticCompositionLocalOf { false }

/* ─────────────────────────  ТИПОГРАФИКА  ───────────────────────── */

private val NeonTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 88.sp,
        letterSpacing = (-2).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        letterSpacing = 0.5.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = 0.4.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp
    )
)

@Composable
fun NeonDriveTheme(
    accent: NeonAccent = NeonAccent.CYAN,
    reducedEffects: Boolean = false,
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = NeonPalette(accent = accent.primary, accent2 = accent.secondary)

    val scheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = Neon.Void,
        secondary = palette.accent2,
        background = Neon.Void,
        onBackground = Neon.TextHi,
        surface = Neon.PanelSolid,
        onSurface = Neon.TextHi,
        surfaceVariant = Color(0xFF121A2B),
        outline = palette.accent.copy(alpha = 0.35f),
        error = Neon.Red
    )

    CompositionLocalProvider(LocalNeon provides palette, LocalReducedEffects provides reducedEffects) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NeonTypography,
            content = content
        )
    }
}
