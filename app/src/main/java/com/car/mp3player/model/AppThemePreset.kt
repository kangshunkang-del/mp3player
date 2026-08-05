package com.car.mp3player.model

import com.car.mp3player.R

enum class AppThemePreset(
    val id: String,
    val displayName: String,
    val styleRes: Int
) {
    NETEASE("netease", "夜航", R.style.Theme_MP3Player),
    ZELDA("zelda", "林野", R.style.Theme_MP3Player_Zelda),
    MARIO("mario", "赛道", R.style.Theme_MP3Player_Mario);

    companion object {
        fun fromId(id: String): AppThemePreset =
            entries.firstOrNull { it.id == id } ?: NETEASE
    }
}
