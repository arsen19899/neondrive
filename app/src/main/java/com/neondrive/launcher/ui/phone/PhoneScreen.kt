package com.neondrive.launcher.ui.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neondrive.launcher.phone.Contact
import com.neondrive.launcher.phone.ContactsRepository
import com.neondrive.launcher.ui.common.HudLabel
import com.neondrive.launcher.ui.common.NeonCard
import com.neondrive.launcher.ui.common.NeonScreenScaffold
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonGlow

/**
 * Телефон: номеронабиратель слева, телефонная книга справа.
 *
 * Контакты берутся из системной базы — на магнитоле они обычно прилетают
 * с телефона по Bluetooth-профилю PBAP и попадают туда же. Поиск работает
 * и по имени, и по цифрам номера: на ходу набрать три цифры быстрее, чем
 * целиться в буквы.
 */
@Composable
fun PhoneScreen(accent: Color, accent2: Color, onBack: () -> Unit) {
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var granted by remember { mutableStateOf(ContactsRepository.hasPermission(context)) }
    val btName = remember { pairedPhoneName(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    LaunchedEffect(granted) {
        if (granted) contacts = ContactsRepository.load(context)
    }

    // Цифры из номеронабирателя тоже фильтруют книгу
    val effectiveQuery = if (query.isNotBlank()) query else number
    val shown = remember(contacts, effectiveQuery) {
        ContactsRepository.filter(contacts, effectiveQuery)
    }

    NeonScreenScaffold(
        title = "Телефон",
        subtitle = btName?.let { "Подключено: $it" } ?: "Bluetooth-телефон не подключён",
        accent = accent,
        onBack = onBack
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

            /* ─── Номеронабиратель ─── */
            NeonCard(
                modifier = Modifier
                    .width(320.dp)
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
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        number.ifEmpty { "—" },
                        color = if (number.isEmpty()) Neon.TextLow else Neon.TextHi,
                        fontSize = 24.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))

                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("*", "0", "#")
                ).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        row.forEach { key ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x330C1424))
                                    .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                    .clickable { number += key },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(key, color = Neon.TextHi, fontSize = 19.sp)
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
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
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x330C1424))
                            .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .clickable { number = number.dropLast(1) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Backspace, "Стереть", tint = Neon.TextMid)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Bluetooth, null,
                        tint = if (btName != null) accent2 else Neon.TextLow,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (btName != null) "Hands-free активен" else "Телефон не подключён",
                        color = Neon.TextLow,
                        fontSize = 12.sp
                    )
                }
            }

            /* ─── Телефонная книга ─── */
            Column(Modifier.weight(1f).fillMaxHeight()) {

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x660C1424))
                            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Search, null,
                                tint = accent.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(
                                        "Поиск по имени или номеру",
                                        color = Neon.TextLow,
                                        fontSize = 14.sp
                                    )
                                }
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Neon.TextHi, fontSize = 14.sp),
                                    cursorBrush = SolidColor(accent),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    IconAction(Icons.Rounded.History, accent2) {
                        open(context, Intent(Intent.ACTION_VIEW).setType("vnd.android.cursor.dir/calls"))
                    }
                }

                Spacer(Modifier.height(12.dp))

                when {
                    !granted -> PermissionCard(accent2) {
                        permissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                    }

                    contacts.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Телефонная книга пуста.\n" +
                                "Разрешите передачу контактов на телефоне при подключении по Bluetooth.",
                            color = Neon.TextLow,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }

                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(shown, key = { it.id.toString() + it.digits }) { contact ->
                            ContactRow(contact, accent, accent2) { dial(context, contact.number) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    accent: Color,
    accent2: Color,
    onCall: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x330C1424))
            .clickable(onClick = onCall)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f))
                .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (contact.photoUri != null) {
                AsyncImage(
                    model = contact.photoUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(42.dp).clip(CircleShape)
                )
            } else {
                Text(
                    contact.initials,
                    color = accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact.name,
                    color = Neon.TextHi,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (contact.starred) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Star, null,
                        tint = accent2, modifier = Modifier.size(13.dp)
                    )
                }
            }
            Text(
                contact.number,
                color = Neon.TextLow,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Neon.Lime.copy(alpha = 0.14f))
                .border(1.dp, Neon.Lime.copy(alpha = 0.5f), CircleShape)
                .clickable(onClick = onCall),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Call, "Позвонить", tint = Neon.Lime, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PermissionCard(color: Color, onGrant: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .neonGlow(color, 16.dp, 0.2f, 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onGrant)
            .padding(20.dp)
    ) {
        HudLabel("Нужен доступ к контактам", color)
        Spacer(Modifier.height(8.dp))
        Text(
            "Оболочка покажет телефонную книгу головного устройства — ту самую, " +
                "которая приходит с телефона по Bluetooth. Нажмите, чтобы разрешить.",
            color = Neon.TextMid,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x660C1424))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    }
}

/**
 * Звонок уходит напрямую (ACTION_CALL), минуя экран набора номера системного
 * «Телефона» — раньше ACTION_DIAL просто подставлял цифры и ждал, пока пользователь
 * ткнёт в чужом интерфейсе. Экран самого разговора всё равно рисует системный
 * Telecom (это епархия приложения по умолчанию), но переход в чужой UI для набора
 * больше не происходит. Без разрешения CALL_PHONE откатываемся на старое поведение,
 * чтобы не упасть с SecurityException.
 */
private fun dial(context: Context, number: String) {
    if (number.isBlank()) return
    val canCallDirectly = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.CALL_PHONE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val action = if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL
    runCatching {
        context.startActivity(
            Intent(action, Uri.parse("tel:${Uri.encode(number)}"))
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
    if (adapter.getProfileConnectionState(BluetoothProfile.HEADSET) !=
        BluetoothProfile.STATE_CONNECTED
    ) return null
    adapter.bondedDevices?.firstOrNull()?.name
}.getOrNull()
