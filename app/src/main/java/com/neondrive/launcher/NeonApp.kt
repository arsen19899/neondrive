package com.neondrive.launcher

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import com.neondrive.launcher.automation.AutomationService
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.media.PlayerHub
import com.neondrive.launcher.nav.FreeformSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NeonApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        PlayerHub.init(this)
        runCatching { AutomationService.start(this) }
        enableCarMode()
        applyFreeformIfAllowed()
    }

    /**
     * Если приложению один раз выдали `WRITE_SECURE_SETTINGS` (командой с компьютера,
     * см. [com.neondrive.launcher.nav.FreeformSetup]), включаем freeform молча и сами —
     * при каждом старте, чтобы настройка не потерялась после сброса или обновления
     * прошивки. Без выданного разрешения метод не делает ничего и ничего не стоит.
     *
     * Это именно то, без чего навигацию нельзя показать на половине экрана:
     * плавающее окно с заданными границами существует только при включённом freeform.
     */
    private fun applyFreeformIfAllowed() {
        if (!FreeformSetup.canWriteSecureSettings(this)) return
        if (FreeformSetup.flagsSet(this)) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { FreeformSetup.enable(this@NeonApp) }
        }
    }

    /**
     * Сообщает системе, что устройство работает в автомобильном режиме.
     *
     * Это не косметика: именно по этому флагу Telecom решает, кому отдать
     * экран звонка — сервису с меткой IN_CALL_SERVICE_CAR_MODE_UI (нашему
     * [com.neondrive.launcher.phone.NeonInCallService]) вместо системного
     * «Телефона». Раз оболочка и есть замена рабочего стола ГУ (см.
     * CAR_DOCK-intent в манифесте), включаем режим сразу при старте процесса,
     * а не дожидаясь входящего звонка.
     *
     * На части прошивок enableCarMode ничего не меняет — это системная
     * рекомендация, а не жёсткая гарантия, поэтому свой экран звонка может
     * не подхватиться на совсем нестандартных ГУ. Собственные звонки, начатые
     * из PhoneScreen через TelecomManager.placeCall, всё равно приходят
     * в NeonInCallService напрямую в подавляющем большинстве случаев.
     */
    private fun enableCarMode() {
        runCatching {
            val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            uiModeManager?.enableCarMode(0)
        }
    }

    companion object {
        lateinit var instance: NeonApp
            private set
    }
}
