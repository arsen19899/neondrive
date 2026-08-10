package com.neondrive.launcher.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.ui.theme.LocalNeon
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import com.neondrive.launcher.ui.theme.neonPanel

/**
 * Единый сигнал «компактный интерфейс» — портретный экран или карта, поднятая
 * «во фрейме» (часть экрана занята плавающим окном навигатора, полезная полоса
 * узкая). Вычисляется один раз в [com.neondrive.launcher.ui.NeonRoot] по
 * фактической ориентации экрана и состоянию [com.neondrive.launcher.nav.MapFrameController],
 * а не угадывается на месте по измеренной ширине — так поведение предсказуемо
 * совпадает с той же логикой, что уже использует рабочий стол (HomeScreen),
 * и не переключается случайно на широких, но чуть более узких ландшафтных
 * экранах.
 */
val LocalCompactUi = compositionLocalOf { false }

/** Заголовок-«трафарет» в стиле HUD. */
@Composable
fun HudLabel(text: String, color: Color = LocalNeon.current.accent, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color.copy(alpha = 0.8f),
        modifier = modifier
    )
}

/** Базовая карточка-панель. */
@Composable
fun NeonCard(
    modifier: Modifier = Modifier,
    accent: Color = LocalNeon.current.accent,
    radius: Dp = 22.dp,
    glow: Boolean = true,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Box(
        modifier
            .then(if (glow) Modifier.neonGlow(accent, radius, 0.16f, 14.dp) else Modifier)
            .neonPanel(accent, radius)
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Квадратная неоновая кнопка дока — «плитка» в духе CarPlay. */
@Composable
fun DockButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    size: Dp = 62.dp,
    onClick: () -> Unit
) {
    val glow by animateFloatAsState(
        targetValue = if (selected) 0.45f else 0.14f,
        animationSpec = tween(220), label = "dockGlow"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) accent else Neon.TextMid,
        animationSpec = tween(220), label = "dockTint"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .neonGlow(accent, 20.dp, glow, 12.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF101A2E).copy(alpha = 0.95f),
                            Color(0xFF0A0F1A).copy(alpha = 0.95f)
                        )
                    ),
                    RoundedCornerShape(20.dp)
                )
                .border(
                    1.dp,
                    accent.copy(alpha = if (selected) 0.85f else 0.28f),
                    RoundedCornerShape(20.dp)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.44f))
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            fontSize = 9.sp,
            color = if (selected) accent.copy(alpha = 0.9f) else Neon.TextLow,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Переключатель «вкл/выкл» в неоновой стилистике. */
@Composable
fun NeonToggle(
    checked: Boolean,
    accent: Color = LocalNeon.current.accent,
    onChange: (Boolean) -> Unit
) {
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(200), label = "toggle")
    Box(
        Modifier
            .width(56.dp)
            .height(30.dp)
            .neonGlow(accent, 15.dp, 0.10f + 0.22f * t, 8.dp)
            .background(
                if (checked) accent.copy(alpha = 0.22f) else Color(0xFF121A2B),
                RoundedCornerShape(15.dp)
            )
            .border(
                1.dp,
                if (checked) accent.copy(alpha = 0.85f) else Neon.TextLow.copy(alpha = 0.4f),
                RoundedCornerShape(15.dp)
            )
            .clickable { onChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(start = 4.dp + 26.dp * t)
                .size(22.dp)
                .background(if (checked) accent else Neon.TextLow, RoundedCornerShape(11.dp))
        )
    }
}

/**
 * Строка настройки: заголовок, пояснение и любой контрол.
 *
 * Раскладка решает, ставить ли контрол справа от текста в одну строку или под
 * текстом отдельной строкой, по [LocalCompactUi] — тому же сигналу «портрет
 * или карта во фрейме», что и весь остальной интерфейс настроек. На обычном
 * ландшафтном экране без фрейма раскладка ровно та, что была изначально:
 * контрол в той же строке, что заголовок.
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    accent: Color = LocalNeon.current.accent,
    control: @Composable () -> Unit
) {
    if (LocalCompactUi.current) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            Text(title, color = Neon.TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = Neon.TextLow, fontSize = 12.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(10.dp))
            control()
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = Neon.TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(subtitle, color = Neon.TextLow, fontSize = 12.sp, lineHeight = 15.sp)
                }
            }
            control()
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(accent.copy(alpha = 0.08f))
    )
}

/** Слайдер с неоновой заливкой. */
@Composable
fun NeonSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color = LocalNeon.current.accent,
    steps: Int = 0,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = accent.copy(alpha = 0.18f),
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent
        )
    )
}

/** Сегментированный выбор из нескольких вариантов. */
@Composable
fun <T> NeonSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    accent: Color = LocalNeon.current.accent,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit
) {
    // horizontalScroll — защита от переполнения на узкой ширине (портрет, узкая
    // полоса настроек поверх карты в режиме «Во фрейме»): раньше строка вариантов
    // просто вылезала за границы панели вместо переноса.
    Row(
        modifier
            .background(Color(0xFF0C1424), RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { opt ->
            val isSel = opt == selected
            Box(
                Modifier
                    .background(
                        if (isSel) accent.copy(alpha = 0.20f) else Color.Transparent,
                        RoundedCornerShape(11.dp)
                    )
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    label(opt),
                    color = if (isSel) accent else Neon.TextMid,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

/** Секция настроек с заголовком. */
@Composable
fun SettingsSection(
    title: String,
    accent: Color = LocalNeon.current.accent,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        HudLabel(title, accent, Modifier.padding(bottom = 8.dp, start = 4.dp))
        NeonCard(accent = accent, padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            content()
        }
    }
}
