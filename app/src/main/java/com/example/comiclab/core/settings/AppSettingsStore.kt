package com.example.comiclab.core.settings

import com.example.comiclab.core.store.BookcaseFolderPaths
import com.example.comiclab.core.store.PrefsGateway
import android.content.Context
import android.content.SharedPreferences
import java.io.File

internal object AppSettingsStore {
    internal const val READING_DIRECTION_TOP_TO_BOTTOM = "top_to_bottom"
    internal const val READING_DIRECTION_RIGHT_TO_LEFT = "right_to_left"
    internal const val READING_DIRECTION_LEFT_TO_RIGHT = "left_to_right"

    internal const val DEFAULT_START_MARKER_ERROR_TAGS = "中;汉;漢;翻;译;譯"
    internal const val DEFAULT_READER_BRIGHTNESS = 128
    internal const val MIN_READER_BRIGHTNESS = 0
    internal const val MAX_READER_BRIGHTNESS = 255
    internal const val DEFAULT_DOUBLE_PAGE_COVER_SINGLE = true
    internal const val DEFAULT_DOUBLE_PAGE_READING_ENABLED = true

    private const val PREFS_NAME = "app_settings"
    private const val KEY_READING_DIRECTION = "reading_direction"
    private const val KEY_DOUBLE_PAGE_READING_ENABLED = "double_page_reading_enabled"
    private const val KEY_DOUBLE_PAGE_COVER_SINGLE = "double_page_cover_single"
    private const val KEY_VOLUME_KEY_PAGE_TURN = "volume_key_page_turn"
    private const val KEY_AUTO_HIDE_SYSTEM_BARS = "auto_hide_system_bars"
    private const val KEY_BOOKCASE_DIRECTORIES = "bookcase_directories"
    private const val KEY_START_MARKER_ERROR_TAGS = "start_marker_error_tags"
    private const val KEY_CUSTOM_READER_BRIGHTNESS_ENABLED = "custom_reader_brightness_enabled"
    private const val KEY_CUSTOM_READER_BRIGHTNESS = "custom_reader_brightness"
    private const val KEY_CUSTOM_READER_BRIGHTNESS_ZERO_MIN_MIGRATED =
        "custom_reader_brightness_zero_min_migrated"
    private const val KEY_DARK_MODE_ENABLED = "dark_mode_enabled"
    private val bookcaseDirectoriesLock = Any()

    fun isDarkModeEnabled(context: Context): Boolean {
        return PrefsGateway.open(context, PREFS_NAME)
            .getBoolean(KEY_DARK_MODE_ENABLED, false)
    }

    fun setDarkModeEnabled(context: Context, enabled: Boolean) {
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putBoolean(KEY_DARK_MODE_ENABLED, enabled)
            .apply()
    }

    fun getReadingDirection(context: Context): String {
        return PrefsGateway.open(context, PREFS_NAME)
            .getString(KEY_READING_DIRECTION, READING_DIRECTION_TOP_TO_BOTTOM)
            ?: READING_DIRECTION_TOP_TO_BOTTOM
    }

    fun setReadingDirection(context: Context, direction: String) {
        val normalizedDirection = when (direction) {
            READING_DIRECTION_RIGHT_TO_LEFT,
            READING_DIRECTION_LEFT_TO_RIGHT -> direction
            else -> READING_DIRECTION_TOP_TO_BOTTOM
        }

        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putString(KEY_READING_DIRECTION, normalizedDirection)
            .apply()
    }

    fun isDoublePageReadingEnabled(context: Context): Boolean {
        return PrefsGateway.open(context, PREFS_NAME)
            .getBoolean(KEY_DOUBLE_PAGE_READING_ENABLED, DEFAULT_DOUBLE_PAGE_READING_ENABLED)
    }

    fun setDoublePageReadingEnabled(context: Context, enabled: Boolean) {
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putBoolean(KEY_DOUBLE_PAGE_READING_ENABLED, enabled)
            .apply()
    }

    fun isDoublePageCoverSingleEnabled(context: Context): Boolean {
        return PrefsGateway.open(context, PREFS_NAME)
            .getBoolean(KEY_DOUBLE_PAGE_COVER_SINGLE, DEFAULT_DOUBLE_PAGE_COVER_SINGLE)
    }

    fun setDoublePageCoverSingleEnabled(context: Context, enabled: Boolean) {
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putBoolean(KEY_DOUBLE_PAGE_COVER_SINGLE, enabled)
            .apply()
    }

    fun isVolumeKeyPageTurnEnabled(context: Context): Boolean {
        return PrefsGateway.open(context, PREFS_NAME)
            .getBoolean(KEY_VOLUME_KEY_PAGE_TURN, true)
    }

    fun setVolumeKeyPageTurnEnabled(context: Context, enabled: Boolean) {
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putBoolean(KEY_VOLUME_KEY_PAGE_TURN, enabled)
            .apply()
    }

    fun isAutoHideSystemBarsEnabled(context: Context): Boolean {
        return PrefsGateway.open(context, PREFS_NAME)
            .getBoolean(KEY_AUTO_HIDE_SYSTEM_BARS, true)
    }

    fun setAutoHideSystemBarsEnabled(context: Context, enabled: Boolean) {
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putBoolean(KEY_AUTO_HIDE_SYSTEM_BARS, enabled)
            .apply()
    }

    fun isBookcaseDirectory(context: Context, directory: File): Boolean {
        return PrefsGateway.open(context, PREFS_NAME)
            .getStringSet(KEY_BOOKCASE_DIRECTORIES, emptySet())
            .orEmpty()
            .contains(directory.absolutePath)
    }

    fun setBookcaseDirectory(context: Context, directory: File, enabled: Boolean) {
        synchronized(bookcaseDirectoriesLock) {
            val preferences = PrefsGateway.open(context, PREFS_NAME)
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
            val preferences = PrefsGateway.open(context, PREFS_NAME)
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

    fun bookcaseDirectoryPaths(context: Context): Set<String> {
        return synchronized(bookcaseDirectoriesLock) {
            PrefsGateway.open(context, PREFS_NAME)
                .getStringSet(KEY_BOOKCASE_DIRECTORIES, emptySet())
                .orEmpty()
                .toSet()
        }
    }

    fun renameBookcaseDirectory(context: Context, oldDirectory: File, newDirectory: File) {
        synchronized(bookcaseDirectoriesLock) {
            val preferences = PrefsGateway.open(context, PREFS_NAME)
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
            val preferences = PrefsGateway.open(context, PREFS_NAME)
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
        return PrefsGateway.open(context, PREFS_NAME)
            .getString(KEY_START_MARKER_ERROR_TAGS, DEFAULT_START_MARKER_ERROR_TAGS)
            ?: DEFAULT_START_MARKER_ERROR_TAGS
    }

    fun setStartMarkerErrorTags(context: Context, tags: String) {
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putString(KEY_START_MARKER_ERROR_TAGS, tags)
            .apply()
    }

    fun isCustomReaderBrightnessEnabled(context: Context): Boolean {
        return PrefsGateway.open(context, PREFS_NAME)
            .getBoolean(KEY_CUSTOM_READER_BRIGHTNESS_ENABLED, false)
    }

    fun setCustomReaderBrightnessEnabled(context: Context, enabled: Boolean) {
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putBoolean(KEY_CUSTOM_READER_BRIGHTNESS_ENABLED, enabled)
            .apply()
    }

    fun getCustomReaderBrightness(context: Context): Int {
        val preferences = PrefsGateway.open(context, PREFS_NAME)
        migrateLegacyReaderBrightnessMinimum(preferences)
        return preferences.getInt(KEY_CUSTOM_READER_BRIGHTNESS, DEFAULT_READER_BRIGHTNESS)
            .coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
    }

    fun setCustomReaderBrightness(context: Context, brightness: Int) {
        val preferences = PrefsGateway.open(context, PREFS_NAME)
        migrateLegacyReaderBrightnessMinimum(preferences)
        preferences
            .edit()
            .putInt(
                KEY_CUSTOM_READER_BRIGHTNESS,
                brightness.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
            )
            .apply()
    }

    private fun migrateLegacyReaderBrightnessMinimum(preferences: SharedPreferences) {
        if (preferences.getBoolean(KEY_CUSTOM_READER_BRIGHTNESS_ZERO_MIN_MIGRATED, false)) {
            return
        }

        val storedBrightness = preferences.getInt(KEY_CUSTOM_READER_BRIGHTNESS, DEFAULT_READER_BRIGHTNESS)
        val migratedBrightness = if (storedBrightness == LEGACY_MIN_READER_BRIGHTNESS) {
            MIN_READER_BRIGHTNESS
        } else {
            storedBrightness.coerceIn(MIN_READER_BRIGHTNESS, MAX_READER_BRIGHTNESS)
        }
        preferences.edit()
            .putInt(KEY_CUSTOM_READER_BRIGHTNESS, migratedBrightness)
            .putBoolean(KEY_CUSTOM_READER_BRIGHTNESS_ZERO_MIN_MIGRATED, true)
            .apply()
    }

    private const val LEGACY_MIN_READER_BRIGHTNESS = 1
}
