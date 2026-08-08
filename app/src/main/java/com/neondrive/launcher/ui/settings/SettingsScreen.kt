package com.neondrive.launcher.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neondrive.launcher.automation.NeonNotificationListener
import com.neondrive.launcher.automation.SpeedProvider
import com.neondrive.launcher.data.LauncherSettings
import com.neondrive.launcher.data.MusicSource
import com.neondrive.launcher.data.NotificationReaction
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.data.SidebarSide
import com.neondrive.launcher.data.SpeedUnits
import com.neondrive.launcher.data.SpeedVolumeStep
import com.neondrive.launcher.data.SwcAction
import com.neondrive.launcher.nav.NavigatorBridge
import com.neondrive.launcher.ui.common.HudLabel
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

typealias SettingsEdit = (suspend (SettingsRepository) -> Unit) -> Unit

private enum class Tab(val title: String) {
    MUSIC("Музыка"),
    REACTIONS("Реакции"),
    SPEED("Скорость"),
    WHEEL("Кнопки руля"),
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
    var tab by remember { mutableStateOf(Tab.MUSIC) }

    NeonScreenScaffold(
        title = "Настройки оболочки",
        subtitle = "NeonDrive · конфигурация рабочего стола и автоматики",
        accent = accent,
        onBack = onBack
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

            // Вертикальные вкладки
            Column(
                Modifier.width(190.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Tab.entries.forEach { t ->
                    val sel = t == tab
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .then(if (sel) Modifier.neonGlow(accent, 14.dp, 0.18f, 8.dp) else Modifier)
                            .background(if (sel) accent.copy(alpha = 0.14f) else Color(0x220C1424))
                            .border(
                                1.dp,
                                if (sel) accent.copy(alpha = 0.6f) else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { tab = t }
                            .padding(horizontal = 16.dp, vertical = 13.dp)
                    ) {
                        Text(
                            t.title,
                            color = if (sel) accent else Neon.TextMid,
                            fontSize = 14.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            // Содержимое
            LazyColumn(
                Modifier.weight(1f).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    when (tab) {
                        Tab.MUSIC -> MusicTab(settings, accent, accent2, edit)
                        Tab.REACTIONS -> ReactionsTab(settings, accent, accent2, edit)
                        Tab.SPEED -> SpeedTab(settings, accent, accent2, edit)
                        Tab.WHEEL -> WheelTab(settings, accent, accent2, edit)
                        Tab.LOOK -> LookTab(settings, accent, accent2, edit)
                        Tab.SYSTEM -> SystemTab(settings, accent, accent2, edit)
                    }
                }
            }
        }
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

/* ═════════════════════  5. ВНЕШНИЙ ВИД  ═════════════════════ */

@Composable
private fun LookTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
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
            SettingRow("Анимированный фон", "Дрейф неоновых пятен и сетка перспективы", accent) {
                NeonToggle(s.animatedBackground, accent) { v ->
                    edit { it.setAnimatedBackground(v) }
                }
            }
        }

        SettingsSection("Раскладка", accent2) {
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
            SettingRow("Приложение навигации", s.mapPackage, accent2) {
                NeonSegmented(
                    options = NAV_PACKAGES,
                    selected = s.mapPackage.takeIf { it in NAV_PACKAGES } ?: NAV_PACKAGES.first(),
                    label = { NAV_LABELS[it] ?: it },
                    accent = accent2
                ) { v -> edit { it.setMapPackage(v) } }
            }
        }

        NavigationSection(s, accent, accent2, edit)
    }
}

@Composable
private fun NavigationSection(
    s: LauncherSettings,
    accent: Color,
    accent2: Color,
    edit: SettingsEdit
) {
    val context = LocalContext.current
    val gps by SpeedProvider.state.collectAsState()
    val installed = NavigatorBridge.isInstalled(context, s.mapPackage)

    SettingsSection("Навигатор", accent) {
        SettingRow(
            "Яндекс.Навигатор",
            if (installed) "Установлен — оболочка управляет им через deep links"
            else "Не найден на устройстве",
            accent
        ) {
            SmallButton("Открыть", accent) {
                NavigatorBridge.openFullscreen(context, s.mapPackage)
            }
        }

        SettingRow(
            "Открывать в окне поверх панели",
            "Навигатор запускается в плавающем окне по границам карты. " +
                "Нужен freeform-режим прошивки — если он выключен, откроется на весь экран",
            accent
        ) {
            NeonToggle(s.navWindowed, accent) { v -> edit { it.setNavWindowed(v) } }
        }

        SettingRow(
            "Точка «Дом»",
            if (s.hasHomePoint) "%.5f, %.5f".format(s.homeLat, s.homeLon)
            else "Не задана — кнопка «Домой» на карте пока неактивна",
            accent2
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(
                    if (gps.hasFix) "Запомнить здесь" else "Нет GPS",
                    if (gps.hasFix) accent2 else Neon.TextLow
                ) {
                    if (gps.hasFix) {
                        edit { it.setHomePoint(gps.lastLat, gps.lastLon) }
                    }
                }
                if (s.hasHomePoint) {
                    SmallButton("Сброс", accent) { edit { it.clearHomePoint() } }
                }
            }
        }
    }

    Hint(
        "Подписка Плюс в самом Навигаторе относится к аккаунту внутри того приложения " +
            "и не даёт стороннему лаунчеру рисовать карту Яндекса у себя — для этого нужен " +
            "ключ MapKit/NaviKit SDK. Поэтому оболочка использует то, что работает без ключа: " +
            "URL-схемы Навигатора и запуск его окна поверх панели карты.",
        accent2
    )
}

private val NAV_PACKAGES = listOf(
    "ru.yandex.yandexnavi",
    "ru.yandex.yandexmaps",
    "com.google.android.apps.maps"
)
private val NAV_LABELS = mapOf(
    "ru.yandex.yandexnavi" to "Навигатор",
    "ru.yandex.yandexmaps" to "Я.Карты",
    "com.google.android.apps.maps" to "Google"
)

/* ═════════════════════  6. СИСТЕМА  ═════════════════════ */

@Composable
private fun SystemTab(s: LauncherSettings, accent: Color, accent2: Color, edit: SettingsEdit) {
    val context = LocalContext.current
    Column {
        SettingsSection("Поведение", accent) {
            SettingRow("Запуск при включении", "Автоматика поднимается вместе с системой", accent) {
                NeonToggle(s.startOnBoot, accent) { v -> edit { it.setStartOnBoot(v) } }
            }
            SettingRow("Не гасить экран", "Держать подсветку, пока открыт рабочий стол", accent) {
                NeonToggle(s.keepScreenOn, accent) { v -> edit { it.setKeepScreenOn(v) } }
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
            SettingRow("Лаунчер по умолчанию", "Сделать NeonDrive рабочим столом", accent2) {
                SmallButton("Выбрать", accent2) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_HOME_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            SettingRow("Разрешения приложения", "Геолокация, музыка, телефон", accent2) {
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

        Hint("NeonDrive · версия 1.0.0", accent)
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
