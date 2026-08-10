package com.neondrive.launcher.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.ui.theme.Neon
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Часы и дата. Компактный вариант живёт в доке, крупный — на пустом рабочем столе. */
@Composable
fun ClockCard(
    accent: Color,
    use24h: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    // false — в горизонтальном скроллящемся доке (портретный экран), где
    // fillMaxWidth() внутри Modifier.horizontalScroll поймал бы Infinity-ограничение
    // по ширине и уронил бы layout.
    fillWidth: Boolean = true
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    val ru = remember { Locale("ru", "RU") }
    val timeFmt = remember(use24h) { SimpleDateFormat(if (use24h) "HH:mm" else "h:mm", ru) }
    val dateFmt = remember { SimpleDateFormat("d MMMM", ru) }
    val dowFmt = remember { SimpleDateFormat("EEEE", ru) }
    val date = Date(nowMs)

    Column(
        if (fillWidth) modifier.fillMaxWidth() else modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = timeFmt.format(date),
            color = Neon.TextHi,
            fontSize = if (compact) 32.sp else 64.sp,
            fontWeight = FontWeight.Light,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-1).sp
        )
        Text(
            text = dateFmt.format(date).lowercase(ru),
            color = Neon.TextMid,
            fontSize = if (compact) 12.sp else 16.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = dowFmt.format(date).uppercase(ru),
            color = accent.copy(alpha = 0.6f),
            fontSize = if (compact) 9.sp else 12.sp,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
    }
}
