package com.neondrive.launcher.nav

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * Включение freeform-режима прошивки — того самого, без которого навигатор
 * невозможно показать на половине экрана, а не на всём.
 *
 * ## Почему это вообще нужно
 *
 * Android не даёт стороннему лаунчеру рисовать чужое окно внутри своего. Настоящих
 * способов показать навигацию на части экрана ровно два, и оба — системные:
 *
 *  • **split-screen** — не подходит. Домашний экран система в разделённый режим не
 *    пускает в принципе (`Task` домашнего стека не поддерживает split windowing mode),
 *    а NeonDrive именно домашний экран. Поэтому «поделить экран пополам с лаунчером»
 *    штатными средствами нельзя, сколько ни пробуй `FLAG_ACTIVITY_LAUNCH_ADJACENT`.
 *  • **freeform** — подходит: приложение поднимается плавающим окном с точно
 *    заданными границами (`ActivityOptions.setLaunchBounds`), и оболочка ставит
 *    его ровно по границам своей панели карты. Это и есть «навигация на пол экрана».
 *
 * ## Почему freeform обычно выключен
 *
 * На бюджетных ГУ (Unisoc UIS8581/SC9863, MTK AC8257 и подобных) freeform в ядре
 * системы собран, но глобальный флаг `enable_freeform_support` выключен. Менять
 * его имеет право только владелец разрешения `WRITE_SECURE_SETTINGS` — оно
 * `signature|privileged`, обычному приложению система его не выдаёт.
 *
 * ## Что делает этот объект
 *
 * Три пути включения, от самого чистого к самому «как получится»:
 *
 *  1. Разрешение уже выдано — обычно однократной командой с компьютера
 *     `adb shell pm grant com.neondrive.launcher android.permission.WRITE_SECURE_SETTINGS`.
 *     Тогда оболочка ставит флаги сама и больше об этом не спрашивает.
 *  2. На устройстве есть root (на китайских ГУ встречается часто) — те же настройки
 *     пишутся через `su`.
 *  3. Ничего из этого нет — показываем готовую команду для adb, копировать и вставить.
 *
 * ## Важно про перезагрузку
 *
 * `enable_freeform_support` WindowManager читает **один раз при старте системы**.
 * Записать флаг мало — ГУ нужно перезагрузить, иначе плавающие окна не появятся.
 * `force_resizable_activities` применяется без перезагрузки, поэтому пишем оба.
 */
object FreeformSetup {

    private const val KEY_FREEFORM = "enable_freeform_support"
    private const val KEY_RESIZABLE = "force_resizable_activities"

    /** Что удалось сделать при попытке включения. */
    enum class Result {
        /** Флаги записаны, нужна перезагрузка ГУ. */
        WRITTEN_NEEDS_REBOOT,

        /** Флаги уже стояли и freeform уже работает. */
        ALREADY_ON,

        /** Ни разрешения, ни root — остаётся команда для adb. */
        NO_ACCESS
    }

    /** Команда, которую нужно выполнить с компьютера один раз. */
    fun adbCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /** Запасной вариант — включить флаги напрямую, без выдачи разрешения приложению. */
    const val ADB_COMMAND_DIRECT: String =
        "adb shell settings put global enable_freeform_support 1\n" +
            "adb shell settings put global force_resizable_activities 1"

    /** Выдано ли приложению право менять защищённые настройки. */
    fun canWriteSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Стоят ли глобальные флаги. Не то же самое, что «freeform уже работает». */
    fun flagsSet(context: Context): Boolean =
        readGlobal(context, KEY_FREEFORM) == 1 && readGlobal(context, KEY_RESIZABLE) == 1

    /**
     * Работает ли freeform прямо сейчас.
     *
     * Системный признак устройства важнее записанных флагов: он появляется только
     * после перезагрузки, когда WindowManager флаг прочитал и действительно поднял
     * поддержку плавающих окон.
     */
    fun active(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val byFeature = runCatching {
            context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        }.getOrDefault(false)
        return byFeature || readGlobal(context, KEY_FREEFORM) == 1
    }

    /**
     * Попытаться включить freeform всеми доступными путями.
     * Тяжёлая операция (может запускать `su`), поэтому suspend и на IO.
     */
    suspend fun enable(context: Context): Result = withContext(Dispatchers.IO) {
        if (active(context) && flagsSet(context)) return@withContext Result.ALREADY_ON

        if (canWriteSecureSettings(context) && writeViaSettings(context)) {
            return@withContext if (active(context)) Result.ALREADY_ON
            else Result.WRITTEN_NEEDS_REBOOT
        }

        if (writeViaRoot()) {
            return@withContext Result.WRITTEN_NEEDS_REBOOT
        }

        Result.NO_ACCESS
    }

    /* ─────────────────  РЕАЛИЗАЦИЯ  ───────────────── */

    private fun readGlobal(context: Context, key: String): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, key, 0)
    }.getOrDefault(0)

    private fun writeViaSettings(context: Context): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, KEY_FREEFORM, 1)
        Settings.Global.putInt(context.contentResolver, KEY_RESIZABLE, 1)
        true
    }.getOrDefault(false)

    /**
     * Root есть далеко не везде, но на прошивках китайских ГУ встречается регулярно —
     * и это единственный путь, не требующий компьютера с adb. Если `su` нет,
     * `Runtime.exec` бросит исключение, и мы просто вернём false.
     */
    private fun writeViaRoot(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec("su")
        DataOutputStream(process.outputStream).use { out ->
            out.writeBytes("settings put global $KEY_FREEFORM 1\n")
            out.writeBytes("settings put global $KEY_RESIZABLE 1\n")
            out.writeBytes("exit\n")
            out.flush()
        }
        process.waitFor() == 0
    }.getOrDefault(false)
}
