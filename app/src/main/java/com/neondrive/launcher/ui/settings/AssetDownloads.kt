package com.neondrive.launcher.ui.settings

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neondrive.launcher.assets.Asset
import com.neondrive.launcher.assets.AssetHub
import com.neondrive.launcher.assets.DownloadPhase
import com.neondrive.launcher.ui.common.SettingRow
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.neonPanel

/**
 * Строка «скачать недостающий файл» и диалог согласия к ней.
 *
 * ## Почему согласие спрашивается всегда
 *
 * Интернет в машине почти всегда мобильный и почти всегда платный, а карта
 * страны весит триста мегабайт. Молча начать такую загрузку — это залезть
 * человеку в кошелёк. Поэтому оболочка не качает ничего сама, никогда: ни
 * «фоновой проверки обновлений», ни «предзагрузки на всякий случай». Она лишь
 * показывает, чего не хватает, и ждёт нажатия.
 *
 * ## А вот дальше — без участия
 *
 * После согласия человек не делает больше ничего: файл скачается (с докачкой
 * после обрыва связи), распакуется, ляжет в нужную папку, и относящаяся к нему
 * настройка включится сама. Экран настроек при этом можно закрыть — работа идёт
 * в фоновом сервисе.
 */
@Composable
fun AssetRow(asset: Asset, accent: Color, accent2: Color) {
    val context = LocalContext.current
    val states by AssetHub.states.collectAsState()
    val state = states[asset.id]

    // Пересчитывается на каждом изменении состояния загрузки — этого хватает,
    // потому что появиться файл может только в результате такой загрузки.
    val installed = remember(state?.phase) { asset.isInstalled(context) }
    var askConsent by remember { mutableStateOf(false) }

    SettingRow(
        title = asset.title,
        subtitle = when {
            state?.phase == DownloadPhase.ERROR ->
                state.error.ifBlank { "Не удалось скачать" }
            state?.active == true ->
                buildString {
                    append(
                        when (state.phase) {
                            DownloadPhase.CONNECTING -> "Соединение…"
                            DownloadPhase.UNPACKING -> "Распаковка…"
                            else -> state.sizeLabel
                        }
                    )
                    if (state.sourceHost.isNotBlank()) append("  ·  ${state.sourceHost}")
                }
            installed -> "Установлено"
            else -> asset.description
        },
        accent = when {
            state?.phase == DownloadPhase.ERROR -> Neon.Amber
            installed -> accent2
            else -> accent
        }
    ) {
        when {
            state?.active == true -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.total > 0L) "${state.percent} %" else "…",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Box(
                        Modifier
                            .padding(start = 10.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Neon.Red.copy(alpha = 0.14f))
                            .border(1.dp, Neon.Red.copy(alpha = 0.5f), RoundedCornerShape(11.dp))
                            .clickable { AssetHub.cancel(asset.id) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close, "Отменить",
                            tint = Neon.Red,
                            modifier = Modifier.padding(0.dp)
                        )
                    }
                }
            }

            installed -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = accent2)
                    // Обновить осмысленно: камеры в OSM появляются постоянно, а
                    // карта раз в несколько месяцев пересобирается заново.
                    AssetButton("Обновить", accent) { askConsent = true }
                }
            }

            else -> AssetButton(
                if (asset.approxMb > 0) "Скачать · ${asset.approxMb} МБ" else "Скачать",
                accent
            ) { askConsent = true }
        }
    }

    // Полоса прогресса отдельной строкой под настройкой: в самой строке ей
    // места нет, а без неё непонятно, идёт ли дело вообще.
    if (state?.active == true) {
        ProgressBar(percent = state.percent, indeterminate = state.total <= 0L, accent = accent)
    }

    if (state?.phase == DownloadPhase.ERROR && asset.manualUrl.isNotBlank()) {
        Text(
            "Если не получается — скачайте вручную: ${asset.manualUrl}",
            color = Neon.TextLow,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 10.dp)
        )
    }

    if (askConsent) {
        AssetConsentDialog(
            asset = asset,
            accent = accent,
            accent2 = accent2,
            onDismiss = { askConsent = false },
            onConfirm = {
                askConsent = false
                AssetHub.request(context, asset)
            }
        )
    }
}

@Composable
private fun AssetButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProgressBar(percent: Int, indeterminate: Boolean, accent: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33FFFFFF))
    ) {
        Box(
            Modifier
                .fillMaxWidth(if (indeterminate) 1f else percent / 100f)
                .height(4.dp)
                .background(accent.copy(alpha = if (indeterminate) 0.35f else 1f))
        )
    }
}

/**
 * Диалог согласия.
 *
 * Показывает три вещи, которых человек иначе не узнает: что именно качается,
 * откуда и сколько это весит. Плюс предупреждение о платном трафике, если
 * система говорит, что подключение тарифицируемое, — в машине это обычное дело,
 * потому что интернет раздаётся с телефона.
 */
@Composable
fun AssetConsentDialog(
    asset: Asset,
    accent: Color,
    accent2: Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    val metered = remember { isMetered(context) }
    val host = remember(asset.id) {
        runCatching { java.net.URL(asset.urls.first()).host }.getOrDefault("")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.78f)
                .neonPanel(accent, radius = 24.dp)
                .padding(22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDownload, null, tint = accent)
                Text(
                    asset.title,
                    color = Neon.TextHi,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            Text(
                asset.description,
                color = Neon.TextMid,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 12.dp)
            )

            Column(Modifier.padding(top = 14.dp)) {
                InfoLine("Размер", "около ${asset.approxMb} МБ", accent2)
                if (host.isNotBlank()) InfoLine("Источник", host, accent2)
                InfoLine("Куда", "во внутреннюю память оболочки", accent2)
            }

            Text(
                if (metered) {
                    "Текущее подключение тарифицируется — скорее всего, это интернет " +
                        "с телефона. ${asset.approxMb} МБ по мобильному трафику могут " +
                        "стоить заметных денег. Лучше подключиться к Wi-Fi."
                } else {
                    "Загрузка идёт в фоне: экран настроек можно закрыть. При обрыве " +
                        "связи скачанное сохранится, и продолжить можно с того же места."
                },
                color = if (metered) Neon.Amber else Neon.TextLow,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 14.dp)
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0x66060B14))
                        .border(1.dp, Neon.TextLow.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Отмена", color = Neon.TextMid, fontSize = 15.sp)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(accent.copy(alpha = 0.2f))
                        .border(1.dp, accent.copy(alpha = 0.75f), RoundedCornerShape(13.dp))
                        .clickable(onClick = onConfirm)
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Скачать",
                        color = accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Neon.TextLow, fontSize = 13.sp, modifier = Modifier.weight(0.35f))
        Text(value, color = color, fontSize = 13.sp, modifier = Modifier.weight(0.65f))
    }
}

/**
 * Тарифицируется ли текущее подключение.
 *
 * `isActiveNetworkMetered` — единственный способ узнать это, не разбирая
 * вручную тип сети: система сама учитывает и мобильный интернет, и Wi-Fi,
 * помеченный пользователем как лимитный. На части прошивок ГУ метод врёт,
 * поэтому предупреждение мягкое и ничего не блокирует.
 */
private fun isMetered(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    cm?.isActiveNetworkMetered ?: false
}.getOrDefault(false)
