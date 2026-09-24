package com.example.comiclab

import android.content.Context
import java.io.File

object AppSettings {
    const val READING_DIRECTION_TOP_TO_BOTTOM = "top_to_bottom"
    const val READING_DIRECTION_RIGHT_TO_LEFT = "right_to_left"
    const val READING_DIRECTION_LEFT_TO_RIGHT = "left_to_right"

    private const val PREFS_NAME = "app_settings"
    private const val KEY_READING_DIRECTION = "reading_direction"
    private const val KEY_DOUBLE_PAGE_COVER_SINGLE = "double_page_cover_single"
    private const val KEY_VOLUME_KEY_PAGE_TURN = "volume_key_page_turn"
    private const val KEY_AUTO_HIDE_SYSTEM_BARS = "auto_hide_system_bars"
    private const val KEY_BOOKCASE_DIRECTORIES = "bookcase_directories"
    private val bookcaseDirectoriesLock = Any()
    private const val KEY_START_MARKER_ERROR_TAGS = "start_marker_error_tags"
    private const val KEY_CUSTOM_READER_BRIGHTNESS_ENABLED = "custom_reader_brightness_enabled"
    private const val KEY_CUSTOM_READER_BRIGHTNESS = "custom_reader_brightness"
    const val DEFAULT_START_MARKER_ERROR_TAGS = "中;汉;漢;翻;译;譯"
    const val DEFAULT_READER_BRIGHTNESS = 128
    const val MIN_READER_BRIGHTNESS = 1
    const val MAX_READER_BRIGHTNESS = 255
    const val DEFAULT_DOUBLE_PAGE_COVER_SINGLE = true

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

    fun isDoublePageCoverSingleEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DOUBLE_PAGE_COVER_SINGLE, DEFAULT_DOUBLE_PAGE_COVER_SINGLE)
    }

    fun setDoublePageCoverSingleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DOUBLE_PAGE_COVER_SINGLE, enabled)
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

    fun isBookcaseDirectory(context: Context, directory: File): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_BOOKCASE_DIRECTORIES, emptySet())
            .orEmpty()
            .contains(directory.absolutePath)
    }

    fun setBookcaseDirectory(context: Context, directory: File, enabled: Boolean) {
        synchronized(bookcaseDirectoriesLock) {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentPaths = preferences.getStringSet(KEY_BOOKCASE_DIRECTORIES, emptySet())
                .orEmpty()
                .toSet()
            preferences.edit()
                .putStringSet(
                    KEY_BOOKCASE_DIRECTORIES,
                    BookcaseFolderPaths.withSelection(currentPaths, directory.absolutePath, enabled)
                )
                .apply()
        }
    }

    fun setBookcaseDirectories(
        context: Context,
        directories: Collection<File>,
        enabled: Boolean
    ): Int {
        val selectedPaths = directories.mapTo(mutableSetOf()) { it.absolutePath }
        if (selectedPaths.isEmpty()) {
            return 0
        }

        return synchronized(bookcaseDirectoriesLock) {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentPaths = preferences.getStringSet(KEY_BOOKCASE_DIRECTORIES, emptySet())
                .orEmpty()
                .toSet()
            val changedCount = selectedPaths.count { (it in currentPaths) != enabled }
            if (changedCount == 0) {
                return@synchronized 0
            }

            preferences.edit()
                .putStringSet(
                    KEY_BOOKCASE_DIRECTORIES,
                    BookcaseFolderPaths.withSelections(currentPaths, selectedPaths, enabled)
                )
                .apply()
            changedCount
        }
    }

    fun renameBookcaseDirectory(context: Context, oldDirectory: File, newDirectory: File) {
        synchronized(bookcaseDirectoriesLock) {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentPaths = preferences.getStringSet(KEY_BOOKCASE_DIRECTORIES, emptySet())
                .orEmpty()
                .toSet()
            preferences.edit()
                .putStringSet(
                    KEY_BOOKCASE_DIRECTORIES,
                    BookcaseFolderPaths.afterRename(
                        currentPaths,
                        oldDirectory.absolutePath,
                        newDirectory.absolutePath
                    )
                )
                .apply()
        }
    }

    fun removeBookcaseDirectory(context: Context, directory: File) {
        synchronized(bookcaseDirectoriesLock) {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentPaths = preferences.getStringSet(KEY_BOOKCASE_DIRECTORIES, emptySet())
                .orEmpty()
                .toSet()
            preferences.edit()
                .putStringSet(
                    KEY_BOOKCASE_DIRECTORIES,
                    BookcaseFolderPaths.underDirectory(currentPaths, directory.absolutePath)
                )
                .apply()
        }
    }

    fun getStartMarkerErrorTags(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_START_MARKER_ERROR_TAGS, DEFAULT_START_MARKER_ERROR_TAGS)
            ?: DEFAULT_START_MARKER_ERROR_TAGS
    }

    fun setStartMarkerErrorTags(context: Context, tags: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_START_MARKER_ERROR_TAGS, tags)
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
