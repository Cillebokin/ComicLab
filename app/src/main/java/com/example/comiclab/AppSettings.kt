package com.example.comiclab

import android.content.Context

object AppSettings {
    const val READING_DIRECTION_TOP_TO_BOTTOM = "top_to_bottom"
    const val READING_DIRECTION_RIGHT_TO_LEFT = "right_to_left"
    const val READING_DIRECTION_LEFT_TO_RIGHT = "left_to_right"

    private const val PREFS_NAME = "app_settings"
    private const val KEY_READING_DIRECTION = "reading_direction"
    private const val KEY_VOLUME_KEY_PAGE_TURN = "volume_key_page_turn"

    fun getReadingDirection(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_READING_DIRECTION, READING_DIRECTION_TOP_TO_BOTTOM)
            ?: READING_DIRECTION_TOP_TO_BOTTOM
    }

    fun setReadingDirection(context: Context, direction: String) {
        val normalizedDirection = when (direction) {
            READING_DIRECTION_RIGHT_TO_LEFT,
            READING_DIRECTION_LEFT_TO_RIGHT -> direction
            else -> READING_DIRECTION_TOP_TO_BOTTOM
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_READING_DIRECTION, normalizedDirection)
            .apply()
    }

    fun isVolumeKeyPageTurnEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOLUME_KEY_PAGE_TURN, true)
    }

    fun setVolumeKeyPageTurnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VOLUME_KEY_PAGE_TURN, enabled)
            .apply()
    }
}
