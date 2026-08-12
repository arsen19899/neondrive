package com.neondrive.launcher.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Профиль железа головного устройства.
 *
 * Оболочка ставится на очень разные ГУ — от восьмиядерных UIS7862/8581 с 6 ГБ ОЗУ
 * до бюджетных четырёхъядерных Cortex-A53 с реальными 1–2 ГБ (при заявленных «4 ГБ»
 * в описании товара). На вторых декоративная графика — бесконечная анимация фона,
 * радиальные градиенты во весь экран каждый кадр — съедает заметную часть и без того
 * слабого GPU и даёт рывки при скролле и на карте.
 *
 * Раньше «упрощённая графика» была выключена по умолчанию, и пользователь слабого ГУ
 * видел тормозящую оболочку до тех пор, пока сам не находил тумблер в настройках.
 * Теперь заводское состояние тумблеров зависит от железа: на слабом ГУ эффекты
 * выключены сразу, на мощном — включены, как и раньше. Явный выбор пользователя,
 * если он его сделал, всегда важнее автоопределения (см. SettingsRepository).
 */
object DeviceProfile {

    @Volatile
    private var cached: Boolean? = null

    /**
     * Признак слабого ГУ. Считаем таким устройство, если верно хотя бы одно:
     *  • система сама помечена как low-RAM (`ActivityManager.isLowRamDevice`);
     *  • физической памяти меньше ~2,5 ГБ — типичный бюджетный ГУ, где «4 ГБ»
     *    из описания товара на деле оказываются 1–2 ГБ плюс zram;
     *  • не больше 4 ядер И нет поддержки Vulkan — почти всегда это связка
     *    Cortex-A53 + PowerVR/Mali начального уровня.
     */
    fun isLowEnd(context: Context): Boolean {
        cached?.let { return it }
        val result = runCatching { detect(context) }.getOrDefault(false)
        cached = result
        return result
    }

    private fun detect(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true) return true

        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024L * 1024L)
        if (totalMb in 1..2560) return true

        val cores = Runtime.getRuntime().availableProcessors()
        val hasVulkan = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        }.getOrDefault(false)
        return cores <= 4 && !hasVulkan
    }
}
