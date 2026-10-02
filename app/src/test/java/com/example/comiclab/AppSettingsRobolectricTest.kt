package com.example.comiclab

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppSettingsRobolectricTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("app_settings", 0).edit().clear().commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @After
    fun resetGlobalAppCompatState() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun preferencesReturnStableDefaultsAndNormalizeInvalidValues() {
        assertFalse(AppSettings.isDarkModeEnabled(context))
        assertEquals(
            AppSettings.READING_DIRECTION_TOP_TO_BOTTOM,
            AppSettings.getReadingDirection(context)
        )
        assertTrue(AppSettings.isDoublePageCoverSingleEnabled(context))
        assertTrue(AppSettings.isVolumeKeyPageTurnEnabled(context))
        assertTrue(AppSettings.isAutoHideSystemBarsEnabled(context))
        assertEquals(AppSettings.DEFAULT_START_MARKER_ERROR_TAGS, AppSettings.getStartMarkerErrorTags(context))
        assertEquals(AppSettings.DEFAULT_READER_BRIGHTNESS, AppSettings.getCustomReaderBrightness(context))

        AppSettings.setReadingDirection(context, "invalid")
        AppSettings.setCustomReaderBrightness(context, Int.MIN_VALUE)

        assertEquals(
            AppSettings.READING_DIRECTION_TOP_TO_BOTTOM,
            AppSettings.getReadingDirection(context)
        )
        assertEquals(AppSettings.MIN_READER_BRIGHTNESS, AppSettings.getCustomReaderBrightness(context))

        AppSettings.setCustomReaderBrightness(context, Int.MAX_VALUE)
        assertEquals(AppSettings.MAX_READER_BRIGHTNESS, AppSettings.getCustomReaderBrightness(context))
    }

    @Test
    fun bookcaseBatchSelectionIsIdempotentAndDirectoryRemovalIncludesDescendants() {
        val root = tempDirectory("comiclab-settings-bookcase")
        try {
            val shelf = File(root, "shelf").apply { mkdirs() }
            val nested = File(shelf, "nested").apply { mkdirs() }
            val other = File(root, "other").apply { mkdirs() }

            assertEquals(2, AppSettings.setBookcaseDirectories(context, listOf(shelf, nested, shelf), true))
            assertEquals(0, AppSettings.setBookcaseDirectories(context, listOf(shelf, nested), true))
            assertTrue(AppSettings.isBookcaseDirectory(context, nested))

            AppSettings.renameBookcaseDirectory(context, shelf, other)
            assertEquals(
                setOf(other.absolutePath, File(other, "nested").absolutePath),
                AppSettings.bookcaseDirectoryPaths(context)
            )

            AppSettings.removeBookcaseDirectory(context, other)
            assertTrue(AppSettings.bookcaseDirectoryPaths(context).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun settingsSnapshotRoundTripsOnlyPresentFieldsAndAppliesLocales() {
        val partial = ComicLabAppSettings(
            darkModeEnabled = true,
            languageTag = "zh-CN",
            readingDirection = AppSettings.READING_DIRECTION_RIGHT_TO_LEFT,
            customReaderBrightness = 999
        )

        assertEquals(4, AppSettings.applySnapshot(context, partial))

        val snapshot = AppSettings.snapshot(context)
        assertTrue(snapshot.darkModeEnabled == true)
        assertEquals(AppSettings.READING_DIRECTION_RIGHT_TO_LEFT, snapshot.readingDirection)
        assertEquals(AppSettings.MAX_READER_BRIGHTNESS, snapshot.customReaderBrightness)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        AppSettings.setDarkModeEnabled(context, false)
        assertFalse(AppSettings.isDarkModeEnabled(context))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
    }

    private fun tempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}
