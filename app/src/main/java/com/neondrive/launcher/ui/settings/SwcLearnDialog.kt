package com.neondrive.launcher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.SwcAction
import com.neondrive.launcher.input.SteeringWheelManager
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow

/**
 * Диалог обучения кнопки руля. Пока он открыт, менеджер не выполняет действия,
 * а отдаёт сюда пойманный код клавиши или значение АЦП.
 */
@Composable
fun SwcLearnDialog(
    action: SwcAction,
    settings: LauncherSettings,
    accent: Color,
    accent2: Color,
    edit: SettingsEdit,
    onDismiss: () -> Unit
) {
    var captured by remember { mutableStateOf<SteeringWheelManager.Captured?>(null) }
    var longPress by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        SteeringWheelManager.setLearning(true)
        onDispose { SteeringWheelManager.setLearning(false) }
    }

    LaunchedEffect(Unit) {
        SteeringWheelManager.captured.collect { captured = it }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(460.dp)
                .neonGlow(accent, 22.dp, 0.28f, 16.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF080D18))
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                .padding(24.dp)
        ) {
            Text(
                "ОБУЧЕНИЕ КНОПКИ",
                color = accent,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                action.label,
                color = Neon.TextHi,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(18.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0C1424))
                    .border(1.dp, accent2.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                    .padding(22.dp),
                contentAlignment = Alignment.Center
            ) {
                val c = captured
                if (c == null) {
                    Text(
                        "Нажмите нужную кнопку на руле…",
                        color = Neon.TextLow,
                        fontSize = 15.sp
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            c.label,
                            color = accent2,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (c.fromAdc) "значение АЦП" else "код клавиши ${c.code}",
                            color = Neon.TextLow,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (longPress) accent2.copy(alpha = 0.18f) else Color(0x330C1424)
                        )
                        .border(
                            1.dp,
                            if (longPress) accent2.copy(alpha = 0.7f) else Neon.TextLow.copy(alpha = 0.3f),
                            RoundedCornerShape(11.dp)
                        )
                        .clickable { longPress = !longPress }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        "Долгое нажатие",
                        color = if (longPress) accent2 else Neon.TextMid,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Одну и ту же кнопку можно назначить дважды: коротко и с удержанием.",
                    color = Neon.TextLow,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DialogButton("Отмена", Neon.TextLow, Modifier.weight(1f), onDismiss)
                DialogButton("Очистить", accent, Modifier.weight(1f)) {
                    edit { repo ->
                        repo.setSwcShort(settings.swcShort.filterValues { it != action })
                        repo.setSwcLong(settings.swcLong.filterValues { it != action })
                        repo.setSwcAdcMap(settings.swcAdcMap.filterValues { it != action })
                    }
                    onDismiss()
                }
                DialogButton(
                    "Сохранить",
                    accent2,
                    Modifier.weight(1f),
                    enabled = captured != null
                ) {
                    val c = captured ?: return@DialogButton
                    edit { repo ->
                        if (c.fromAdc) {
                            val map = settings.swcAdcMap
                                .filterValues { it != action }
                                .toMutableMap()
                            map[c.code] = action
                            repo.setSwcAdcMap(map)
                        } else if (longPress) {
                            val map = settings.swcLong.filterValues { it != action }.toMutableMap()
                            map[c.code] = action
                            repo.setSwcLong(map)
                        } else {
                            val map = settings.swcShort.filterValues { it != action }.toMutableMap()
                            map[c.code] = action
                            repo.setSwcShort(map)
                        }
                    }
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(color.copy(alpha = if (enabled) 0.15f else 0.05f))
            .border(
                1.dp,
                color.copy(alpha = if (enabled) 0.6f else 0.2f),
                RoundedCornerShape(13.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) color else Neon.TextLow,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
