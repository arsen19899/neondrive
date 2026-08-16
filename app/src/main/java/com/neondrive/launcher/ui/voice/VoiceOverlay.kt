package com.neondrive.launcher.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import com.neondrive.launcher.ui.theme.neonPanel
import com.neondrive.launcher.voice.VoiceAssistant
import com.neondrive.launcher.voice.VoicePhase
import com.neondrive.launcher.voice.VoiceTrigger
import com.neondrive.launcher.voice.WakeWord

/**
 * Плашка голосового ассистента — поверх любого экрана оболочки.
 *
 * ## Почему сверху, а не по центру
 *
 * По центру она перекрывала бы карту ровно там, где на ней машина. Сверху — над
 * карточкой манёвра, в единственной части экрана, где ничего важного нет ни в
 * одной из раскладок.
 *
 * ## Почему видно, что именно услышала оболочка
 *
 * Распознавание ошибается, и когда команда не сработала, единственный
 * практический вопрос — «меня не расслышали или не поняли». Показанный текст
 * отвечает на него сразу: увидев «поехали на ленинский», человек в следующий
 * раз скажет иначе, а не будет считать функцию сломанной.
 *
 * Плашка не перехватывает касания: ассистент — не диалог, он не должен мешать
 * рулить интерфейсом, пока говорит.
 */
@Composable
fun BoxScope.VoiceOverlay(accent: Color, accent2: Color) {
    val state by VoiceAssistant.state.collectAsState()

    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { -it / 2 },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { -it / 2 },
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        val listening = state.phase == VoicePhase.LISTENING
        val tint = if (listening) accent else accent2

        Column(
            Modifier
                .padding(top = 14.dp)
                .widthIn(min = 260.dp, max = 560.dp)
                .neonGlow(tint, 22.dp, 0.18f, 14.dp)
                .neonPanel(tint, radius = 22.dp)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicIndicator(listening = listening, tint = tint)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        when (state.phase) {
                            VoicePhase.LISTENING ->
                                WakeWord.NAME.uppercase() + " СЛУШАЕТ" + triggerMark(state.trigger)
                            VoicePhase.WORKING -> "ВЫПОЛНЯЮ"
                            else -> WakeWord.NAME.uppercase()
                        },
                        color = tint,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Пока человек говорит — показываем гипотезу распознавателя,
                    // после — ответ. Промежуточная гипотеза приглушена: она ещё
                    // может измениться, и выглядеть окончательной не должна.
                    val body = state.reply.ifBlank { state.heard }
                    if (body.isNotBlank()) {
                        Text(
                            body,
                            color = if (state.reply.isBlank()) Neon.TextMid else Neon.TextHi,
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (state.reply.isBlank()) FontWeight.Normal
                            else FontWeight.Medium
                        )
                    } else {
                        Text(
                            "Скажите команду",
                            color = Neon.TextLow,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // Услышанное показываем второй строкой только тогда, когда сверху уже
            // стоит ответ: иначе это была бы та же строка дважды.
            if (state.reply.isNotBlank() && state.heard.isNotBlank()) {
                Text(
                    "услышано: " + state.heard,
                    color = Neon.TextLow,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 52.dp)
                )
            }

            if (state.error.isNotBlank()) {
                Text(
                    state.error,
                    color = Neon.Amber,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp, start = 52.dp)
                )
            }
        }
    }
}

/**
 * Чем начат сеанс — приписка к заголовку плашки.
 *
 * Нужна ровно для одного вопроса, на который иначе нельзя ответить, сидя за
 * рулём: если оболочка открыла микрофон сама, что именно сработало. «ИМЯ» —
 * распознаватель принял посторонний звук за обращение. «КНОПКА» — руль прислал
 * нажатие, которого не было: у резистивных кнопок это обычное дело при плохом
 * контакте, и лечится оно не голосовым управлением, а допуском АЦП в настройках.
 */
private fun triggerMark(trigger: VoiceTrigger): String = when (trigger) {
    VoiceTrigger.BUTTON -> " · КНОПКА"
    VoiceTrigger.WAKE -> " · ИМЯ"
    VoiceTrigger.DICTATION -> " · ДИКТОВКА"
    VoiceTrigger.NONE -> ""
}

/**
 * Пульсирующий микрофон.
 *
 * Анимация тут не украшение: она единственная показывает, что микрофон
 * действительно открыт. Замерший значок при живом распознавании и живой при
 * мёртвом — самая частая причина, по которой голосовым управлением перестают
 * пользоваться.
 */
@Composable
private fun MicIndicator(listening: Boolean, tint: Color) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val t by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "micPulse"
    )
    val scale = if (listening) 1f + 0.10f * t else 1f

    Box(
        Modifier
            .size(40.dp)
            .scale(scale)
            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (listening) Icons.Rounded.Mic else Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .alpha(if (listening) 0.7f + 0.3f * t else 1f)
        )
    }
}
