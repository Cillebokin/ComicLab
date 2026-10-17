package com.example.comiclab

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.comiclab.core.settings.ComicLabAppSettings
import com.example.comiclab.core.settings.AppSettingsStore
import java.io.File

object AppSettings {
    const val READING_DIRECTION_TOP_TO_BOTTOM = AppSettingsStore.READING_DIRECTION_TOP_TO_BOTTOM
    const val READING_DIRECTION_RIGHT_TO_LEFT = AppSettingsStore.READING_DIRECTION_RIGHT_TO_LEFT
    const val READING_DIRECTION_LEFT_TO_RIGHT = AppSettingsStore.READING_DIRECTION_LEFT_TO_RIGHT

    const val DEFAULT_START_MARKER_ERROR_TAGS = AppSettingsStore.DEFAULT_START_MARKER_ERROR_TAGS
    const val DEFAULT_READER_BRIGHTNESS = AppSettingsStore.DEFAULT_READER_BRIGHTNESS
    const val MIN_READER_BRIGHTNESS = AppSettingsStore.MIN_READER_BRIGHTNESS
    const val MAX_READER_BRIGHTNESS = AppSettingsStore.MAX_READER_BRIGHTNESS
    const val DEFAULT_DOUBLE_PAGE_READING_ENABLED = AppSettingsStore.DEFAULT_DOUBLE_PAGE_READING_ENABLED
    const val DEFAULT_DOUBLE_PAGE_COVER_SINGLE = AppSettingsStore.DEFAULT_DOUBLE_PAGE_COVER_SINGLE

    fun isDarkModeEnabled(context: Context): Boolean =
        AppSettingsStore.isDarkModeEnabled(context)

    fun setDarkModeEnabled(context: Context, enabled: Boolean) {
        AppSettingsStore.setDarkModeEnabled(context, enabled)
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun applyThemeMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (AppSettingsStore.isDarkModeEnabled(context)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    fun getReadingDirection(context: Context): String =
        AppSettingsStore.getReadingDirection(context)

    fun setReadingDirection(context: Context, direction: String) =
        AppSettingsStore.setReadingDirection(context, direction)

    fun isDoublePageReadingEnabled(context: Context): Boolean =
        AppSettingsStore.isDoublePageReadingEnabled(context)

    fun setDoublePageReadingEnabled(context: Context, enabled: Boolean) =
        AppSettingsStore.setDoublePageReadingEnabled(context, enabled)

    fun isDoublePageCoverSingleEnabled(context: Context): Boolean =
        AppSettingsStore.isDoublePageCoverSingleEnabled(context)

    fun setDoublePageCoverSingleEnabled(context: Context, enabled: Boolean) =
        AppSettingsStore.setDoublePageCoverSingleEnabled(context, enabled)

    fun isVolumeKeyPageTurnEnabled(context: Context): Boolean =
        AppSettingsStore.isVolumeKeyPageTurnEnabled(context)

    fun setVolumeKeyPageTurnEnabled(context: Context, enabled: Boolean) =
        AppSettingsStore.setVolumeKeyPageTurnEnabled(context, enabled)

    fun isAutoHideSystemBarsEnabled(context: Context): Boolean =
        AppSettingsStore.isAutoHideSystemBarsEnabled(context)

    fun setAutoHideSystemBarsEnabled(context: Context, enabled: Boolean) =
        AppSettingsStore.setAutoHideSystemBarsEnabled(context, enabled)

    fun isBookcaseDirectory(context: Context, directory: File): Boolean =
        AppSettingsStore.isBookcaseDirectory(context, directory)

    fun setBookcaseDirectory(context: Context, directory: File, enabled: Boolean) =
        AppSettingsStore.setBookcaseDirectory(context, directory, enabled)

    fun setBookcaseDirectories(
        context: Context,
        directories: Collection<File>,
        enabled: Boolean
    ): Int = AppSettingsStore.setBookcaseDirectories(context, directories, enabled)

    fun bookcaseDirectoryPaths(context: Context): Set<String> =
        AppSettingsStore.bookcaseDirectoryPaths(context)

    fun snapshot(context: Context): ComicLabAppSettings {
        return ComicLabAppSettings(
            darkModeEnabled = isDarkModeEnabled(context),
            languageTag = AppCompatDelegate.getApplicationLocales().toLanguageTags(),
            readingDirection = getReadingDirection(context),
            doublePageReadingEnabled = isDoublePageReadingEnabled(context),
            doublePageCoverSingle = isDoublePageCoverSingleEnabled(context),
            volumeKeyPageTurn = isVolumeKeyPageTurnEnabled(context),
            autoHideSystemBars = isAutoHideSystemBarsEnabled(context),
            startMarkerErrorTags = getStartMarkerErrorTags(context),
            customReaderBrightnessEnabled = isCustomReaderBrightnessEnabled(context),
            customReaderBrightness = getCustomReaderBrightness(context)
        )
    }

    fun applySnapshot(context: Context, settings: ComicLabAppSettings): Int {
        var appliedCount = 0
        settings.darkModeEnabled?.let {
            setDarkModeEnabled(context, it)
            appliedCount++
        }
        settings.languageTag?.let {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
            appliedCount++
        }
        settings.readingDirection?.let {
            setReadingDirection(context, it)
            appliedCount++
        }
        settings.doublePageReadingEnabled?.let {
            setDoublePageReadingEnabled(context, it)
            appliedCount++
        }
        settings.doublePageCoverSingle?.let {
            setDoublePageCoverSingleEnabled(context, it)
            appliedCount++
        }
        settings.volumeKeyPageTurn?.let {
            setVolumeKeyPageTurnEnabled(context, it)
            appliedCount++
        }
        settings.autoHideSystemBars?.let {
            setAutoHideSystemBarsEnabled(context, it)
            appliedCount++
        }
        settings.startMarkerErrorTags?.let {
            setStartMarkerErrorTags(context, it)
            appliedCount++
        }
        settings.customReaderBrightnessEnabled?.let {
            setCustomReaderBrightnessEnabled(context, it)
            appliedCount++
        }
        settings.customReaderBrightness?.let {
            setCustomReaderBrightness(context, it)
            appliedCount++
        }
        return appliedCount
    }

    fun renameBookcaseDirectory(context: Context, oldDirectory: File, newDirectory: File) =
        AppSettingsStore.renameBookcaseDirectory(context, oldDirectory, newDirectory)

    fun removeBookcaseDirectory(context: Context, directory: File) =
        AppSettingsStore.removeBookcaseDirectory(context, directory)

    fun getStartMarkerErrorTags(context: Context): String =
        AppSettingsStore.getStartMarkerErrorTags(context)

    fun setStartMarkerErrorTags(context: Context, tags: String) =
        AppSettingsStore.setStartMarkerErrorTags(context, tags)

    fun isCustomReaderBrightnessEnabled(context: Context): Boolean =
        AppSettingsStore.isCustomReaderBrightnessEnabled(context)

    fun setCustomReaderBrightnessEnabled(context: Context, enabled: Boolean) =
        AppSettingsStore.setCustomReaderBrightnessEnabled(context, enabled)

    fun getCustomReaderBrightness(context: Context): Int =
        AppSettingsStore.getCustomReaderBrightness(context)

    fun setCustomReaderBrightness(context: Context, brightness: Int) =
        AppSettingsStore.setCustomReaderBrightness(context, brightness)
}
