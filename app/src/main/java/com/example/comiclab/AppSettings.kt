package com.example.comiclab

import android.content.Context

object AppSettings {
    const val READING_DIRECTION_TOP_TO_BOTTOM = "top_to_bottom"
    const val READING_DIRECTION_RIGHT_TO_LEFT = "right_to_left"
    const val READING_DIRECTION_LEFT_TO_RIGHT = "left_to_right"

    private const val PREFS_NAME = "app_settings"
    private const val KEY_READING_DIRECTION = "reading_direction"
    private const val KEY_VOLUME_KEY_PAGE_TURN = "volume_key_page_turn"
    private const val KEY_AUTO_HIDE_SYSTEM_BARS = "auto_hide_system_bars"
    private const val KEY_DETECT_MANGA_COLLECTIONS = "detect_manga_collections"
    private const val KEY_CUSTOM_READER_BRIGHTNESS_ENABLED = "custom_reader_brightness_enabled"
    private const val KEY_CUSTOM_READER_BRIGHTNESS = "custom_reader_brightness"
    const val DEFAULT_READER_BRIGHTNESS = 128
    const val MIN_READER_BRIGHTNESS = 1
    const val MAX_READER_BRIGHTNESS = 255

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

    fun isAutoHideSystemBarsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_HIDE_SYSTEM_BARS, true)
    }

    fun setAutoHideSystemBarsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_HIDE_SYSTEM_BARS, enabled)
            .apply()
    }

    fun isDetectMangaCollectionsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DETECT_MANGA_COLLECTIONS, false)
    }

    fun setDetectMangaCollectionsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DETECT_MANGA_COLLECTIONS, enabled)
            .apply()
    }

    fun isCustomReaderBrightnessEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CUSTOM_READER_BRIGHTNESS_ENABLED, false)
    }

    fun setCustomReaderBrightnessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CUSTOM_READER_BRIGHTNESS_ENABLED, enabled)
            .apply()
    }

    fun getCustomReaderBrightness(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CUSTOM_READER_BRIGHTNESS, DEFAULT_READER_BRIGHTNESS)
            .coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
    }

    fun setCustomReaderBrightness(context: Context, brightness: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                KEY_CUSTOM_READER_BRIGHTNESS,
                brightness.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
            )
            .apply()
    }
}
