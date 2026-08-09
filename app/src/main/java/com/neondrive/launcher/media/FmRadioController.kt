package com.neondrive.launcher.media

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FmState(
    /** Найдено ли на устройстве заводское радио-приложение ГУ, которое можно открыть. */
    val factoryAppFound: Boolean = false,
    val factoryAppPackage: String? = null
)

/**
 * Обычное FM-радио по антенне головного устройства.
 *
 * ЧЕСТНОЕ ОГРАНИЧЕНИЕ, важное для правильных ожиданий: единственный системный API
 * Android для управления тюнером — android.hardware.radio.RadioManager — помечен в
 * AOSP как @SystemApi и @hide. Это означает сразу две вещи:
 *
 *  1. Класса нет в публичном android.jar. Обычное приложение не может даже
 *     СКОМПИЛИРОВАТЬСЯ со ссылкой на него стандартным Android SDK — не говоря уже
 *     о том, чтобы его вызвать. (Первая версия этого файла пыталась обращаться к
 *     RadioManager напрямую и не собралась бы — это было исправлено.)
 *  2. Разрешение ACCESS_BROADCAST_RADIO имеет уровень защиты signature|privileged:
 *     его получают только приложения, подписанные ключом платформы, или установленные
 *     как системные (/system/priv-app). Обычный apk, поставленный пользователем поверх
 *     штатной прошивки, такое разрешение не получит никогда — это ограничение самого
 *     Android, а не недоработка оболочки, и программно обойти его невозможно.
 *
 * Поэтому «настоящее» подключение к тюнеру и переключение частоты здесь не
 * реализовано — это была бы нечестная имитация работающей функции. Вместо этого
 * FM-раздел устроен так, как он реально может быть полезен стороннему приложению:
 *  • список станций «частота + название» — быстрая шпаргалка, настроиться на
 *    станцию пользователь может сам, штатными кнопками магнитолы;
 *  • если на ГУ есть предустановленное заводское радио-приложение (у многих
 *    Android-магнитол оно есть), кнопка «Играть» на станции открывает его —
 *    это единственное управление тюнером, действительно доступное стороннему
 *    приложению без специальных прав.
 */
object FmRadioController {

    private val _state = MutableStateFlow(FmState())
    val state: StateFlow<FmState> = _state

    // Пакеты заводских радио-приложений, встречающиеся на типичных Android-магнитолах.
    // Список не может быть исчерпывающим — прошивок слишком много, но покрывает
    // самые частые случаи (референсное приложение AOSP и несколько популярных OEM).
    private val KNOWN_FACTORY_RADIO_PACKAGES = listOf(
        "com.android.car.radio",
        "com.google.android.car.radio",
        "com.actions.radioapp",
        "com.tsinglink.android.radioapp",
        "com.baic.radio",
        "com.hiyi.radio",
        "com.xdroid.radio",
        "com.newsoft.radiofm"
    )

    private var initialized = false

    /** Проверить, есть ли на устройстве заводское радио-приложение. Безопасно вызывать многократно. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val pm = context.applicationContext.packageManager
        val found = KNOWN_FACTORY_RADIO_PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
        _state.value = FmState(factoryAppFound = found != null, factoryAppPackage = found)
    }

    /** Открыть заводское радио-приложение ГУ, если оно найдено. */
    fun openFactoryApp(context: Context): Boolean {
        val pkg = _state.value.factoryAppPackage ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
