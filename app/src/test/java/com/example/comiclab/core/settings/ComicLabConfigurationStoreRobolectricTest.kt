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
    fun importDoesNotCountDeferredSettingsBeforeTheyAreApplied() {
        val configuration = ComicLabConfiguration(
            appSettings = ComicLabAppSettings(darkModeEnabled = true)
        )

        val result = ComicLabConfigurationStore.importAndMerge(
            context,
            configuration,
            applyAppSettings = false
        )

        assertEquals(0, result.restoredEntries)
        assertEquals(0, result.skippedEntries)
        assertFalse(AppSettings.isDarkModeEnabled(context))
    }

    @Test
    fun importCountsOnlyEntriesRetainedAfterAllThreeCollectionLimits() {
        val root = tempDirectory("comiclab-count-limits")
        try {
            val files = (0..101).map { File(root, "$it.cbz").apply { writeText("comic") } }
            val directories = (0..21).map { File(root, "shelf-$it").apply { mkdir() } }
            val result = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
                favoriteComics = files.mapIndexed { index, f -> ComicLabFavoriteComic(f.absolutePath, index + 1L, f.length(), f.lastModified()) },
                favoritePaths = directories.mapIndexed { index, f -> ComicLabFavoritePath(f.absolutePath, index + 1L) },
                readingHistory = files.take(32).mapIndexed { index, f -> ComicLabHistoryEntry(f.absolutePath, index + 1L, f.length(), f.lastModified()) }
            ), initiallySkippedEntries = 3)
            val retained = FavoriteComicStore.items(context).size + FavoritePathStore.items(context).size + ReadingHistoryStore.items(context).size
            assertEquals(150, retained)
            assertEquals(retained, result.restoredEntries)
            assertEquals(9, result.skippedEntries)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun duplicatePathsCountOncePerCategoryAndKeepExistingMergeRules() {
        val root = tempDirectory("comiclab-count-duplicates")
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            val ebook = File(root, "novel.epub").apply { writeText("ebook") }
            val shelf = File(root, "shelf").apply { mkdir() }
            val result = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
                favoriteComics = listOf(10L, 20L).map { ComicLabFavoriteComic(comic.absolutePath, it, 5L, 1L) },
                favoritePaths = listOf(10L, 20L).map { ComicLabFavoritePath(shelf.absolutePath, it) },
                readingHistory = listOf(20L, 10L).map { ComicLabHistoryEntry(comic.absolutePath, it, 5L, 1L) },
                bookcasePaths = listOf(shelf.absolutePath, shelf.absolutePath),
                mangaReadingProgress = listOf(ComicLabMangaProgress(comic.absolutePath, 4, 1), ComicLabMangaProgress(comic.absolutePath, 2, 3)),
                ebookReadingProgress = listOf(ComicLabEbookProgress(ebook.absolutePath, 4, 0.8f), ComicLabEbookProgress(ebook.absolutePath, 2, 0.3f))
            ))
            assertEquals(6, result.restoredEntries)
            assertEquals(6, result.skippedEntries)
            assertEquals(20L, FavoriteComicStore.items(context).single().addedAt)
            assertEquals(20L, FavoritePathStore.items(context).single().addedAt)
            assertEquals(20L, ReadingHistoryStore.items(context).single().lastReadAt)
            assertEquals(ComicLabMangaProgress(comic.absolutePath, 2, 3), MangaReaderActivity.savedReadingProgress(context, comic))
            assertEquals(com.example.comiclab.ebook.EbookProgress(2, 0.3f), com.example.comiclab.core.store.EbookProgressStore.load(context, ebook))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun importPreservesExistingMangaAndEbookProgressInsteadOfRollingBack() {
        val root = tempDirectory("comiclab-progress-local-wins")
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            val ebook = File(root, "novel.epub").apply { writeText("ebook") }
            val localManga = ComicLabMangaProgress(comic.absolutePath, 14, 256)
            val localEbook = com.example.comiclab.ebook.EbookProgress(8, 0.75f)
            MangaReaderActivity.restoreReadingProgress(context, comic, localManga)
            com.example.comiclab.core.store.EbookProgressStore.save(context, ebook, localEbook)

            val result = ComicLabConfigurationStore.importAndMerge(
                context,
                ComicLabConfiguration(
                    mangaReadingProgress = listOf(ComicLabMangaProgress(comic.absolutePath, 2, 0)),
                    ebookReadingProgress = listOf(ComicLabEbookProgress(ebook.absolutePath, 1, 0.2f))
                ),
                applyAppSettings = false
            )

            assertEquals(0, result.restoredEntries)
            assertEquals(2, result.skippedEntries)
            assertEquals(2, result.preservedReadingProgressEntries)
            assertEquals(localManga, MangaReaderActivity.savedReadingProgress(context, comic))
            assertEquals(localEbook, com.example.comiclab.core.store.EbookProgressStore.load(context, ebook))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun explicitFirstPageProgressAlsoWinsOverBackup() {
        val root = tempDirectory("comiclab-progress-first-page")
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            val ebook = File(root, "novel.epub").apply { writeText("ebook") }
            val localManga = ComicLabMangaProgress(comic.absolutePath, 0, 0)
            val localEbook = com.example.comiclab.ebook.EbookProgress(0, 0f)
            MangaReaderActivity.restoreReadingProgress(context, comic, localManga)
            com.example.comiclab.core.store.EbookProgressStore.save(context, ebook, localEbook)

            val result = ComicLabConfigurationStore.importAndMerge(
                context,
                ComicLabConfiguration(
                    mangaReadingProgress = listOf(ComicLabMangaProgress(comic.absolutePath, 6, 128)),
                    ebookReadingProgress = listOf(ComicLabEbookProgress(ebook.absolutePath, 4, 0.6f))
                ),
                applyAppSettings = false
            )

            assertEquals(0, result.restoredEntries)
            assertEquals(2, result.skippedEntries)
            assertEquals(2, result.preservedReadingProgressEntries)
            assertEquals(localManga, MangaReaderActivity.savedReadingProgress(context, comic))
            assertEquals(localEbook, com.example.comiclab.core.store.EbookProgressStore.load(context, ebook))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun ebookProgressAliasesKeepLastBackupEntryAndCountOneStoredItem() {
        val root = tempDirectory("comiclab-ebook-progress-alias")
        try {
            val ebook = File(root, "novel.epub").apply { writeText("ebook") }
            val alias = File(root, "./novel.epub")
            assertTrue(ebook.absolutePath != alias.absolutePath)
            assertEquals(
                com.example.comiclab.core.store.EbookProgressStore.fileIdentity(ebook),
                com.example.comiclab.core.store.EbookProgressStore.fileIdentity(alias)
            )

            val result = ComicLabConfigurationStore.importAndMerge(
                context,
                ComicLabConfiguration(ebookReadingProgress = listOf(
                    ComicLabEbookProgress(ebook.absolutePath, 1, 0.2f),
                    ComicLabEbookProgress(alias.absolutePath, 4, 0.8f)
                )),
                applyAppSettings = false
            )

            assertEquals(1, result.restoredEntries)
            assertEquals(1, result.skippedEntries)
            assertEquals(0, result.preservedReadingProgressEntries)
            assertEquals(
                com.example.comiclab.ebook.EbookProgress(4, 0.8f),
                com.example.comiclab.core.store.EbookProgressStore.load(context, ebook)
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun ebookProgressAliasesReportOnePreservedLocalConflict() {
        val root = tempDirectory("comiclab-ebook-progress-alias-conflict")
        try {
            val ebook = File(root, "novel.epub").apply { writeText("ebook") }
            val alias = File(root, "./novel.epub")
            assertTrue(ebook.absolutePath != alias.absolutePath)
            val local = com.example.comiclab.ebook.EbookProgress(7, 0.65f)
            com.example.comiclab.core.store.EbookProgressStore.save(context, ebook, local)

            val result = ComicLabConfigurationStore.importAndMerge(
                context,
                ComicLabConfiguration(ebookReadingProgress = listOf(
                    ComicLabEbookProgress(ebook.absolutePath, 1, 0.2f),
                    ComicLabEbookProgress(alias.absolutePath, 4, 0.8f)
                )),
                applyAppSettings = false
            )

            assertEquals(0, result.restoredEntries)
            assertEquals(2, result.skippedEntries)
            assertEquals(1, result.preservedReadingProgressEntries)
            assertEquals(local, com.example.comiclab.core.store.EbookProgressStore.load(context, ebook))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun olderImportedHistoryIsSkippedWhenLocalRecordWins() {
        val root = tempDirectory("comiclab-count-history")
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            ReadingHistoryStore.merge(context, listOf(ReadingHistoryStore.Item(comic, 200L, 5L, 1L)))
            val result = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
                readingHistory = listOf(ComicLabHistoryEntry(comic.absolutePath, 100L, 1L, 1L))
            ))
            assertEquals(0, result.restoredEntries)
            assertEquals(1, result.skippedEntries)
            assertEquals(200L, ReadingHistoryStore.items(context).single().lastReadAt)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun alreadyExistingFavoritesAndBookcaseSelectionsStillCountAsRetained() {
        val root = tempDirectory("comiclab-count-existing")
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            val shelf = File(root, "shelf").apply { mkdir() }
            FavoriteComicStore.record(context, comic)
            AppSettings.setBookcaseDirectory(context, shelf, true)
            val result = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
                favoriteComics = listOf(ComicLabFavoriteComic(comic.absolutePath, 10L, 5L, 1L)),
                bookcasePaths = listOf(shelf.absolutePath)
            ))
            assertEquals(2, result.restoredEntries)
            assertEquals(0, result.skippedEntries)
            assertTrue(AppSettings.isBookcaseDirectory(context, shelf))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun decodeSkipsAndDuplicateSkipsAreAccountedExactlyOnce() {
        val root = tempDirectory("comiclab-count-decode")
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            val configuration = ComicLabConfiguration(favoriteComics = List(2) { ComicLabFavoriteComic(comic.absolutePath, 1L, 5L, 1L) })
            val json = org.json.JSONObject(ComicLabConfigurationJson.encode(configuration))
            json.getJSONArray("favoriteComics").put(7)
            val decoded = ComicLabConfigurationJson.decode(json.toString())
            assertEquals(1, decoded.skippedEntries)
            val result = ComicLabConfigurationStore.importAndMerge(context, decoded.configuration, decoded.skippedEntries)
            assertEquals(1, result.restoredEntries)
            assertEquals(2, result.skippedEntries)
        } finally { root.deleteRecursively() }
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

    @Test
    fun corruptLaterStoreAbortsBeforeEarlierImportDataIsWritten() {
        val root = tempDirectory("comiclab-config-preflight")
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            val favoritePreferences = context.getSharedPreferences("favorite_comic_prefs", 0)
            context.getSharedPreferences("reading_history_prefs", 0)
                .edit().putString("reading_history", "[{broken").commit()

            assertThrows(IllegalStateException::class.java) {
                ComicLabConfigurationStore.importAndMerge(
                    context,
                    ComicLabConfiguration(
                        favoriteComics = listOf(
                            ComicLabFavoriteComic(comic.absolutePath, 1L, comic.length(), comic.lastModified())
                        ),
                        appSettings = ComicLabAppSettings(darkModeEnabled = true)
                    )
                )
            }

            assertFalse(favoritePreferences.contains("favorite_comics"))
            assertFalse(AppSettings.isDarkModeEnabled(context))
            assertEquals("[{broken", context.getSharedPreferences("reading_history_prefs", 0)
                .getString("reading_history", null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptBrightnessPreferenceAbortsBeforeEarlierImportDataIsWritten() {
        val root = tempDirectory("comiclab-config-settings-preflight")
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            val favoritePreferences = context.getSharedPreferences("favorite_comic_prefs", 0)
            context.getSharedPreferences("app_settings", 0).edit()
                .putString("custom_reader_brightness", "broken")
                .commit()

            assertThrows(ClassCastException::class.java) {
                ComicLabConfigurationStore.importAndMerge(
                    context,
                    ComicLabConfiguration(
                        favoriteComics = listOf(
                            ComicLabFavoriteComic(comic.absolutePath, 1L, comic.length(), comic.lastModified())
                        ),
                        appSettings = ComicLabAppSettings(customReaderBrightness = 100)
                    )
                )
            }

            assertFalse(favoritePreferences.contains("favorite_comics"))
            assertEquals("broken", context.getSharedPreferences("app_settings", 0)
                .getString("custom_reader_brightness", null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingOfflineFavoritesUseCapacityButDoNotCountAsImported() {
        val root = tempDirectory("comiclab-count-offline")
        try {
            val rows = org.json.JSONArray()
            repeat(100) { index -> rows.put(org.json.JSONObject()
                .put("path", File(root, "offline-$index.cbz").absolutePath)
                .put("addedAt", index + 1L).put("fileSize", 5L).put("modifiedAt", 1L)) }
            val preferences = context.getSharedPreferences("favorite_comic_prefs", 0)
            preferences.edit().putString("favorite_comics", rows.toString()).commit()
            val file = File(root, "new.cbz").apply { writeText("comic") }
            val result = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
                favoriteComics = listOf(ComicLabFavoriteComic(file.absolutePath, 1000L, 5L, 1L))
            ))
            assertEquals(0, result.restoredEntries)
            assertEquals(1, result.skippedEntries)
            assertEquals(100, org.json.JSONArray(preferences.getString("favorite_comics", null)).length())
            assertTrue(FavoriteComicStore.items(context).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun olderNewHistoryOutsideFinalWindowIsCountedAsSkipped() {
        val root = tempDirectory("comiclab-count-history-window")
        try {
            val current = (0 until 30).map { File(root, "current-$it.cbz").apply { writeText("comic") } }
            ReadingHistoryStore.merge(context, current.mapIndexed { index, f -> ReadingHistoryStore.Item(f, 1000L + index, 5L, 1L) })
            val imported = File(root, "old.cbz").apply { writeText("comic") }
            val result = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
                readingHistory = listOf(ComicLabHistoryEntry(imported.absolutePath, 1L, 5L, 1L))
            ))
            assertEquals(0, result.restoredEntries)
            assertEquals(1, result.skippedEntries)
            assertEquals(current.toSet(), ReadingHistoryStore.items(context).map { it.file }.toSet())
        } finally { root.deleteRecursively() }
    }

    private fun tempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}
