package com.car.mp3player.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.car.mp3player.R
import com.car.mp3player.data.SettingsRepository
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object StartupSoundPlayer {
    @Volatile
    private var greetingPlayedThisSession = false

    fun hasPlayedThisSession(): Boolean = greetingPlayedThisSession

    /** 播放问候音并等待结束（续播 / 打开 App 前调用） */
    suspend fun playBeforeBootPlayback(context: Context, settings: SettingsRepository) {
        if (!settings.startupSoundEnabled || greetingPlayedThisSession) return
        suspendCancellableCoroutine { cont ->
            playInternal(context.applicationContext) {
                greetingPlayedThisSession = true
                cont.resume(Unit)
            }
        }
    }

    private fun playInternal(context: Context, onDone: () -> Unit) {
        runCatching {
            val player = MediaPlayer.create(context, R.raw.hello_linjun)
            if (player == null) {
                onDone()
                return
            }
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setVolume(1f, 1f)
            player.setOnCompletionListener {
                player.release()
                onDone()
            }
            player.setOnErrorListener { _, _, _ ->
                player.release()
                onDone()
                true
            }
            player.start()
        }.onFailure {
            onDone()
        }
    }
}
