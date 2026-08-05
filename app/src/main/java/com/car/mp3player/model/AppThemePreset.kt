package com.car.mp3player.model

import com.car.mp3player.R

enum class AppThemePreset(
    val id: String,
    val displayName: String,
    val styleRes: Int
) {
    NETEASE("netease", "网易云", R.style.Theme_MP3Player),
    ZELDA("zelda", "塞尔达", R.style.Theme_MP3Player_Zelda),
    MARIO("mario", "马里奥", R.style.Theme_MP3Player_Mario);

    companion object {
        fun fromId(id: String): AppThemePreset =
            entries.firstOrNull { it.id == id } ?: NETEASE
    }
}
