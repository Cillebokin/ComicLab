package com.example.comiclab.core.settings

import androidx.appcompat.app.AppCompatDelegate
import com.example.comiclab.AppSettings
import com.example.comiclab.MangaReaderActivity
import com.example.comiclab.core.settings.ComicLabAppSettings
import com.example.comiclab.core.settings.ComicLabConfiguration
import com.example.comiclab.core.settings.ComicLabEbookProgress
import com.example.comiclab.core.settings.ComicLabFavoriteComic
import com.example.comiclab.core.settings.ComicLabFavoritePath
import com.example.comiclab.core.settings.ComicLabHistoryEntry
import com.example.comiclab.core.settings.ComicLabMangaProgress
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.core.settings.ComicLabConfigurationStore
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComicLabConfigurationStoreRobolectricTest {

    private val context = RuntimeEnvironment.getApplication()
    private val preferenceNames = listOf(
        "app_settings",
        "favorite_comic_prefs",
        "favorite_path_prefs",
        "reading_history_prefs",
        "ebook_progress_prefs",
        "reader_prefs"
    )

    @Before
    fun clearPreferences() {
        preferenceNames.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.getEmptyLocaleList())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @After
    fun resetGlobalState() {
        preferenceNames.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.getEmptyLocaleList())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test
    fun exportCapturesPersistedCollectionsBookcaseSettingsAndReaderProgress() {
        val root = tempDirectory("comiclab-config-export")
        try {
            val cbz = File(root, "comic.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val epub = File(root, "novel.epub").apply { writeText("epub") }
            val shelf = File(root, "shelf").apply { mkdirs() }
            FavoriteComicStore.record(context, cbz)
            FavoritePathStore.record(context, shelf)
            ReadingHistoryStore.record(context, cbz)
            ReadingHistoryStore.record(context, epub)
            MangaReaderActivity.restoreReadingProgress(
                context,
                cbz,
                ComicLabMangaProgress(cbz.absolutePath, 7, 12)
            )
            com.example.comiclab.core.store.EbookProgressStore.save(
                context,
                epub,
                com.example.comiclab.ebook.EbookProgress(3, 0.4f)
            )
            AppSettings.setBookcaseDirectory(context, shelf, true)
            AppSettings.setReadingDirection(context, AppSettings.READING_DIRECTION_LEFT_TO_RIGHT)
            AppSettings.setCustomReaderBrightnessEnabled(context, true)

            val exported = ComicLabConfigurationStore.export(context)

            assertEquals(1, exported.favoriteComics.size)
            assertEquals(cbz.absolutePath, exported.favoriteComics.single().path)
            assertEquals(1, exported.favoritePaths.size)
            assertEquals(shelf.absolutePath, exported.favoritePaths.single().path)
            assertEquals(2, exported.readingHistory.size)
            assertTrue(exported.readingHistory.any { it.path == epub.absolutePath })
            assertEquals(listOf(shelf.absolutePath), exported.bookcasePaths)
            assertEquals(listOf(ComicLabMangaProgress(cbz.absolutePath, 7, 12)), exported.mangaReadingProgress)
            assertEquals(listOf(ComicLabEbookProgress(epub.absolutePath, 3, 0.4f)), exported.ebookReadingProgress)
            assertEquals(AppSettings.READING_DIRECTION_LEFT_TO_RIGHT, exported.appSettings.readingDirection)
            assertTrue(exported.appSettings.customReaderBrightnessEnabled == true)
            assertTrue(exported.exportedAt > 0L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importRestoresValidEntriesSkipsInvalidOnesAndAppliesSettings() {
        val root = tempDirectory("comiclab-config-import")
        try {
            val comic = File(root, "valid.cbz").apply { writeBytes(byteArrayOf(1, 2)) }
            val epub = File(root, "valid.epub").apply { writeText("epub") }
            val library = File(root, "library").apply { mkdirs() }
            val missingComic = File(root, "missing.cbz")
            val invalidFile = File(root, "invalid.bin").apply { writeText("x") }
            val missingDirectory = File(root, "missing-directory")

            val configuration = ComicLabConfiguration(
                favoriteComics = listOf(
                    ComicLabFavoriteComic(comic.absolutePath, 1L, 2L, comic.lastModified()),
                    ComicLabFavoriteComic(missingComic.absolutePath, 1L, 2L, 3L),
                    ComicLabFavoriteComic(invalidFile.absolutePath, 1L, 2L, 3L)
                ),
                favoritePaths = listOf(
                    ComicLabFavoritePath(library.absolutePath, 2L),
                    ComicLabFavoritePath(missingDirectory.absolutePath, 3L)
                ),
                readingHistory = listOf(
                    ComicLabHistoryEntry(comic.absolutePath, 4L, comic.length(), comic.lastModified()),
                    ComicLabHistoryEntry(missingComic.absolutePath, 5L, 0L, 0L)
                ),
                bookcasePaths = listOf(library.absolutePath, missingDirectory.absolutePath),
                mangaReadingProgress = listOf(
                    ComicLabMangaProgress(comic.absolutePath, 8, 9),
                    ComicLabMangaProgress(epub.absolutePath, 8, 9)
                ),
                ebookReadingProgress = listOf(
                    ComicLabEbookProgress(epub.absolutePath, 2, 0.6f),
                    ComicLabEbookProgress(missingComic.absolutePath, 1, 0.2f)
                ),
                appSettings = ComicLabAppSettings(
                    darkModeEnabled = true,
                    readingDirection = AppSettings.READING_DIRECTION_RIGHT_TO_LEFT
                )
            )

            val result = ComicLabConfigurationStore.importAndMerge(
                context,
                configuration,
                initiallySkippedEntries = 1
            )

            assertEquals(8, result.restoredEntries)
            assertEquals(8, result.skippedEntries)
            assertEquals(comic.absolutePath, FavoriteComicStore.items(context).single().file.absolutePath)
            assertEquals(library.absolutePath, FavoritePathStore.items(context).single().directory.absolutePath)
            assertEquals(comic.absolutePath, ReadingHistoryStore.items(context).single().file.absolutePath)
            assertEquals(ComicLabMangaProgress(comic.absolutePath, 8, 9), MangaReaderActivity.savedReadingProgress(context, comic))
            assertEquals(
                com.example.comiclab.ebook.EbookProgress(2, 0.6f),
                com.example.comiclab.core.store.EbookProgressStore.load(context, epub)
            )
            assertTrue(AppSettings.isDarkModeEnabled(context))
            assertEquals(AppSettings.READING_DIRECTION_RIGHT_TO_LEFT, AppSettings.getReadingDirection(context))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importCanCountButSkipApplyingAppSettings() {
        val configuration = ComicLabConfiguration(
            appSettings = ComicLabAppSettings(darkModeEnabled = true)
        )

        val result = ComicLabConfigurationStore.importAndMerge(
            context,
            configuration,
            applyAppSettings = false
        )

        assertEquals(1, result.restoredEntries)
        assertEquals(0, result.skippedEntries)
        assertFalse(AppSettings.isDarkModeEnabled(context))
    }

    @Test
    fun importRejectsCorruptLocalCollectionsAndPreservesTheirOriginalJson() {
        val stores = listOf(
            "favorite_comic_prefs" to "favorite_comics",
            "favorite_path_prefs" to "favorite_paths",
            "reading_history_prefs" to "reading_history"
        )
        stores.forEach { (name, key) ->
            preferenceNames.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
            val preferences = context.getSharedPreferences(name, 0)
            preferences.edit().putString(key, "[{broken").commit()
            assertThrows(IllegalStateException::class.java) {
                ComicLabConfigurationStore.importAndMerge(
                    context,
                    ComicLabConfiguration(appSettings = ComicLabAppSettings(darkModeEnabled = true))
                )
            }
            assertEquals("[{broken", preferences.getString(key, null))
            assertFalse(AppSettings.isDarkModeEnabled(context))
        }
    }

    private fun tempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}
