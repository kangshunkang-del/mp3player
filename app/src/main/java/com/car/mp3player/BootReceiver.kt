package com.car.mp3player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.playback.PlaybackStateHolder

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            ACTION_QUICKBOOT,
            ACTION_HTC_QUICKBOOT,
            ACTION_REBOOT -> startBootResume(context)
            Intent.ACTION_USER_PRESENT -> {
                if (!shouldResumeOnUnlock(context)) return
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val last = prefs.getLong(KEY_LAST_UNLOCK_RESUME, 0L)
                if (now - last < UNLOCK_DEBOUNCE_MS) return
                prefs.edit().putLong(KEY_LAST_UNLOCK_RESUME, now).apply()
                startBootResume(context)
            }
        }
    }

    private fun shouldResumeOnUnlock(context: Context): Boolean {
        val settings = SettingsRepository(context)
        if (!settings.bootAutoStart || !settings.autoResumePlayback) return false
        if (PlaybackStateHolder.isPlaying) return false
        return true
    }

    private fun startBootResume(context: Context) {
        val settings = SettingsRepository(context)
        if (!settings.bootAutoStart || !settings.autoResumePlayback) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, BootResumeService::class.java)
        )
    }

    companion object {
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_REBOOT = "android.intent.action.REBOOT"
        private const val PREFS = "boot_receiver"
        private const val KEY_LAST_UNLOCK_RESUME = "last_unlock_resume"
        private const val UNLOCK_DEBOUNCE_MS = 60_000L
    }
}
