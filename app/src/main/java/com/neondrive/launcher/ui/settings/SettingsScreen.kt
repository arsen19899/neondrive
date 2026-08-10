package com.neondrive.launcher.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.automation.NeonNotificationListener
import com.neondrive.launcher.automation.SpeedProvider
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MapMode
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.NotificationReaction
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.data.SidebarSide
import com.neondrive.launcher.data.SpeedUnits
import com.neondrive.launcher.data.SpeedVolumeStep
import com.neondrive.launcher.data.RadioMode
import com.neondrive.launcher.data.SwcAction
import com.neondrive.launcher.media.FmRadioController
import com.neondrive.launcher.media.FmStation
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.nav.MapFrameController
import com.neondrive.launcher.nav.NavigatorBridge
import com.neondrive.launcher.overlay.NeonOverlayService
import com.neondrive.launcher.phone.BluetoothDevicesRepository
import com.neondrive.launcher.phone.PairedBtDevice
import com.neondrive.launcher.system.DefaultLauncherHelper
import com.neondrive.launcher.ui.common.HudLabel
import com.neondrive.launcher.ui.common.LocalCompactUi
import com.neondrive.launcher.ui.common.NeonSegmented
import com.neondrive.launcher.ui.common.NeonScreenScaffold
import com.neondrive.launcher.ui.common.NeonSlider
import com.neondrive.launcher.ui.common.NeonToggle
import com.neondrive.launcher.ui.common.SettingRow
import com.neondrive.launcher.ui.common.SettingsSection
import com.neondrive.launcher.ui.theme.Neon
import com.neondrive.launcher.ui.theme.NeonAccent
import com.neondrive.launcher.ui.theme.neonGlow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

typealias SettingsEdit = (suspend (SettingsRepository) -> Unit) -> Unit

private enum class Tab(val title: String) {
    NAV("Навигация"),
    MUSIC("Музыка"),
    RADIO("Радио"),
    REACTIONS("Реакции"),
    SPEED("Скорость"),
    WHEEL("Кнопки руля"),
    BLUETOOTH("Bluetooth"),
    LOOK("Внешний вид"),
    SYSTEM("Система")
}

@Composable
fun SettingsScreen(
    settings: LauncherSettings,
    accent: Color,
    accent2: Color,
    onBack: () -> Unit,
    edit: SettingsEdit
) {
    var tab by remember { mutableStateOf(Tab.NAV) }

    NeonScreenScaffold(
        title = "Настройки оболочки",
        subtitle = "NeonDrive · конфигурация рабочего стола и автоматики",
        accent = accent,
        onBack = onBack
    ) {
        val content: @Composable () -> Unit = {
            when (tab) {
                Tab.NAV -> NavTab(settings, accent, accent2, edit)
                Tab.MUSIC -> MusicTab(settings, accent, accent2, edit)
                Tab.RADIO -> RadioTab(accent, accent2)
                Tab.REACTIONS -> ReactionsTab(settings, accent, accent2, edit)
                Tab.SPEED -> SpeedTab(settings, accent, accent2, edit)
                Tab.WHEEL -> WheelTab(settings, accent, accent2, edit)
                Tab.BLUETOOTH -> BluetoothTab(settings, accent, accent2, edit)
                Tab.LOOK -> LookTab(settings, accent, accent2, edit)
                Tab.SYSTEM -> SystemTab(settings, accent, accent2, edit)
            }
        }

        // compact — тот же сигнал «портрет или карта во фрейме», что и у всего
        // остального интерфейса (см. LocalCompactUi). На обычном ландшафтном
        // экране без фрейма раскладка не меняется — боковая колонка вкладок
        // шириной 190dp, как и было всегда.
        val compact = LocalCompactUi.current
        if (!compact) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier
                        .width(190.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Tab.entries.forEach { t ->
                        SettingsTabChip(
                            title = t.title,
                            selected = t == tab,
                            accent = accent,
                            fillWidth = true,
                            onClick = { tab = t }
                        )
                    }
                }

                LazyColumn(
                    Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item { content() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Tab.entries.forEach { t ->
                        SettingsTabChip(
                            title = t.title,
                            selected = t == tab,
                            accent = accent,
                            fillWidth = false,
                            onClick = { tab = t }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item { content() }
                }
            }
        }
    }
}

@Composable
private fun SettingsTabChip(
    title: String,
    selected: Boolean,
    accent: Color,
    fillWidth: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(RoundedCornerShape(14.dp))
            .then(if (selected) Modifier.neonGlow(accent, 14.dp, 0.18f, 8.dp) else Modifier)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color(0x220C1424))
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.6f) else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Text(
            title,
            color = if (selected) accent else Neon.TextMid,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/* ═════════════════════  1. МУЗЫКА  ═════════════════════ */

@Composable
private fun MusicTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    Column {
        SettingsSection("Автопроигрывание", accent) {
            SettingRow(
                "Автопроигрывание музыки",
                "Музыка стартует сама при запуске головного устройства и возвращается после вызова",
                accent
            ) {
                NeonToggle(s.autoplay, accent) { v -> edit { it.setAutoplay(v) } }
            }

            if (s.autoplay) {
                SettingRow(
                    "Источник автостарта",
                    "С чего начинать проигрывание после включения",
                    accent
                ) {
                    NeonSegmented(
                        options = MusicSource.entries.toList(),
                        selected = s.autoplaySource,
                        label = { it.label },
                        accent = accent
                    ) { v -> edit { it.setAutoplaySource(v) } }
                }

                SettingRow(
                    "Задержка перед стартом",
                    "${s.autoplayDelaySec} с — пауза, чтобы система успела подняться",
                    accent
                ) {
                    NeonSlider(
                        value = s.autoplayDelaySec.toFloat(),
                        range = 0f..30f,
                        accent = accent,
                        modifier = Modifier.width(220.dp)
                    ) { v -> edit { it.setAutoplayDelay(v.roundToInt()) } }
                }
            }
        }

        SettingsSection("После телефонного вызова", accent2) {
            SettingRow(
                "Возобновлять музыку",
                "Как только разговор завершён, воспроизведение включается само",
                accent2
            ) {
                NeonToggle(s.resumeAfterCall, accent2) { v -> edit { it.setResumeAfterCall(v) } }
            }
            if (s.resumeAfterCall) {
                SettingRow(
                    "Пауза перед возвратом",
                    "${s.resumeAfterCallDelaySec} с после завершения вызова",
                    accent2
                ) {
                    NeonSlider(
                        value = s.resumeAfterCallDelaySec.toFloat(),
                        range = 0f..15f,
                        accent = accent2,
                        modifier = Modifier.width(220.dp)
                    ) { v -> edit { it.setResumeDelay(v.roundToInt()) } }
                }
            }
            if (!s.autoplay) {
                Hint(
                    "Возврат после вызова работает только вместе с включённым автопроигрыванием.",
                    accent2
                )
            }
        }
    }
}

/* ═════════════════════  РАДИО  ═════════════════════ */

@Composable
private fun RadioTab(accent: Color, accent2: Color) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val stations by PlayerHub.stations.collectAsState()
    val results by PlayerHub.stationSearch.collectAsState()
    val searching by PlayerHub.searching.collectAsState()
    val radioMode by PlayerHub.radioMode.collectAsState()

    fun runSearch() {
        scope.launch { PlayerHub.searchStations(query) }
    }

    Column {
        SettingsSection("Источник радио", accent) {
            SettingRow(
                "Интернет-радио или FM",
                "FM работает только если к головному устройству физически подключена антенна " +
                    "и прошивка предоставляет доступ к тюнеру",
                accent
            ) {
                NeonSegmented(
                    options = RadioMode.entries.toList(),
                    selected = radioMode,
                    label = { it.label },
                    accent = accent
                ) { v -> PlayerHub.setRadioMode(v) }
            }
        }

        if (radioMode == RadioMode.FM) {
            FmRadioSection(accent, accent2)
            return@Column
        }

        SettingsSection("Сохранённые станции", accent) {
            if (stations.isEmpty()) {
                Hint("Станций пока нет — найдите их ниже и сохраните.", accent2)
            } else {
                stations.forEach { st ->
                    SettingRow(
                        st.name,
                        listOf(st.genre, if (st.builtIn) "предустановлена" else "своя")
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        accent
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("Играть", accent2) { PlayerHub.playStation(st) }
                            if (!st.builtIn) {
                                SmallButton("Удалить", Neon.Red) { PlayerHub.removeStation(st.id) }
                            }
                        }
                    }
                }
            }
        }

        SettingsSection("Поиск станций", accent2) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x330C1424))
                        .border(1.dp, accent2.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (query.isEmpty()) {
                        Text("Название, жанр, город…", color = Neon.TextLow, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Neon.TextHi, fontSize = 13.sp),
                        cursorBrush = SolidColor(accent2),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(10.dp))
                SmallButton("Найти", accent2) { runSearch() }
            }

            when {
                searching -> Hint("Ищем…", accent2)
                results.isEmpty() && query.isNotBlank() -> Hint(
                    "Ничего не найдено. Проверьте название или интернет-соединение.", accent2
                )
                results.isEmpty() -> Unit
                else -> results.forEach { r ->
                    val saved = stations.any { it.streamUrl == r.streamUrl }
                    SettingRow(
                        r.name,
                        listOf(r.genre, r.country).filter { it.isNotBlank() }.joinToString(" · "),
                        accent
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton("Играть", accent2) { PlayerHub.saveAndPlayStation(r) }
                            SmallButton(
                                if (saved) "Сохранено" else "Сохранить",
                                if (saved) Neon.TextLow else accent2
                            ) { if (!saved) PlayerHub.saveStation(r) }
                        }
                    }
                }
            }
        }

        Hint(
            "Каталог станций — открытый сервис radio-browser.info, ключ не нужен. " +
                "Сохранённые станции переживают перезапуск оболочки и листаются кнопками " +
                "⏮/⏭ прямо в мини-плеере на рабочем столе.",
            accent2
        )
    }
}

/**
 * Обычное FM-радио по антенне ГУ.
 *
 * Важное честное ограничение: Android не даёт стороннему приложению (без прав
 * системного/привилегированного) программно управлять тюнером — это защищено на
 * уровне разрешений самой системы, и обхода для этого не существует. Поэтому раздел
 * работает как шпаргалка станций «частота + название» (настроиться нужно кнопками
 * самой магнитолы) плюс быстрый переход в заводское радио-приложение ГУ, если оно
 * на устройстве есть — это единственное управление, которое действительно доступно.
 */
@Composable
private fun FmRadioSection(accent: Color, accent2: Color) {
    val context = LocalContext.current
    val fmState by FmRadioController.state.collectAsState()
    val fmStations by PlayerHub.fmStations.collectAsState()
    var freqText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }

    LaunchedEffectOnce { FmRadioController.init(context) }

    SettingsSection("Заводское радио-приложение", accent) {
        SettingRow(
            "Приложение радио на этом ГУ",
            if (fmState.factoryAppFound) "Найдено (${fmState.factoryAppPackage}) — можно открыть напрямую"
            else "Не найдено среди распространённых пакетов. Настройтесь на нужную частоту " +
                "штатными кнопками магнитолы",
            if (fmState.factoryAppFound) accent else Neon.Amber
        ) {
            if (fmState.factoryAppFound) {
                SmallButton("Открыть", accent2) { PlayerHub.openFactoryRadioApp() }
            }
        }
    }

    SettingsSection("Сохранённые FM-станции", accent) {
        if (fmStations.isEmpty()) {
            Hint("Станций нет — добавьте частоту вручную ниже, для памяти.", accent2)
        } else {
            fmStations.forEach { st ->
                SettingRow(st.label, "%.1f МГц".format(st.mhz), accent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("Выбрать", accent2) { PlayerHub.playFmStation(st) }
                        SmallButton("Удалить", Neon.Red) { PlayerHub.removeFmStation(st.frequencyKHz) }
                    }
                }
            }
        }
    }

    SettingsSection("Добавить вручную", accent2) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FmField(freqText, { freqText = it }, "101.7", accent2, Modifier.width(90.dp))
            Spacer(Modifier.width(8.dp))
            Text("МГц", color = Neon.TextLow, fontSize = 12.sp)
            Spacer(Modifier.width(14.dp))
            FmField(nameText, { nameText = it }, "Название (необязательно)", accent2, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            SmallButton("Добавить", accent2) {
                val mhz = freqText.replace(",", ".").toFloatOrNull()
                if (mhz != null && mhz in 65f..108f) {
                    PlayerHub.addFmStation(
                        FmStation((mhz * 1000).roundToInt(), nameText.trim())
                    )
                    freqText = ""; nameText = ""
                }
            }
        }
    }

    Hint(
        "Android не позволяет обычному приложению без системных прав управлять FM-тюнером " +
            "напрямую — это ограничение платформы, которое нельзя обойти программно. Список " +
            "станций здесь — памятка с частотами; переключает их сама магнитола (штатными " +
            "кнопками или заводским радио-приложением выше, если оно нашлось).",
        accent2
    )
}

@Composable
private fun FmField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x330C1424))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Neon.TextLow, fontSize = 13.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Neon.TextHi, fontSize = 13.sp),
            cursorBrush = SolidColor(accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Мини-замена LaunchedEffect(Unit) без лишнего импорта на уровне файла. */
@Composable
private fun LaunchedEffectOnce(block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

/* ═════════════════════  2. РЕАКЦИИ НА УВЕДОМЛЕНИЯ  ═════════════════════ */

@Composable
private fun ReactionsTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    val context = LocalContext.current
    // Не кэшируем: пользователь мог выдать доступ и вернуться в этот экран
    val granted = NeonNotificationListener.isEnabled(context)

    Column {
        if (!granted) {
            WarningCard(
                "Нужен доступ к уведомлениям",
                "Без него оболочка не увидит уведомления телефона и не сможет управлять " +
                    "Яндекс.Музыкой. Откройте системный список и включите NeonDrive.",
                accent2
            ) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        SettingsSection("Уведомления с подключённого телефона", accent) {
            SettingRow(
                "Реакция музыки",
                s.notificationReaction.hint,
                accent
            ) {
                NeonSegmented(
                    options = NotificationReaction.entries.toList(),
                    selected = s.notificationReaction,
                    label = { it.label },
                    accent = accent
                ) { v -> edit { it.setNotificationReaction(v) } }
            }

            if (s.notificationReaction == NotificationReaction.DUCK) {
                SettingRow(
                    "Уровень приглушения",
                    "Музыка опускается до ${s.duckPercent}% от текущей громкости",
                    accent
                ) {
                    NeonSlider(
                        value = s.duckPercent.toFloat(),
                        range = 5f..90f,
                        accent = accent,
                        modifier = Modifier.width(220.dp)
                    ) { v -> edit { it.setDuckPercent(v.roundToInt()) } }
                }
            }

            if (s.notificationReaction != NotificationReaction.IGNORE) {
                SettingRow(
                    "Держать реакцию",
                    "${s.duckHoldMs / 1000f} с — потом громкость (или воспроизведение) возвращается",
                    accent
                ) {
                    NeonSlider(
                        value = s.duckHoldMs.toFloat(),
                        range = 500f..15000f,
                        accent = accent,
                        modifier = Modifier.width(220.dp)
                    ) { v -> edit { it.setDuckHold(v.roundToInt()) } }
                }

                SettingRow(
                    "Только уведомления телефона",
                    if (s.onlyPairedDeviceNotifications)
                        "Реагируем только на то, что пришло по Bluetooth с телефона"
                    else "Реагируем на уведомления любых приложений головного устройства",
                    accent
                ) {
                    NeonToggle(s.onlyPairedDeviceNotifications, accent) { v ->
                        edit { it.setOnlyPaired(v) }
                    }
                }
            }
        }

        Hint(
            "Пауза удобна для голосовых сообщений и навигационных подсказок, " +
                "приглушение — для коротких мессенджеров: трек не прерывается.",
            accent2
        )
    }
}

/* ═════════════════════  3. ГРОМКОСТЬ ОТ СКОРОСТИ  ═════════════════════ */

@Composable
private fun SpeedTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    Column {
        SettingsSection("Громкость от скорости", accent) {
            SettingRow(
                "Увеличение громкости при скорости",
                "Чем быстрее едем — тем громче музыка. Четыре ступени настраиваются ниже",
                accent
            ) {
                NeonToggle(s.speedVolumeEnabled, accent) { v ->
                    edit { it.setSpeedVolumeEnabled(v) }
                }
            }
            if (s.speedVolumeEnabled) {
                SettingRow(
                    "Плавность перехода",
                    "${s.speedVolumeSmoothMs} мс на шаг — чем больше, тем мягче",
                    accent
                ) {
                    NeonSlider(
                        value = s.speedVolumeSmoothMs.toFloat(),
                        range = 200f..6000f,
                        accent = accent,
                        modifier = Modifier.width(220.dp)
                    ) { v -> edit { it.setSpeedSmooth(v.roundToInt()) } }
                }
            }
        }

        if (s.speedVolumeEnabled) {
            val steps = s.speedSteps.sortedBy { it.fromKmh }
            SettingsSection("Четыре ступени", accent2) {
                steps.forEachIndexed { index, step ->
                    StepEditor(
                        index = index,
                        step = step,
                        accent = accent,
                        accent2 = accent2,
                        onChange = { updated ->
                            val list = steps.toMutableList()
                            list[index] = updated
                            edit { it.setSpeedSteps(list) }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton("Сбросить", accent) {
                        edit { it.setSpeedSteps(com.neondrive.launcher.data.Defaults.speedSteps) }
                    }
                    SmallButton("Тихий город", accent2) {
                        edit {
                            it.setSpeedSteps(
                                listOf(
                                    SpeedVolumeStep(0, 0),
                                    SpeedVolumeStep(50, 4),
                                    SpeedVolumeStep(80, 9),
                                    SpeedVolumeStep(110, 15)
                                )
                            )
                        }
                    }
                    SmallButton("Трасса", accent) {
                        edit {
                            it.setSpeedSteps(
                                listOf(
                                    SpeedVolumeStep(0, 0),
                                    SpeedVolumeStep(70, 8),
                                    SpeedVolumeStep(100, 16),
                                    SpeedVolumeStep(130, 26)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepEditor(
    index: Int,
    step: SpeedVolumeStep,
    accent: Color,
    accent2: Color,
    onChange: (SpeedVolumeStep) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent2.copy(alpha = 0.18f))
                    .border(1.dp, accent2.copy(alpha = 0.5f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    color = accent2,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "от ${step.fromKmh} км/ч",
                color = Neon.TextHi,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (step.gain > 0) "+${step.gain}%" else "базовая",
                color = if (step.gain > 0) accent2 else Neon.TextLow,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Скорость", color = Neon.TextLow, fontSize = 11.sp, modifier = Modifier.width(74.dp))
            NeonSlider(
                value = step.fromKmh.toFloat(),
                range = 0f..180f,
                accent = accent,
                modifier = Modifier.weight(1f)
            ) { v -> onChange(step.copy(fromKmh = v.roundToInt())) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Прибавка", color = Neon.TextLow, fontSize = 11.sp, modifier = Modifier.width(74.dp))
            NeonSlider(
                value = step.gain.toFloat(),
                range = 0f..40f,
                accent = accent2,
                modifier = Modifier.weight(1f)
            ) { v -> onChange(step.copy(gain = v.roundToInt())) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(accent.copy(alpha = 0.08f))
        )
    }
}

/* ═════════════════════  4. КНОПКИ РУЛЯ  ═════════════════════ */

@Composable
private fun WheelTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    var learnTarget by remember { mutableStateOf<SwcAction?>(null) }

    Column {
        SettingsSection("Кнопки на руле", accent) {
            SettingRow(
                "Обрабатывать кнопки руля",
                "Оболочка перехватывает нажатия и выполняет назначенные действия",
                accent
            ) {
                NeonToggle(s.swcEnabled, accent) { v -> edit { it.setSwcEnabled(v) } }
            }
            SettingRow(
                "Порог долгого нажатия",
                "${s.swcLongPressMs} мс",
                accent
            ) {
                NeonSlider(
                    value = s.swcLongPressMs.toFloat(),
                    range = 250f..2000f,
                    accent = accent,
                    modifier = Modifier.width(220.dp)
                ) { v -> edit { it.setSwcLongMs(v.roundToInt()) } }
            }
        }

        SettingsSection("Резистивный руль (АЦП)", accent2) {
            SettingRow(
                "Читать значения АЦП напрямую",
                "Включайте, если ядро не превращает нажатия в обычные клавиши",
                accent2
            ) {
                NeonToggle(s.swcAdcEnabled, accent2) { v -> edit { it.setSwcAdcEnabled(v) } }
            }
            if (s.swcAdcEnabled) {
                SettingRow(
                    "Путь к ноде",
                    s.swcAdcPath,
                    accent2
                ) {
                    NeonSegmented(
                        options = KNOWN_ADC_PATHS,
                        selected = s.swcAdcPath.takeIf { it in KNOWN_ADC_PATHS } ?: KNOWN_ADC_PATHS.first(),
                        label = { it.substringAfterLast('/') },
                        accent = accent2
                    ) { v -> edit { it.setSwcAdcPath(v) } }
                }
                SettingRow(
                    "Допуск разброса",
                    "±${s.swcAdcTolerance} единиц АЦП считается той же кнопкой",
                    accent2
                ) {
                    NeonSlider(
                        value = s.swcAdcTolerance.toFloat(),
                        range = 1f..120f,
                        accent = accent2,
                        modifier = Modifier.width(220.dp)
                    ) { v -> edit { it.setSwcAdcTolerance(v.roundToInt()) } }
                }
            }
        }

        SettingsSection("Назначения", accent) {
            SwcAction.entries.filter { it != SwcAction.NONE }.forEach { action ->
                val keyCode = s.swcShort.entries.firstOrNull { it.value == action }?.key
                val adcValue = s.swcAdcMap.entries.firstOrNull { it.value == action }?.key
                SettingRow(
                    action.label,
                    when {
                        adcValue != null && s.swcAdcEnabled -> "АЦП $adcValue"
                        keyCode != null -> android.view.KeyEvent.keyCodeToString(keyCode)
                        else -> "не назначено"
                    },
                    accent
                ) {
                    SmallButton("Обучить", accent2) { learnTarget = action }
                }
            }
        }

        Hint(
            "Обучение: нажмите «Обучить», затем — кнопку на руле. Оболочка запомнит код " +
                "клавиши или значение АЦП. Долгие нажатия настраиваются тем же способом, " +
                "если удерживать кнопку дольше порога.",
            accent2
        )
    }

    learnTarget?.let { action ->
        SwcLearnDialog(
            action = action,
            settings = s,
            accent = accent,
            accent2 = accent2,
            edit = edit,
            onDismiss = { learnTarget = null }
        )
    }
}

private val KNOWN_ADC_PATHS = listOf(
    "/sys/class/adc_key/value",
    "/sys/devices/platform/rk3x-i2c.1/adc_key/value",
    "/sys/class/switch/swc/state",
    "/dev/swc_adc"
)

/* ═════════════════════  BLUETOOTH  ═════════════════════ */

/**
 * Список сопряжённых Bluetooth-устройств и выбор, какое из них считать
 * «телефоном» — оболочка использует это для подписей на экране «Телефон» и как
 * более надёжную замену прежней эвристике «первое сопряжённое устройство».
 *
 * Самого подключения здесь нет и быть не может: Android не даёт стороннему
 * приложению без системных прав программно подключить уже сопряжённое
 * Bluetooth-устройство — это ограничение платформы, не оболочки (см. пояснение
 * ниже и комментарий в BluetoothDevicesRepository).
 */
@Composable
private fun BluetoothTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    val context = LocalContext.current
    val granted = BluetoothDevicesRepository.hasPermission(context)
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<PairedBtDevice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    suspend fun refresh() {
        loading = true
        devices = BluetoothDevicesRepository.list(context)
        loading = false
    }

    LaunchedEffect(granted) {
        if (granted) refresh() else loading = false
    }

    Column {
        if (!granted) {
            WarningCard(
                "Нужен доступ к Bluetooth",
                "Без разрешения «Устройства поблизости» оболочка не увидит список " +
                    "сопряжённых устройств. Откройте разрешения приложения и выдайте его.",
                accent2
            ) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        SettingsSection("Устройство-телефон", accent) {
            SettingRow(
                "Список сопряжённых устройств",
                "Отметьте то, что считать телефоном — от этого зависят подписи в «Телефоне» " +
                    "и распознавание уведомлений с телефона во вкладке «Реакции»",
                accent
            ) {
                SmallButton(if (loading) "Ищем…" else "Обновить", accent2) {
                    scope.launch { refresh() }
                }
            }

            when {
                loading -> Hint("Ищем сопряжённые устройства…", accent2)
                devices.isEmpty() -> Hint(
                    "Сопряжённых устройств не найдено. Сопрягите телефон в системных " +
                        "настройках Bluetooth головного устройства, затем нажмите «Обновить».",
                    accent2
                )
                else -> devices.forEach { d ->
                    BtDeviceRow(
                        device = d,
                        selected = d.address == s.phoneBluetoothAddress,
                        accent = accent,
                        accent2 = accent2
                    ) {
                        edit { it.setPhoneBluetoothAddress(d.address) }
                    }
                }
            }

            if (s.phoneBluetoothAddress.isNotBlank() && devices.none { it.address == s.phoneBluetoothAddress }) {
                Hint(
                    "Выбранное устройство сейчас не видно среди сопряжённых — " +
                        "проверьте, не отвязали ли его на телефоне.",
                    Neon.Amber
                )
            }
        }

        SettingsSection("Подключение", accent2) {
            SettingRow(
                "Управлять подключением здесь нельзя",
                "Android не даёт стороннему приложению без системных прав самому подключать " +
                    "или переподключать уже сопряжённое Bluetooth-устройство — это ограничение " +
                    "платформы. Нажмите, чтобы открыть системные настройки Bluetooth",
                accent2
            ) {
                SmallButton("Открыть", accent2) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }

        Hint(
            "Hands-free (HFP) — профиль для звонков, Медиа (A2DP) — для музыки. Собственный " +
                "экран звонка оболочки работает независимо от выбора здесь — этот выбор влияет " +
                "только на подписи и на то, какие уведомления считаются «с телефона».",
            accent
        )
    }
}

@Composable
private fun BtDeviceRow(
    device: PairedBtDevice,
    selected: Boolean,
    accent: Color,
    accent2: Color,
    onSelect: () -> Unit
) {
    SettingRow(
        device.name,
        buildString {
            append(device.address)
            when {
                device.connectedHeadset && device.connectedMedia -> append(" · Hands-free и медиа")
                device.connectedHeadset -> append(" · Hands-free")
                device.connectedMedia -> append(" · медиа")
                else -> append(" · не подключено")
            }
        },
        if (device.connected) accent2 else Neon.TextLow
    ) {
        NeonToggle(selected, accent) { onSelect() }
    }
}

/* ═════════════════════  5. ВНЕШНИЙ ВИД  ═════════════════════ */

@Composable
private fun LookTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Картинка копируется во внутреннее хранилище приложения сразу при выборе —
    // так фон переживёт перезагрузку ГУ и не зависит от того, умеет ли конкретный
    // системный пикер выдавать постоянное разрешение на content://-ссылку.
    val pickBackground = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val path = runCatching {
                val dir = File(context.filesDir, "background").apply { mkdirs() }
                val dest = File(dir, "custom_bg.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                } ?: return@runCatching null
                dest.absolutePath
            }.getOrNull()
            if (path != null) edit { it.setBackgroundImagePath(path) }
        }
    }

    Column {
        SettingsSection("Неон", accent) {
            SettingRow("Цвет акцента", "Задаёт свечение всей оболочки", accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonAccent.entries.forEach { a ->
                        val sel = a.name == s.accent
                        Box(
                            Modifier
                                .size(34.dp)
                                .then(if (sel) Modifier.neonGlow(a.primary, 17.dp, 0.5f, 8.dp) else Modifier)
                                .clip(RoundedCornerShape(17.dp))
                                .background(a.primary.copy(alpha = if (sel) 0.9f else 0.35f))
                                .border(
                                    if (sel) 2.dp else 1.dp,
                                    a.primary,
                                    RoundedCornerShape(17.dp)
                                )
                                .clickable { edit { it.setAccent(a.name) } }
                        )
                    }
                }
            }
            SettingRow(
                "Фон рабочего стола",
                if (s.backgroundImagePath.isBlank()) "Стандартный неоновый фон"
                else "Своя картинка",
                accent
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Загрузить", accent) { pickBackground.launch("image/*") }
                    if (s.backgroundImagePath.isNotBlank()) {
                        SmallButton("Сбросить", Neon.Red) { edit { it.setBackgroundImagePath("") } }
                    }
                }
            }
            if (s.backgroundImagePath.isNotBlank()) {
                SettingRow(
                    "Затемнение фона",
                    "Приглушает яркую картинку, чтобы не резала глаз и не мешала читать панели",
                    accent
                ) {
                    Text(
                        "${(s.backgroundDarken * 100).roundToInt()}%",
                        color = accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                }
                NeonSlider(
                    value = s.backgroundDarken,
                    range = 0.15f..0.9f,
                    accent = accent,
                    modifier = Modifier.fillMaxWidth()
                ) { v -> edit { it.setBackgroundDarken(v) } }
            }
            SettingRow(
                "Анимированный фон",
                if (s.backgroundImagePath.isBlank()) "Дрейф неоновых пятен и сетка перспективы"
                else "Не действует, пока выбрана своя картинка",
                accent
            ) {
                NeonToggle(s.animatedBackground, accent) { v ->
                    edit { it.setAnimatedBackground(v) }
                }
            }
            SettingRow(
                "Упрощённая графика",
                "Отключает декоративные бесконечные анимации (фон, HUD карты, спектр " +
                    "плеера) — заметно снижает нагрузку на слабых головных устройствах",
                accent
            ) {
                NeonToggle(s.reducedEffects, accent) { v -> edit { it.setReducedEffects(v) } }
            }
        }

        SettingsSection("Раскладка", accent2) {
            SettingRow(
                "Строка приборов (заправка · спидометр · погода)",
                if (s.showSpeedometer) "Показана над плеером"
                else "Скрыта — плеер занимает всю колонку приборов",
                accent2
            ) {
                NeonToggle(s.showSpeedometer, accent2) { v -> edit { it.setShowSpeedometer(v) } }
            }
            SettingRow("Сторона бокового меню", "Под правый или левый руль", accent2) {
                NeonSegmented(
                    options = SidebarSide.entries.toList(),
                    selected = s.sidebarSide,
                    label = { if (it == SidebarSide.LEFT) "Слева" else "Справа" },
                    accent = accent2
                ) { v -> edit { it.setSidebarSide(v) } }
            }
            SettingRow("Формат времени", if (s.show24h) "24 часа" else "12 часов", accent2) {
                NeonToggle(s.show24h, accent2) { v -> edit { it.setShow24h(v) } }
            }
            SettingRow("Единицы скорости", s.units.label, accent2) {
                NeonSegmented(
                    options = SpeedUnits.entries.toList(),
                    selected = s.units,
                    label = { it.label },
                    accent = accent2
                ) { v -> edit { it.setUnits(v) } }
            }
        }
    }
}

/* ═════════════════════  НАВИГАЦИЯ  ═════════════════════ */

@Composable
private fun NavTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    val context = LocalContext.current
    val gps by SpeedProvider.state.collectAsState()
    val apps = remember { NavigatorBridge.installedNavApps(context) }
    val freeform = remember { NavigatorBridge.freeformSupported(context) }
    val canOverlay = NeonOverlayService.canDraw(context)

    Column {
        SettingsSection("Приложение навигации", accent) {
            if (apps.isEmpty()) {
                Hint("На устройстве не найдено ни одного навигационного приложения.", accent2)
            } else {
                apps.forEach { app ->
                    val chosen = app.packageName == s.mapPackage
                    SettingRow(
                        app.label,
                        app.packageName,
                        accent
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmallButton("Открыть", Neon.TextMid) {
                                NavigatorBridge.openFullscreen(context, app.packageName)
                            }
                            NeonToggle(chosen, accent) {
                                edit { it.setMapPackage(app.packageName) }
                            }
                        }
                    }
                }
            }
        }

        SettingsSection("Карта во фрейме", accent2) {
            SettingRow(
                "Режим показа",
                s.mapMode.hint,
                accent2
            ) {
                NeonSegmented(
                    options = MapMode.entries.toList(),
                    selected = s.mapMode,
                    label = { it.label },
                    accent = accent2
                ) { v -> edit { it.setMapMode(v) } }
            }

            if (s.mapMode == MapMode.FRAME) {
                SettingRow(
                    "Поддержка плавающих окон",
                    if (freeform) "Прошивка сообщает о поддержке freeform — режим будет работать"
                    else "Прошивка о поддержке не сообщает. Включите freeform по инструкции " +
                        "ниже или выберите режим «Поверх карты»",
                    if (freeform) accent2 else Neon.Amber
                ) {
                    SmallButton(if (freeform) "Готово" else "Нет", if (freeform) accent2 else Neon.Amber) {}
                }
                Hint(
                    "Включение freeform на головном устройстве, один раз через adb:\n" +
                        "adb shell settings put global enable_freeform_support 1\n" +
                        "adb shell settings put global force_resizable_activities 1\n" +
                        "После этого перезагрузите устройство.",
                    accent2
                )
            }

            if (s.mapMode == MapMode.OVERLAY) {
                SettingRow(
                    "Разрешение «Поверх других приложений»",
                    if (canOverlay) "Выдано — панели лягут поверх карты"
                    else "Не выдано, без него режим не запустится",
                    if (canOverlay) accent2 else Neon.Amber
                ) {
                    SmallButton(
                        if (canOverlay) "Выдано" else "Выдать",
                        if (canOverlay) accent2 else Neon.Amber
                    ) {
                        if (!canOverlay) NeonOverlayService.requestPermission(context)
                    }
                }
            }

            SettingRow(
                "Запускать навигацию автоматически",
                "Сразу после старта оболочки карта поднимается сама — не нужно ничего нажимать",
                accent2
            ) {
                NeonToggle(s.mapAutoStart, accent2) { v -> edit { it.setMapAutoStart(v) } }
            }
            if (s.mapAutoStart) {
                SettingRow(
                    "Задержка автозапуска",
                    "${s.mapAutoStartDelaySec} с — чтобы система и GPS успели подняться",
                    accent2
                ) {
                    NeonSlider(
                        value = s.mapAutoStartDelaySec.toFloat(),
                        range = 0f..30f,
                        accent = accent2,
                        modifier = Modifier.width(220.dp)
                    ) { v -> edit { it.setMapAutoStartDelay(v.roundToInt()) } }
                }
            }
            SettingRow("Проверить сейчас", "Поднять карту в выбранном режиме", accent) {
                SmallButton("Запустить", accent) {
                    MapFrameController.launch(context, s)
                }
            }
        }

        SettingsSection("Точка «Дом»", accent) {
            SettingRow(
                "Сохранённая точка",
                if (s.hasHomePoint) "%.5f, %.5f".format(s.homeLat, s.homeLon)
                else "Не задана — кнопка «Домой» на карте пока неактивна",
                accent
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(
                        if (gps.hasFix) "Запомнить здесь" else "Нет GPS",
                        if (gps.hasFix) accent2 else Neon.TextLow
                    ) {
                        if (gps.hasFix) edit { it.setHomePoint(gps.lastLat, gps.lastLon) }
                    }
                    if (s.hasHomePoint) {
                        SmallButton("Сброс", accent) { edit { it.clearHomePoint() } }
                    }
                }
            }
        }

        Hint(
            "Встроить чужое окно внутрь своего Android не позволяет, а рисовать карту " +
                "самостоятельно нельзя без ключа MapKit — подписка Плюс в Навигаторе такого " +
                "права не даёт. Поэтому «карта во фрейме» делается двумя честными способами: " +
                "плавающим окном по границам панели или полноэкранной картой с панелями " +
                "оболочки поверх неё. Второй способ работает на любой прошивке.",
            accent2
        )
    }
}

/* ═════════════════════  6. СИСТЕМА  ═════════════════════ */

@Composable
private fun SystemTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    val context = LocalContext.current
    val isDefault = DefaultLauncherHelper.isDefault(context)

    Column {
        SettingsSection("Оболочка по умолчанию", accent) {
            SettingRow(
                "Сделать NeonDrive рабочим столом",
                if (isDefault) "NeonDrive уже назначен домашним экраном"
                else "Система откроет выбор домашнего экрана — отметьте NeonDrive",
                if (isDefault) accent else Neon.Amber
            ) {
                NeonToggle(s.beDefaultLauncher || isDefault, accent) { v ->
                    edit { it.setBeDefaultLauncher(v) }
                    if (v && !isDefault) DefaultLauncherHelper.requestDefault(context)
                }
            }

            if (!isDefault) {
                SettingRow(
                    "Диалог выбора не появляется?",
                    "Значит домашний экран уже закреплён за другим приложением. " +
                        "Откройте его карточку и нажмите «Удалить настройки по умолчанию»",
                    Neon.Amber
                ) {
                    SmallButton("Открыть", Neon.Amber) {
                        DefaultLauncherHelper.openCurrentLauncherSettings(context)
                    }
                }
            }

            SettingRow(
                "Всегда открывать оболочку поверх",
                "При включении экрана, пробуждении, подаче питания (зажигание) и загрузке " +
                    "системы NeonDrive сам выходит на передний план. Работает, когда " +
                    "оболочка назначена домашним экраном",
                accent
            ) {
                NeonToggle(s.startOnScreenOn, accent) { v -> edit { it.setStartOnScreenOn(v) } }
            }
        }

        SettingsSection("Поведение", accent2) {
            SettingRow("Запуск при включении", "Автоматика поднимается вместе с системой", accent2) {
                NeonToggle(s.startOnBoot, accent2) { v -> edit { it.setStartOnBoot(v) } }
            }
            SettingRow("Не гасить экран", "Держать подсветку, пока открыт рабочий стол", accent2) {
                NeonToggle(s.keepScreenOn, accent2) { v -> edit { it.setKeepScreenOn(v) } }
            }
        }

        SettingsSection("Разрешения и доступы", accent2) {
            SettingRow(
                "Доступ к уведомлениям",
                if (NeonNotificationListener.isEnabled(context)) "Выдан"
                else "Не выдан — реакции и Яндекс.Музыка не работают",
                accent2
            ) {
                SmallButton("Открыть", accent2) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            SettingRow(
                "Поверх других приложений",
                if (NeonOverlayService.canDraw(context)) "Выдано"
                else "Нужно для режима «Поверх карты»",
                accent2
            ) {
                SmallButton("Открыть", accent2) {
                    NeonOverlayService.requestPermission(context)
                }
            }
            SettingRow("Разрешения приложения", "Геолокация, музыка, телефон, контакты", accent2) {
                SmallButton("Открыть", accent2) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }

        Hint("NeonDrive · версия 1.3.1", accent)
    }
}

/* ═════════════════════  ОБЩИЕ МЕЛОЧИ  ═════════════════════ */

@Composable
private fun SmallButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Hint(text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.06f))
            .padding(14.dp)
    ) {
        Text(text, color = Neon.TextLow, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun WarningCard(title: String, text: String, color: Color, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .neonGlow(color, 16.dp, 0.2f, 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        HudLabel(title, color)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Neon.TextMid, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
