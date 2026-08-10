package com.neondrive.launcher.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PauseCircleFilled
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.phone.ContactsRepository
import com.neondrive.launcher.phone.NeonCallInfo
import com.neondrive.launcher.phone.NeonCallState
import com.neondrive.launcher.phone.NeonInCallService
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow
import com.neondrive.launcher.ui.theme.neonPanel
import kotlinx.coroutines.delay

/**
 * Собственный экран разговора — всплывает поверх любого экрана оболочки, пока
 * идёт звонок ([NeonInCallService.current]), и полностью заменяет системный
 * интерфейс «Телефона»: вызов инициируется, принимается, ставится на
 * удержание и завершается прямо здесь, без перехода в чужое приложение.
 *
 * Место включения — в самом верху дерева композиции (см. [com.neondrive.launcher.ui.NeonRoot]),
 * чтобы оверлей был виден и поверх настроек, и поверх рабочего стола, и поверх
 * любого другого экрана оболочки.
 */
@Composable
fun CallOverlay(accent: Color, accent2: Color) {
    val call by NeonInCallService.current.collectAsState()
    if (call.isEmpty) return

    var showKeypad by remember { mutableStateOf(false) }
    // Клавиатура имеет смысл только пока разговор идёт — как только звонок
    // перестал быть активным/на удержании, прячем её сама.
    LaunchedEffect(call.state) {
        if (call.state != NeonCallState.ACTIVE && call.state != NeonCallState.HOLDING) {
            showKeypad = false
        }
    }

    // «Завершён» держим на экране 1.6с, чтобы разговор не пропадал миганием,
    // затем сами очищаем состояние.
    LaunchedEffect(call.state) {
        if (call.state == NeonCallState.DISCONNECTED) {
            delay(1600)
            NeonInCallService.clearIfDisconnected()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xEE05070C)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .wrapContentSize()
                .padding(24.dp)
                .neonGlow(accent, 28.dp, 0.25f, 20.dp)
                .neonPanel(accent, radius = 28.dp)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CallerIdentity(call, accent, accent2)
            Spacer(Modifier.height(28.dp))
            CallStatusLine(call, accent2)
            Spacer(Modifier.height(20.dp))
            if (showKeypad) {
                DtmfKeypad(accent2)
                Spacer(Modifier.height(20.dp))
            }
            CallControls(
                call = call,
                accent = accent,
                accent2 = accent2,
                keypadActive = showKeypad,
                onToggleKeypad = { showKeypad = !showKeypad }
            )
        }
    }
}

/** Тональный набор во время разговора — меню голосовых меню (IVR), банковские боты и т.п. */
@Composable
private fun DtmfKeypad(accent2: Color) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#")
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { digit ->
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0x330C1424))
                            .border(1.dp, accent2.copy(alpha = 0.25f), CircleShape)
                            .clickable {
                                NeonInCallService.playDtmf(digit[0])
                                NeonInCallService.stopDtmf()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(digit, color = Neon.TextHi, fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CallerIdentity(call: NeonCallInfo, accent: Color, accent2: Color) {
    val context = LocalContext.current
    var resolvedName by remember(call.number) { mutableStateOf<String?>(null) }
    LaunchedEffect(call.number) {
        resolvedName = ContactsRepository.nameForNumber(context, call.number)
    }
    val displayName = resolvedName ?: call.callerDisplayName
    val initials = displayName
        ?.split(' ', '-')
        ?.filter { it.isNotBlank() }
        ?.take(2)
        ?.joinToString("") { it.first().uppercase() }
        ?.ifBlank { null }

    Box(
        Modifier
            .size(96.dp)
            .neonGlow(accent2, 48.dp, 0.3f, 14.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials ?: "?",
            color = accent,
            fontSize = 32.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        displayName ?: call.number.ifBlank { "Неизвестный номер" },
        color = Neon.TextHi,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center
    )
    if (displayName != null && call.number.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            call.number,
            color = Neon.TextLow,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CallStatusLine(call: NeonCallInfo, accent2: Color) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.state, call.activeSinceMs) {
        while (call.state == NeonCallState.ACTIVE || call.state == NeonCallState.HOLDING) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    val text = when (call.state) {
        NeonCallState.RINGING -> if (call.incoming) "Входящий вызов" else "Вызываем…"
        NeonCallState.DIALING -> "Соединение…"
        NeonCallState.ACTIVE -> formatDuration(nowMs - call.activeSinceMs)
        NeonCallState.HOLDING -> "На удержании"
        NeonCallState.DISCONNECTED -> "Вызов завершён"
        NeonCallState.NONE -> ""
    }
    Text(
        text,
        color = accent2,
        fontSize = 15.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp
    )
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}

@Composable
private fun CallControls(
    call: NeonCallInfo,
    accent: Color,
    accent2: Color,
    keypadActive: Boolean,
    onToggleKeypad: () -> Unit
) {
    when (call.state) {
        NeonCallState.RINGING -> {
            if (call.incoming) {
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    RoundActionButton(Icons.Rounded.CallEnd, "Отклонить", Neon.Red) {
                        NeonInCallService.reject()
                    }
                    RoundActionButton(Icons.Rounded.Call, "Ответить", Neon.Lime) {
                        NeonInCallService.answer()
                    }
                }
            } else {
                RoundActionButton(Icons.Rounded.CallEnd, "Отменить", Neon.Red) {
                    NeonInCallService.hangup()
                }
            }
        }

        NeonCallState.DIALING, NeonCallState.ACTIVE, NeonCallState.HOLDING -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ToggleIconButton(
                        icon = if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        label = "Микрофон",
                        active = call.muted,
                        accent = accent2
                    ) { NeonInCallService.toggleMute() }

                    ToggleIconButton(
                        icon = if (call.speakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                        label = "Динамик",
                        active = call.speakerOn,
                        accent = accent2
                    ) { NeonInCallService.toggleSpeaker() }

                    if (call.canHold) {
                        ToggleIconButton(
                            icon = if (call.state == NeonCallState.HOLDING)
                                Icons.Rounded.PlayCircleFilled else Icons.Rounded.PauseCircleFilled,
                            label = "Удержание",
                            active = call.state == NeonCallState.HOLDING,
                            accent = accent2
                        ) { NeonInCallService.toggleHold() }
                    }

                    ToggleIconButton(
                        icon = Icons.Rounded.Dialpad,
                        label = "Клавиатура",
                        active = keypadActive,
                        accent = accent2,
                        onClick = onToggleKeypad
                    )
                }
                Spacer(Modifier.height(28.dp))
                RoundActionButton(Icons.Rounded.CallEnd, "Завершить", Neon.Red) {
                    NeonInCallService.hangup()
                }
            }
        }

        NeonCallState.DISCONNECTED, NeonCallState.NONE -> Unit
    }
}

@Composable
private fun RoundActionButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .neonGlow(color, 32.dp, 0.4f, 14.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.22f))
                .border(1.dp, color.copy(alpha = 0.8f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Neon.TextLow, fontSize = 11.sp)
    }
}

@Composable
private fun ToggleIconButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(if (active) accent.copy(alpha = 0.28f) else Color(0x330C1424))
                .border(1.dp, accent.copy(alpha = if (active) 0.8f else 0.3f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) accent else Neon.TextMid, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Neon.TextLow, fontSize = 10.sp)
    }
}
