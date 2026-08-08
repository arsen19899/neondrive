package com.neondrive.launcher.ui.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.ui.common.HudLabel
import com.neondrive.launcher.ui.common.NeonCard
import com.neondrive.launcher.ui.common.NeonScreenScaffold
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow

/**
 * Телефон. Номеронабиратель оболочки плюс быстрые переходы в системные приложения:
 * штатная «звонилка» головного устройства остаётся источником правды по вызовам.
 */
@Composable
fun PhoneScreen(accent: Color, accent2: Color, onBack: () -> Unit) {
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }
    val btName = remember { pairedPhoneName(context) }

    NeonScreenScaffold(
        title = "Телефон",
        subtitle = btName?.let { "Подключено: $it" } ?: "Bluetooth-телефон не подключён",
        accent = accent,
        onBack = onBack
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

            NeonCard(
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight(),
                accent = accent
            ) {
                HudLabel("Набор номера", accent)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0C1424))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        number.ifEmpty { "—" },
                        color = if (number.isEmpty()) Neon.TextLow else Neon.TextHi,
                        fontSize = 26.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                }
                Spacer(Modifier.height(12.dp))

                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("*", "0", "#")
                )
                rows.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { key ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x330C1424))
                                    .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                    .clickable { number += key },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(key, color = Neon.TextHi, fontSize = 20.sp)
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(52.dp)
                            .neonGlow(Neon.Lime, 14.dp, 0.3f, 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Neon.Lime.copy(alpha = 0.18f))
                            .border(1.dp, Neon.Lime.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                            .clickable { dial(context, number) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Call, "Вызов", tint = Neon.Lime)
                    }
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x330C1424))
                            .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .clickable { number = number.dropLast(1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Backspace, "Стереть", tint = Neon.TextMid)
                    }
                }
            }

            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                NeonCard(accent = accent2, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Bluetooth, null,
                            tint = if (btName != null) accent2 else Neon.TextLow,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                btName ?: "Телефон не подключён",
                                color = Neon.TextHi,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (btName != null) "Hands-free активен"
                                else "Подключите телефон в настройках Bluetooth",
                                color = Neon.TextLow,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                BigAction("Контакты", Icons.Rounded.Contacts, accent, Modifier.fillMaxWidth()) {
                    open(context, Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/")))
                }
                BigAction("Журнал вызовов", Icons.Rounded.History, accent2, Modifier.fillMaxWidth()) {
                    open(context, Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/calls"))
                }
                BigAction("Настройки Bluetooth", Icons.Rounded.Bluetooth, accent, Modifier.fillMaxWidth()) {
                    open(context, Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                }

                Spacer(Modifier.weight(1f))

                Text(
                    "После завершения разговора музыка возвращается автоматически, " +
                        "если в настройках оболочки включено автопроигрывание.",
                    color = Neon.TextLow,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun BigAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x330C1424))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = Neon.TextMid, fontSize = 15.sp)
    }
}

private fun dial(context: Context, number: String) {
    if (number.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@SuppressLint("MissingPermission")
private fun pairedPhoneName(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
        as? android.bluetooth.BluetoothManager
    val adapter: BluetoothAdapter = manager?.adapter ?: return null
    if (!adapter.isEnabled) return null
    val state = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
    if (state != BluetoothProfile.STATE_CONNECTED) return null
    adapter.bondedDevices?.firstOrNull()?.name
}.getOrNull()
