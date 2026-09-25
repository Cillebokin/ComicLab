package com.example.comiclab

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NightModeSettingsTest {

    @Test
    fun languageAndDarkModeRowsShareOneCardSurface() {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_ComicLab
        )
        val settings = LayoutInflater.from(context)
            .inflate(R.layout.activity_settings, null, false)
        val appearanceCardId = context.resources.getIdentifier(
            "cardAppearance",
            "id",
            context.packageName
        )

        assertTrue("Settings should group appearance controls in one card", appearanceCardId != 0)
        val appearanceCard = settings.findViewById<ViewGroup>(appearanceCardId)
        val languageRow = settings.findViewById<View>(R.id.cardLanguage)
        val darkModeRow = settings.findViewById<View>(R.id.cardDarkMode)

        assertSame("Language should be inside the shared appearance card", appearanceCard, languageRow.parent)
        assertSame("Dark mode should be inside the shared appearance card", appearanceCard, darkModeRow.parent)
        assertNotNull("The shared appearance card should provide the rounded surface", appearanceCard.background)
        assertEquals("The shared card should contain only its two setting rows", 2, appearanceCard.childCount)
    }

    @Test
    fun remainingInterfaceIconsUseThemeAwareTints() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseConfiguration = Configuration(context.resources.configuration)
        val darkConfiguration = Configuration(baseConfiguration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val darkContext = ContextThemeWrapper(
            context.createConfigurationContext(darkConfiguration),
            R.style.Theme_ComicLab
        )
        val iconTint = darkContext.getColor(R.color.comiclab_icon)
        val dangerTint = darkContext.getColor(R.color.comiclab_danger)
        val accentTint = darkContext.getColor(R.color.comiclab_accent)
        val inflater = LayoutInflater.from(darkContext)

        val favoritePath = inflater.inflate(R.layout.item_favorite_path, null)
        assertImageTint(iconTint, favoritePath.findViewById(R.id.imgFavoritePathIcon))
        assertImageTint(iconTint, favoritePath.findViewById(R.id.btnFavoritePathItemOptions))

        val favoriteComic = inflater.inflate(R.layout.item_favorite_comic, null)
        assertImageTint(iconTint, favoriteComic.findViewById(R.id.btnFavoriteComicItemOptions))
        val readingHistory = inflater.inflate(R.layout.item_reading_history, null)
        assertImageTint(iconTint, readingHistory.findViewById(R.id.btnReadingHistoryItemOptions))

        val favoritePathsPanel = inflater.inflate(R.layout.panel_favorite_paths, null)
        assertImageTint(dangerTint, favoritePathsPanel.findViewById(R.id.btnClearFavoritePaths))
        val historyPanel = inflater.inflate(R.layout.panel_reading_history, null)
        assertImageTint(dangerTint, historyPanel.findViewById(R.id.btnClearReadingHistory))
        val comicsPanel = inflater.inflate(R.layout.panel_favorite_comics, null)
        assertImageTint(dangerTint, comicsPanel.findViewById(R.id.btnClearFavoriteComics))

        val search = inflater.inflate(R.layout.activity_search, null)
        assertImageTint(accentTint, search.findViewById(R.id.btnRunSearch))

        val preview = inflater.inflate(R.layout.activity_manga_preview, null)
        assertImageTint(iconTint, preview.findViewById(R.id.btnFullRead))
        assertImageTint(iconTint, preview.findViewById(R.id.btnExitPreview))

        val collectionRow = inflater.inflate(R.layout.file_item_manga_collection, null)
        assertImageTint(iconTint, collectionRow.findViewById(R.id.imgIcon))
        val bookcaseTitle = collectionRow.findViewById<ViewGroup>(R.id.itemInfoContainer)
            .getChildAt(0) as ViewGroup
        assertImageTint(iconTint, bookcaseTitle.getChildAt(0) as ImageView)
        assertImageTint(
            accentTint,
            collectionRow.findViewById(R.id.imgFavoriteMarker)
        )
        val fileRow = inflater.inflate(R.layout.file_item, null)
        assertImageTint(accentTint, fileRow.findViewById(R.id.imgFavoriteMarker))

        val actionSheet = inflater.inflate(R.layout.bottom_sheet_archive_actions, null)
        assertEquals(
            iconTint,
            actionSheet.findViewById<TextView>(R.id.btnReadComic)
                .compoundDrawableTintList?.defaultColor
        )
        assertEquals(
            dangerTint,
            actionSheet.findViewById<TextView>(R.id.btnDeleteFile)
                .compoundDrawableTintList?.defaultColor
        )
        val directorySheet = inflater.inflate(R.layout.bottom_sheet_directory_actions, null)
        assertEquals(
            iconTint,
            directorySheet.findViewById<TextView>(R.id.btnBookcaseDirectoryAction)
                .compoundDrawableTintList?.defaultColor
        )
        assertEquals(
            dangerTint,
            directorySheet.findViewById<TextView>(R.id.btnDeleteDirectory)
                .compoundDrawableTintList?.defaultColor
        )
    }

    @Test
    fun darkPaletteUsesSlateSurfacesAndReadableText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseConfiguration = Configuration(context.resources.configuration)
        val lightConfiguration = Configuration(baseConfiguration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_NO
        }
        val darkConfiguration = Configuration(baseConfiguration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val lightResources = context.createConfigurationContext(lightConfiguration).resources
        val darkResources = context.createConfigurationContext(darkConfiguration).resources

        val darkBackground = darkResources.getColor(R.color.comiclab_file_picker_background, null)
        val darkCard = darkResources.getColor(R.color.comiclab_surface_card, null)
        val primaryText = darkResources.getColor(R.color.comiclab_text_primary, null)
        val secondaryText = darkResources.getColor(R.color.comiclab_text_secondary, null)

        assertEquals(Color.parseColor("#0F172A"), darkBackground)
        assertEquals(Color.parseColor("#1E293B"), darkCard)
        assertTrue(darkBackground != darkCard)
        assertEquals(
            Color.parseColor("#F8FAFC"),
            lightResources.getColor(R.color.comiclab_file_picker_background, null)
        )
        assertTrue(ColorUtils.calculateContrast(primaryText, darkBackground) >= 4.5)
        assertTrue(ColorUtils.calculateContrast(secondaryText, darkBackground) >= 4.5)
        assertFalse(darkBackground == Color.BLACK)
        assertFalse(primaryText == Color.WHITE)
    }

    @Test
    fun changingThemeAboveFileBrowserKeepsSettingsCardRendered() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val appPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val mainPreferences = context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
        val hadDarkModeValue = appPreferences.contains(DARK_MODE_PREFERENCE_KEY)
        val previousDarkModeValue = appPreferences.getBoolean(DARK_MODE_PREFERENCE_KEY, false)
        val hadPromptValue = mainPreferences.contains("storage_permission_prompted")
        val previousPromptValue = mainPreferences.getBoolean("storage_permission_prompted", false)
        val previousNightMode = AppCompatDelegate.getDefaultNightMode()
        var mainActivity: MainActivity? = null
        var settingsActivity: SettingsActivity? = null

        try {
            check(appPreferences.edit().putBoolean(DARK_MODE_PREFERENCE_KEY, false).commit())
            check(mainPreferences.edit().putBoolean("storage_permission_prompted", true).commit())
            AppSettings.applyThemeMode(context)

            mainActivity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                mainActivity!!.findViewById<View>(R.id.btnOptions).performClick()
            }
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                settingsActivity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<SettingsActivity>()
                    .lastOrNull()
            }
            assertNotNull("File browser should open Settings from its options button", settingsActivity)

            onView(withId(R.id.switchDarkMode)).perform(click())
            instrumentation.waitForIdleSync()

            var resumedSettings: SettingsActivity? = null
            var cardX = 0
            var cardY = 0
            var expectedCardColor = 0
            instrumentation.runOnMainSync {
                resumedSettings = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<SettingsActivity>()
                    .lastOrNull()

                val screen = checkNotNull(resumedSettings)
                val languageCard = screen.findViewById<View>(R.id.cardLanguage)
                val visibleBounds = Rect()
                assertTrue("Settings language card should stay visible after theme recreation", languageCard.isShown)
                assertTrue(languageCard.getGlobalVisibleRect(visibleBounds))
                assertTrue(visibleBounds.width() > 0 && visibleBounds.height() > 0)

                val location = IntArray(2)
                languageCard.getLocationOnScreen(location)
                cardX = location[0] + languageCard.width / 2
                cardY = location[1] + languageCard.height / 2
                expectedCardColor = screen.getColor(R.color.comiclab_surface_card)
            }

            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                assertTrue(cardX in 0 until screenshot.width)
                assertTrue(cardY in 0 until screenshot.height)
                assertEquals(
                    "Settings card should be drawn after switching to dark mode",
                    expectedCardColor,
                    screenshot.getPixel(cardX, cardY)
                )
            } finally {
                screenshot.recycle()
            }
        } finally {
            val appEditor = appPreferences.edit()
            if (hadDarkModeValue) {
                appEditor.putBoolean(DARK_MODE_PREFERENCE_KEY, previousDarkModeValue)
            } else {
                appEditor.remove(DARK_MODE_PREFERENCE_KEY)
            }
            check(appEditor.commit())

            val mainEditor = mainPreferences.edit()
            if (hadPromptValue) {
                mainEditor.putBoolean("storage_permission_prompted", previousPromptValue)
            } else {
                mainEditor.remove("storage_permission_prompted")
            }
            check(mainEditor.commit())

            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                (monitor.getActivitiesInStage(Stage.RESUMED) +
                    monitor.getActivitiesInStage(Stage.STOPPED))
                    .filter { it is SettingsActivity || it is MainActivity }
                    .distinct()
                    .forEach { it.finish() }
                settingsActivity?.finish()
                mainActivity?.finish()
                AppCompatDelegate.setDefaultNightMode(previousNightMode)
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun darkModeSwitchBelowLanguageStartsLightAndAppliesSavedChoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val hadDarkModeValue = preferences.contains(DARK_MODE_PREFERENCE_KEY)
        val previousDarkModeValue = preferences.getBoolean(DARK_MODE_PREFERENCE_KEY, false)
        val previousNightMode = AppCompatDelegate.getDefaultNightMode()
        var activity: SettingsActivity? = null

        try {
            check(preferences.edit().remove(DARK_MODE_PREFERENCE_KEY).commit())
            AppSettings.applyThemeMode(context)
            assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
            activity = instrumentation.startActivitySync(
                Intent(context, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as SettingsActivity
            instrumentation.waitForIdleSync()

            val switchId = context.resources.getIdentifier(
                "switchDarkMode",
                "id",
                context.packageName
            )
            assertTrue("Settings should expose the dark mode switch", switchId != 0)

            instrumentation.runOnMainSync {
                val languageCard = activity!!.findViewById<View>(R.id.cardLanguage)
                val darkModeSwitch = activity!!.findViewById<SwitchCompat>(switchId)
                val languageBounds = Rect()
                val switchBounds = Rect()
                assertFalse("Dark mode should default to light", darkModeSwitch.isChecked)
                assertTrue(languageCard.getGlobalVisibleRect(languageBounds))
                assertTrue(darkModeSwitch.getGlobalVisibleRect(switchBounds))
                assertTrue(
                    "Theme switch should appear below language settings",
                    switchBounds.top >= languageBounds.bottom
                )
            }

            onView(withId(switchId))
                .check(matches(isNotChecked()))
                .perform(click())
            instrumentation.waitForIdleSync()

            assertTrue(preferences.getBoolean(DARK_MODE_PREFERENCE_KEY, false))
            assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())
            AppSettings.applyThemeMode(context)
            assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

            var resumedSettings: SettingsActivity? = null
            instrumentation.runOnMainSync {
                resumedSettings = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<SettingsActivity>()
                    .lastOrNull()
            }
            assertNotNull("Settings should be recreated with the selected theme", resumedSettings)
            assertEquals(
                Configuration.UI_MODE_NIGHT_YES,
                resumedSettings!!.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            )
        } finally {
            val editor = preferences.edit()
            if (hadDarkModeValue) {
                editor.putBoolean(DARK_MODE_PREFERENCE_KEY, previousDarkModeValue)
            } else {
                editor.remove(DARK_MODE_PREFERENCE_KEY)
            }
            check(editor.commit())

            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(previousNightMode)
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<SettingsActivity>()
                    .forEach { it.finish() }
                activity?.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private companion object {
        const val DARK_MODE_PREFERENCE_KEY = "dark_mode_enabled"
    }

    private fun assertImageTint(expectedTint: Int, view: ImageView) {
        assertEquals(expectedTint, ImageViewCompat.getImageTintList(view)?.defaultColor)
    }
}
