package com.car.mp3player

import android.app.Application
import com.car.mp3player.data.SettingsRepository

class MP3PlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppForegroundTracker.init(this)
        SettingsRepository(this).applyTheme()
    }
}
