package com.neondrive.launcher

import android.app.Application
import com.neondrive.launcher.automation.AutomationService
import com.neondrive.launcher.data.SettingsRepository
import com.neondrive.launcher.media.PlayerHub

class NeonApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        PlayerHub.init(this)
        runCatching { AutomationService.start(this) }
    }

    companion object {
        lateinit var instance: NeonApp
            private set
    }
}
