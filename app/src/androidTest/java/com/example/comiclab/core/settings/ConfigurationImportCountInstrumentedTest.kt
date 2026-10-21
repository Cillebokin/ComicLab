package com.example.comiclab.core.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.ebook.EbookProgress
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** 写入仅限 UUID 命名偏好与 cache 沙箱，真机环境就绪后再执行。 */
@RunWith(AndroidJUnit4::class)
class ConfigurationImportCountInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf("app_settings", "favorite_comic_prefs", "favorite_path_prefs",
        "reading_history_prefs", "reader_prefs", "ebook_progress_prefs")
    @get:Rule val temporaryFolder = TemporaryFolder(base.cacheDir)
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var originals: Map<String, Map<String, *>>

    @Before fun setUp() {
        prefix = "import_count_${UUID.randomUUID()}_"
        originals = names.associateWith { base.getSharedPreferences(it, 0).all }
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name in names)
                return base.getSharedPreferences(prefix + name, mode)
            }
            override fun getApplicationContext(): Context = this
        }
    }

    @After fun tearDown() {
        try {
            names.forEach { assertEquals(originals[it], base.getSharedPreferences(it, 0).all) }
        } finally {
            names.forEach { base.deleteSharedPreferences(prefix + it) }
        }
    }

    private fun file(name: String) = File(temporaryFolder.root, name).apply { writeText("fixture") }
    private fun comic(file: File, time: Long) = ComicLabFavoriteComic(file.absolutePath, time, file.length(), file.lastModified())
    private fun history(file: File, time: Long) = ComicLabHistoryEntry(file.absolutePath, time, file.length(), file.lastModified())

    @Test fun truncatedCollectionsReportOnlyRetainedEntries() {
        val files = (0..101).map { file("$it.cbz") }
        val shelves = (0..21).map { File(temporaryFolder.root, "shelf-$it").apply { mkdir() } }
        val summary = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
            favoriteComics = files.mapIndexed { index, f -> comic(f, index + 1L) },
            favoritePaths = shelves.mapIndexed { index, f -> ComicLabFavoritePath(f.absolutePath, index + 1L) },
            readingHistory = files.take(32).mapIndexed { index, f -> history(f, index + 1L) }
        ), applyAppSettings = false)
        assertEquals(150, summary.restoredEntries)
        assertEquals(6, summary.skippedEntries)
        assertEquals(summary.restoredEntries, FavoriteComicStore.items(context).size +
            FavoritePathStore.items(context).size + ReadingHistoryStore.items(context).size)
    }

    @Test fun duplicatesCountOncePerCategoryIncludingReadingProgress() {
        val comic = file("comic.cbz")
        val ebook = file("novel.epub")
        val shelf = File(temporaryFolder.root, "shelf").apply { mkdir() }
        val summary = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
            favoriteComics = listOf(comic(comic, 1L), comic(comic, 2L)),
            favoritePaths = listOf(1L, 2L).map { ComicLabFavoritePath(shelf.absolutePath, it) },
            readingHistory = listOf(history(comic, 20L), history(comic, 10L)),
            bookcasePaths = listOf(shelf.absolutePath, shelf.absolutePath),
            mangaReadingProgress = listOf(ComicLabMangaProgress(comic.absolutePath, 4, 0), ComicLabMangaProgress(comic.absolutePath, 2, 3)),
            ebookReadingProgress = listOf(ComicLabEbookProgress(ebook.absolutePath, 4, 0.7f), ComicLabEbookProgress(ebook.absolutePath, 2, 0.3f))
        ), applyAppSettings = false)
        assertEquals(6, summary.restoredEntries)
        assertEquals(6, summary.skippedEntries)
        assertEquals(20L, ReadingHistoryStore.items(context).single().lastReadAt)
        assertEquals(2 to 3, com.example.comiclab.core.store.ReaderProgressStore.readPositionAndOffset(context, comic))
        assertEquals(com.example.comiclab.ebook.EbookProgress(2, 0.3f),
            com.example.comiclab.core.store.EbookProgressStore.load(context, ebook))
    }

    @Test fun olderImportedHistoryCountsAsSkippedWithoutChangingLocalRecord() {
        val comic = file("comic.cbz")
        ReadingHistoryStore.merge(context, listOf(ReadingHistoryStore.Item(comic, 300L, comic.length(), comic.lastModified())))
        val summary = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
            readingHistory = listOf(history(comic, 100L), history(comic, 200L))
        ), applyAppSettings = false)
        assertEquals(0, summary.restoredEntries)
        assertEquals(2, summary.skippedEntries)
        assertEquals(300L, ReadingHistoryStore.items(context).single().lastReadAt)
    }

    @Test fun backupProgressKeepsExistingMangaAndEbookProgress() {
        val comic = file("comic.cbz")
        val ebook = file("novel.epub")
        val localManga = ComicLabMangaProgress(comic.absolutePath, 0, 0)
        val localEbook = EbookProgress(0, 0f)
        ReaderProgressStore.restoreReadingProgress(context, comic, localManga)
        EbookProgressStore.save(context, ebook, localEbook)

        val summary = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
            mangaReadingProgress = listOf(ComicLabMangaProgress(comic.absolutePath, 14, 256)),
            ebookReadingProgress = listOf(ComicLabEbookProgress(ebook.absolutePath, 8, 0.75f))
        ), applyAppSettings = false)

        assertEquals(0, summary.restoredEntries)
        assertEquals(2, summary.skippedEntries)
        assertEquals(2, summary.preservedReadingProgressEntries)
        assertEquals(localManga, ReaderProgressStore.savedReadingProgress(context, comic))
        assertEquals(localEbook, EbookProgressStore.load(context, ebook))
    }

    @Test fun ebookPathAliasesUseOneProgressIdentity() {
        val ebook = file("alias-novel.epub")
        val alias = File(temporaryFolder.root, "./alias-novel.epub")
        assertTrue(ebook.absolutePath != alias.absolutePath)
        assertEquals(EbookProgressStore.fileIdentity(ebook), EbookProgressStore.fileIdentity(alias))

        val summary = ComicLabConfigurationStore.importAndMerge(context, ComicLabConfiguration(
            ebookReadingProgress = listOf(
                ComicLabEbookProgress(ebook.absolutePath, 1, 0.2f),
                ComicLabEbookProgress(alias.absolutePath, 4, 0.8f)
            )
        ), applyAppSettings = false)

        assertEquals(1, summary.restoredEntries)
        assertEquals(1, summary.skippedEntries)
        assertEquals(0, summary.preservedReadingProgressEntries)
        assertEquals(EbookProgress(4, 0.8f), EbookProgressStore.load(context, ebook))
    }
}
