package com.example.comiclab

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.comiclab.core.settings.ComicLabAppSettings
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

    @Test fun enhancementScalePersistsAndSurvivesConfigurationExport() {
        assertEquals(2, AppSettings.getEnhancementScale(context))
        AppSettings.setEnhancementScale(context, 3)
        val snapshot = AppSettings.snapshot(context)
        val configuration = com.example.comiclab.core.settings.ComicLabConfiguration(
            appSettings = snapshot)
        val json = com.example.comiclab.core.settings.ComicLabConfigurationJson
        val decoded = json.decode(json.encode(configuration)).configuration.appSettings
        AppSettings.setEnhancementScale(context, 4)
        AppSettings.applySnapshot(context, decoded)
        assertEquals(3, AppSettings.getEnhancementScale(context))
        context.getSharedPreferences("app_settings", 0).edit().putInt("enhancement_scale", 99).commit()
        assertEquals(2, AppSettings.getEnhancementScale(context))
    }

    @Test fun settingsRadioGroupReadsAndUpdatesSharedEnhancementScale() {
        AppSettings.setEnhancementScale(context, 3)
        val controller = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val group = controller.get().findViewById<android.widget.RadioGroup>(R.id.radioEnhancementScale)
            assertEquals(R.id.radioEnhancement3, group.checkedRadioButtonId)
            group.check(R.id.radioEnhancement4)
            assertEquals(4, AppSettings.getEnhancementScale(context))
            controller.recreate()
            assertEquals(R.id.radioEnhancement4, controller.get()
                .findViewById<android.widget.RadioGroup>(R.id.radioEnhancementScale).checkedRadioButtonId)
        } finally { controller.pause().stop().destroy() }
    }

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
        assertTrue(AppSettings.isDoublePageReadingEnabled(context))
        assertTrue(AppSettings.isDoublePageCoverSingleEnabled(context))
        assertTrue(AppSettings.isVolumeKeyPageTurnEnabled(context))
        assertTrue(AppSettings.isAutoHideSystemBarsEnabled(context))
        assertEquals(AppSettings.DEFAULT_START_MARKER_ERROR_TAGS, AppSettings.getStartMarkerErrorTags(context))
        assertEquals(AppSettings.DEFAULT_READER_BRIGHTNESS, AppSettings.getCustomReaderBrightness(context))

        AppSettings.setReadingDirection(context, "invalid")
        AppSettings.setDoublePageReadingEnabled(context, false)
        AppSettings.setCustomReaderBrightness(context, Int.MIN_VALUE)

        assertEquals(
            AppSettings.READING_DIRECTION_TOP_TO_BOTTOM,
            AppSettings.getReadingDirection(context)
        )
        assertFalse(AppSettings.isDoublePageReadingEnabled(context))
        assertEquals(0, AppSettings.getCustomReaderBrightness(context))

        AppSettings.setCustomReaderBrightness(context, Int.MAX_VALUE)
        assertEquals(AppSettings.MAX_READER_BRIGHTNESS, AppSettings.getCustomReaderBrightness(context))
    }

    @Test
    fun migratesLegacyMinimumBrightnessToZeroButKeepsNewFirstStep() {
        val preferences = context.getSharedPreferences("app_settings", 0)
        preferences.edit().putInt("custom_reader_brightness", 1).commit()

        assertEquals(0, AppSettings.getCustomReaderBrightness(context))

        AppSettings.setCustomReaderBrightness(context, 1)
        assertEquals(1, AppSettings.getCustomReaderBrightness(context))
    }

    @Test
    fun readsLegacyPreferenceFileAndKeysWithoutChangingTheirNames() {
        val root = tempDirectory("comiclab-settings-legacy")
        try {
            val firstShelf = File(root, "first").apply { mkdirs() }
            val secondShelf = File(root, "second").apply { mkdirs() }
            val legacyPaths = setOf(firstShelf.absolutePath, secondShelf.absolutePath)
            val preferences = context.getSharedPreferences("app_settings", 0)
            val legacyKeys = setOf(
                "dark_mode_enabled",
                "reading_direction",
                "double_page_cover_single",
                "volume_key_page_turn",
                "auto_hide_system_bars",
                "bookcase_directories",
                "start_marker_error_tags",
                "custom_reader_brightness_enabled",
                "custom_reader_brightness"
            )

            assertTrue(
                preferences.edit()
                    .putBoolean("dark_mode_enabled", true)
                    .putString("reading_direction", "left_to_right")
                    .putBoolean("double_page_cover_single", false)
                    .putBoolean("volume_key_page_turn", false)
                    .putBoolean("auto_hide_system_bars", false)
                    .putStringSet("bookcase_directories", legacyPaths)
                    .putString("start_marker_error_tags", "legacy;tags")
                    .putBoolean("custom_reader_brightness_enabled", true)
                    .putInt("custom_reader_brightness", 211)
                    .commit()
            )
            assertEquals(legacyKeys, preferences.all.keys)

            assertTrue(AppSettings.isDarkModeEnabled(context))
            assertEquals(AppSettings.READING_DIRECTION_LEFT_TO_RIGHT, AppSettings.getReadingDirection(context))
            assertFalse(AppSettings.isDoublePageCoverSingleEnabled(context))
            assertFalse(AppSettings.isVolumeKeyPageTurnEnabled(context))
            assertFalse(AppSettings.isAutoHideSystemBarsEnabled(context))
            assertEquals(legacyPaths, AppSettings.bookcaseDirectoryPaths(context))
            assertTrue(AppSettings.isBookcaseDirectory(context, firstShelf))
            assertEquals("legacy;tags", AppSettings.getStartMarkerErrorTags(context))
            assertTrue(AppSettings.isCustomReaderBrightnessEnabled(context))
            assertEquals(211, AppSettings.getCustomReaderBrightness(context))

            AppSettings.applyThemeMode(context)
            assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
            AppSettings.setDarkModeEnabled(context, false)
            assertFalse(AppSettings.isDarkModeEnabled(context))
            assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
            assertEquals(
                legacyKeys + "custom_reader_brightness_zero_min_migrated",
                preferences.all.keys
            )
        } finally {
            root.deleteRecursively()
        }
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
            doublePageReadingEnabled = false,
            customReaderBrightness = 999
        )

        assertEquals(5, AppSettings.applySnapshot(context, partial))

        val snapshot = AppSettings.snapshot(context)
        assertTrue(snapshot.darkModeEnabled == true)
        assertEquals(AppSettings.READING_DIRECTION_RIGHT_TO_LEFT, snapshot.readingDirection)
        assertFalse(snapshot.doublePageReadingEnabled == true)
        assertEquals(AppSettings.MAX_READER_BRIGHTNESS, snapshot.customReaderBrightness)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        AppSettings.setDarkModeEnabled(context, false)
        assertFalse(AppSettings.isDarkModeEnabled(context))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
    }

    private fun tempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}
