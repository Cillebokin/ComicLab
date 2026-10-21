package com.example.comiclab.core.store

import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.ReadingHistoryStore
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class AndroidStoresRobolectricTest {

    private val context = RuntimeEnvironment.getApplication()
    private val preferenceNames = listOf(
        "favorite_comic_prefs",
        "favorite_path_prefs",
        "reading_history_prefs",
        "ebook_progress_prefs"
    )

    @Before
    fun clearPreferences() {
        preferenceNames.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    @After
    fun clearTemporaryPreferences() {
        preferenceNames.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    @Test
    fun favoriteComicStoreReadsLegacyJsonAndKeepsThePreferenceContractAcrossUpdates() {
        val root = tempDirectory("comiclab-favorite-comics-legacy")
        try {
            val oldDirectory = File(root, "old").apply { mkdirs() }
            val oldComic = comicFile(oldDirectory, "old.cbz")
            val newerComic = comicFile(root, "newer.cbz")
            val addedComic = comicFile(root, "added.cbz")
            val preferences = context.getSharedPreferences("favorite_comic_prefs", 0)
            val legacyRows = JSONArray()
                .put(
                    JSONObject()
                        .put("path", newerComic.absolutePath)
                        .put("addedAt", 20L)
                        .put("fileSize", 202L)
                        .put("modifiedAt", 2002L)
                )
                .put(
                    JSONObject()
                        .put("path", oldComic.absolutePath)
                        .put("addedAt", 10L)
                        .put("fileSize", 101L)
                        .put("modifiedAt", 1001L)
                )
            assertTrue(preferences.edit().putString("favorite_comics", legacyRows.toString()).commit())

            val loaded = FavoriteComicStore.items(context)
            assertEquals(listOf(oldComic, newerComic), loaded.map { it.file })
            assertEquals(10L, loaded.first().addedAt)
            assertEquals(101L, loaded.first().fileSize)
            assertEquals(1001L, loaded.first().modifiedAt)
            assertEquals(setOf("favorite_comics"), preferences.all.keys)

            assertEquals(legacyRows.toString(), preferences.getString("favorite_comics", null))
            val storedRows = JSONArray(preferences.getString("favorite_comics", null))
            assertEquals(newerComic.absolutePath, storedRows.getJSONObject(0).getString("path"))
            assertEquals(
                setOf("path", "addedAt", "fileSize", "modifiedAt"),
                storedRows.getJSONObject(0).keys().asSequence().toSet()
            )
            assertEquals(
                FavoriteComicStore.RecordResult.ALREADY_EXISTS,
                FavoriteComicStore.record(context, oldComic)
            )
            assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, addedComic))
            assertEquals(listOf(oldComic, newerComic, addedComic), FavoriteComicStore.items(context).map { it.file })

            FavoriteComicStore.remove(context, newerComic)
            assertEquals(listOf(oldComic, addedComic), FavoriteComicStore.items(context).map { it.file })
            assertEquals(setOf("favorite_comics"), preferences.all.keys)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun favoritePathStoreReadsLegacyJsonRemapsCollisionsAndRemovesTheLastEntry() {
        val root = tempDirectory("comiclab-favorite-paths-legacy")
        try {
            val oldDirectory = File(root, "old").apply { mkdirs() }
            val newDirectory = File(root, "new").apply { mkdirs() }
            val oldShelf = File(oldDirectory, "shelf").apply { mkdirs() }
            val newShelf = File(newDirectory, "shelf").apply { mkdirs() }
            val unrelatedShelf = File(root, "unrelated").apply { mkdirs() }
            val preferences = context.getSharedPreferences("favorite_path_prefs", 0)
            val legacyRows = JSONArray()
                .put(JSONObject().put("path", unrelatedShelf.absolutePath).put("addedAt", 30L))
                .put(JSONObject().put("path", oldShelf.absolutePath).put("addedAt", 20L))
                .put(JSONObject().put("path", newShelf.absolutePath).put("addedAt", 10L))
            assertTrue(preferences.edit().putString("favorite_paths", legacyRows.toString()).commit())

            val loaded = FavoritePathStore.items(context)
            assertEquals(listOf(newShelf, oldShelf, unrelatedShelf), loaded.map { it.directory })
            assertEquals(listOf(10L, 20L, 30L), loaded.map { it.addedAt })
            assertEquals(setOf("favorite_paths"), preferences.all.keys)

            FavoritePathStore.moveDirectory(context, oldDirectory, newDirectory)
            val remapped = FavoritePathStore.items(context)
            assertEquals(listOf(newShelf, unrelatedShelf), remapped.map { it.directory })
            assertEquals(10L, remapped.first().addedAt)
            assertEquals(1, remapped.count { it.directory.absolutePath == newShelf.absolutePath })
            assertEquals(
                setOf("path", "addedAt"),
                JSONArray(preferences.getString("favorite_paths", null))
                    .getJSONObject(0).keys().asSequence().toSet()
            )

            FavoritePathStore.remove(context, newShelf)
            assertEquals(listOf(unrelatedShelf), FavoritePathStore.items(context).map { it.directory })
            FavoritePathStore.remove(context, unrelatedShelf)
            assertTrue(FavoritePathStore.items(context).isEmpty())
            assertFalse(preferences.contains("favorite_paths"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readingHistoryStoreReadsLegacyJsonRemapsCollisionsAndRemovesEntries() {
        val root = tempDirectory("comiclab-reading-history-legacy")
        try {
            val oldDirectory = File(root, "old").apply { mkdirs() }
            val newDirectory = File(root, "new").apply { mkdirs() }
            val oldComic = comicFile(oldDirectory, "chapter.cbz")
            val newComic = comicFile(newDirectory, "chapter.cbz")
            val otherComic = comicFile(root, "other.cbz")
            val preferences = context.getSharedPreferences("reading_history_prefs", 0)
            val legacyRows = JSONArray()
                .put(
                    JSONObject()
                        .put("path", newComic.absolutePath)
                        .put("lastReadAt", 30L)
                        .put("fileSize", 303L)
                        .put("modifiedAt", 3003L)
                )
                .put(
                    JSONObject()
                        .put("path", otherComic.absolutePath)
                        .put("lastReadAt", 20L)
                        .put("fileSize", 202L)
                        .put("modifiedAt", 2002L)
                )
                .put(
                    JSONObject()
                        .put("path", oldComic.absolutePath)
                        .put("lastReadAt", 10L)
                        .put("fileSize", 101L)
                        .put("modifiedAt", 1001L)
                )
            assertTrue(preferences.edit().putString("reading_history", legacyRows.toString()).commit())

            val loaded = ReadingHistoryStore.items(context)
            assertEquals(listOf(newComic, otherComic, oldComic), loaded.map { it.file })
            assertEquals(303L, loaded.first().fileSize)
            assertEquals(3003L, loaded.first().modifiedAt)
            assertEquals(setOf("reading_history"), preferences.all.keys)

            ReadingHistoryStore.record(context, otherComic)
            assertEquals(3, ReadingHistoryStore.items(context).size)
            assertEquals(otherComic, ReadingHistoryStore.items(context).first().file)
            assertEquals(1, ReadingHistoryStore.items(context).count { it.file == otherComic })

            ReadingHistoryStore.moveDirectory(context, oldDirectory, newDirectory)
            val remapped = ReadingHistoryStore.items(context)
            assertEquals(listOf(otherComic, newComic), remapped.map { it.file })
            assertEquals(30L, remapped.last().lastReadAt)
            assertEquals(
                setOf("path", "lastReadAt", "fileSize", "modifiedAt"),
                JSONArray(preferences.getString("reading_history", null))
                    .getJSONObject(0).keys().asSequence().toSet()
            )

            ReadingHistoryStore.remove(context, newComic)
            assertEquals(listOf(otherComic), ReadingHistoryStore.items(context).map { it.file })
            ReadingHistoryStore.remove(context, otherComic)
            assertTrue(ReadingHistoryStore.items(context).isEmpty())
            assertFalse(preferences.contains("reading_history"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readingHistoryStoreCapsLegacyEntriesWhenWriting() {
        val root = tempDirectory("comiclab-reading-history-limit")
        try {
            val legacyFiles = (0 until 30).map { index -> comicFile(root, "legacy-$index.cbz") }
            val legacyRows = JSONArray()
            legacyFiles.forEachIndexed { index, file ->
                legacyRows.put(
                    JSONObject()
                        .put("path", file.absolutePath)
                        .put("lastReadAt", (30 - index).toLong())
                        .put("fileSize", file.length())
                        .put("modifiedAt", file.lastModified())
                )
            }
            val preferences = context.getSharedPreferences("reading_history_prefs", 0)
            assertTrue(preferences.edit().putString("reading_history", legacyRows.toString()).commit())

            assertEquals(legacyFiles, ReadingHistoryStore.items(context).map { it.file })
            val newest = comicFile(root, "newest.cbz")
            ReadingHistoryStore.record(context, newest)

            val updated = ReadingHistoryStore.items(context)
            assertEquals(30, updated.size)
            assertEquals(newest, updated.first().file)
            assertEquals(legacyFiles.take(29), updated.drop(1).map { it.file })
            val persistedRows = JSONArray(preferences.getString("reading_history", null))
            assertEquals(30, persistedRows.length())
            assertEquals("reading_history", preferences.all.keys.single())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun favoriteComicStoreValidatesDeduplicatesLimitsAndPrunesMissingFiles() {
        val root = tempDirectory("comiclab-favorite-comics")
        try {
            val directory = File(root, "not-a-comic").apply { mkdirs() }
            val unsupported = File(root, "notes.bin").apply { writeText("x") }
            assertEquals(FavoriteComicStore.RecordResult.INVALID, FavoriteComicStore.record(context, directory))
            assertEquals(FavoriteComicStore.RecordResult.INVALID, FavoriteComicStore.record(context, unsupported))

            val first = comicFile(root, "first.cbz")
            assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, first))
            assertEquals(FavoriteComicStore.RecordResult.ALREADY_EXISTS, FavoriteComicStore.record(context, first))
            assertTrue(FavoriteComicStore.isFavorite(context, first))

            val remaining = (2..100).map { comicFile(root, "comic-$it.cbz") }
            remaining.forEach { assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, it)) }
            val overflow = comicFile(root, "overflow.cbz")
            assertEquals(FavoriteComicStore.RecordResult.LIMIT_REACHED, FavoriteComicStore.record(context, overflow))
            assertEquals(100, FavoriteComicStore.items(context).size)

            assertTrue(first.delete())
            assertFalse(FavoriteComicStore.isFavorite(context, first))
            assertEquals(99, FavoriteComicStore.items(context).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun favoritePathStoreValidatesDeduplicatesLimitsAndPrunesMissingDirectories() {
        val root = tempDirectory("comiclab-favorite-paths")
        try {
            val file = File(root, "file.cbz").apply { writeText("x") }
            assertEquals(FavoritePathStore.RecordResult.INVALID, FavoritePathStore.record(context, file))

            val first = File(root, "folder-1").apply { mkdirs() }
            assertEquals(FavoritePathStore.RecordResult.ADDED, FavoritePathStore.record(context, first))
            assertEquals(FavoritePathStore.RecordResult.ALREADY_EXISTS, FavoritePathStore.record(context, first))

            val remaining = (2..20).map { File(root, "folder-$it").apply { mkdirs() } }
            remaining.forEach {
                assertEquals(FavoritePathStore.RecordResult.ADDED, FavoritePathStore.record(context, it))
            }
            val overflow = File(root, "folder-overflow").apply { mkdirs() }
            assertEquals(FavoritePathStore.RecordResult.LIMIT_REACHED, FavoritePathStore.record(context, overflow))
            assertEquals(20, FavoritePathStore.items(context).size)

            assertTrue(first.delete())
            assertFalse(FavoritePathStore.isFavorite(context, first))
            assertEquals(19, FavoritePathStore.items(context).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readingHistoryRecordsNewestFirstMergesByReadTimeAndPrunesMissingFiles() {
        val root = tempDirectory("comiclab-reading-history")
        try {
            val older = comicFile(root, "older.cbz")
            val newer = comicFile(root, "newer.cbz")
            val unsupported = File(root, "unsupported.bin").apply { writeText("x") }

            ReadingHistoryStore.record(context, older)
            ReadingHistoryStore.record(context, newer)
            ReadingHistoryStore.record(context, unsupported)
            assertEquals(listOf(newer, older), ReadingHistoryStore.items(context).map { it.file })

            assertEquals(
                1, // 较旧的 older 导入版本未采用，只有 newer 的导入版本保留。
                ReadingHistoryStore.merge(
                    context,
                    listOf(
                        ReadingHistoryStore.Item(older, lastReadAt = 1L, fileSize = 0L, modifiedAt = 0L),
                        ReadingHistoryStore.Item(newer, lastReadAt = Long.MAX_VALUE, fileSize = 0L, modifiedAt = 0L)
                    )
                )
            )
            val merged = ReadingHistoryStore.items(context)
            assertEquals(listOf(newer, older), merged.map { it.file })
            assertEquals(Long.MAX_VALUE, merged.first().lastReadAt)
            assertEquals(newer.length(), merged.first().fileSize)

            assertTrue(newer.delete())
            assertEquals(listOf(older), ReadingHistoryStore.items(context).map { it.file })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storesRequireExplicitClearToRecoverFromCorruptJsonAndThenRemapMoves() {
        val root = tempDirectory("comiclab-store-recovery")
        try {
            val oldDirectory = File(root, "old").apply { mkdirs() }
            val oldFile = comicFile(oldDirectory, "book.cbz")
            val newDirectory = File(root, "new").apply { mkdirs() }
            val newFile = comicFile(newDirectory, "book.cbz")

            context.getSharedPreferences("favorite_comic_prefs", 0)
                .edit().putString("favorite_comics", "{broken").commit()
            context.getSharedPreferences("favorite_path_prefs", 0)
                .edit().putString("favorite_paths", "{broken").commit()
            context.getSharedPreferences("reading_history_prefs", 0)
                .edit().putString("reading_history", "{broken").commit()

            assertTrue(FavoriteComicStore.items(context).isEmpty())
            assertTrue(FavoritePathStore.items(context).isEmpty())
            assertTrue(ReadingHistoryStore.items(context).isEmpty())

            assertThrows(IllegalStateException::class.java) {
                FavoriteComicStore.merge(context, listOf(FavoriteComicStore.Item(oldFile, 1L, 0L, 0L)))
            }
            assertThrows(IllegalStateException::class.java) {
                FavoritePathStore.merge(context, listOf(FavoritePathStore.Item(oldDirectory, 2L)))
            }
            assertThrows(IllegalStateException::class.java) {
                ReadingHistoryStore.merge(context, listOf(ReadingHistoryStore.Item(oldFile, 3L, 0L, 0L)))
            }
            assertEquals("{broken", context.getSharedPreferences("favorite_comic_prefs", 0).getString("favorite_comics", null))
            assertEquals("{broken", context.getSharedPreferences("favorite_path_prefs", 0).getString("favorite_paths", null))
            assertEquals("{broken", context.getSharedPreferences("reading_history_prefs", 0).getString("reading_history", null))
            FavoriteComicStore.clear(context)
            FavoritePathStore.clear(context)
            ReadingHistoryStore.clear(context)

            FavoriteComicStore.merge(context, listOf(FavoriteComicStore.Item(oldFile, 1L, 0L, 0L)))
            FavoritePathStore.merge(context, listOf(FavoritePathStore.Item(oldDirectory, 2L)))
            ReadingHistoryStore.merge(context, listOf(ReadingHistoryStore.Item(oldFile, 3L, 0L, 0L)))

            FavoriteComicStore.moveDirectory(context, oldDirectory, newDirectory)
            FavoritePathStore.moveDirectory(context, oldDirectory, newDirectory)
            ReadingHistoryStore.moveDirectory(context, oldDirectory, newDirectory)

            assertEquals(newFile.absolutePath, FavoriteComicStore.items(context).single().file.absolutePath)
            assertEquals(newDirectory.absolutePath, FavoritePathStore.items(context).single().directory.absolutePath)
            assertEquals(newFile.absolutePath, ReadingHistoryStore.items(context).single().file.absolutePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directoryMoveCollisionsKeepTheCorrectStoreSpecificRecord() {
        val root = tempDirectory("comiclab-store-move-collision")
        try {
            val oldDirectory = File(root, "old").apply { mkdirs() }
            val newDirectory = File(root, "new").apply { mkdirs() }
            val oldShelf = File(oldDirectory, "shelf").apply { mkdirs() }
            val newShelf = File(newDirectory, "shelf").apply { mkdirs() }
            val oldComic = comicFile(oldDirectory, "book.cbz")
            val newComic = comicFile(newDirectory, "book.cbz")

            FavoriteComicStore.merge(
                context,
                listOf(
                    FavoriteComicStore.Item(oldComic, addedAt = 20L, fileSize = oldComic.length(), modifiedAt = 20L),
                    FavoriteComicStore.Item(newComic, addedAt = 10L, fileSize = newComic.length(), modifiedAt = 10L)
                )
            )
            FavoritePathStore.merge(
                context,
                listOf(FavoritePathStore.Item(oldShelf, 20L), FavoritePathStore.Item(newShelf, 10L))
            )
            ReadingHistoryStore.merge(
                context,
                listOf(
                    ReadingHistoryStore.Item(oldComic, lastReadAt = 20L, fileSize = oldComic.length(), modifiedAt = 20L),
                    ReadingHistoryStore.Item(newComic, lastReadAt = 30L, fileSize = newComic.length(), modifiedAt = 30L)
                )
            )

            FavoriteComicStore.moveDirectory(context, oldDirectory, newDirectory)
            FavoritePathStore.moveDirectory(context, oldDirectory, newDirectory)
            ReadingHistoryStore.moveDirectory(context, oldDirectory, newDirectory)

            assertEquals(1, FavoriteComicStore.items(context).size)
            assertEquals(10L, FavoriteComicStore.items(context).single().addedAt)
            assertEquals(1, FavoritePathStore.items(context).size)
            assertEquals(10L, FavoritePathStore.items(context).single().addedAt)
            assertEquals(1, ReadingHistoryStore.items(context).size)
            assertEquals(30L, ReadingHistoryStore.items(context).single().lastReadAt)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ebookProgressPersistsNormalizedValuesAndRequiresBothStoredFields() {
        val root = tempDirectory("comiclab-ebook-progress")
        try {
            val file = File(root, "book.epub").apply { writeText("epub") }
            assertNull(com.example.comiclab.core.store.EbookProgressStore.load(context, file))

            com.example.comiclab.core.store.EbookProgressStore.save(
                context,
                file,
                com.example.comiclab.ebook.EbookProgress(-2, 2f)
            )
            assertEquals(
                com.example.comiclab.ebook.EbookProgress(0, 1f),
                com.example.comiclab.core.store.EbookProgressStore.load(context, file)
            )

            val key = progressKey(file)
            context.getSharedPreferences("ebook_progress_prefs", 0).edit()
                .remove("ebook_progress_${key}_fraction")
                .commit()
            assertNull(com.example.comiclab.core.store.EbookProgressStore.load(context, file))

            com.example.comiclab.core.store.EbookProgressStore.save(
                context,
                file,
                com.example.comiclab.ebook.EbookProgress(4, 0.25f)
            )
            com.example.comiclab.core.store.EbookProgressStore.clear(context, file)
            assertNull(com.example.comiclab.core.store.EbookProgressStore.load(context, file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ebookProgressPreservesLegacyPerFileKeysAndClearsOnlyThatFile() {
        val root = tempDirectory("comiclab-ebook-progress-legacy")
        try {
            val file = File(root, "book.epub").apply { writeText("epub") }
            val otherFile = File(root, "other.epub").apply { writeText("other epub") }
            val preferences = context.getSharedPreferences("ebook_progress_prefs", 0)
            val key = progressKey(file)
            val otherKey = progressKey(otherFile)
            val fileChapterKey = "ebook_progress_${key}_chapter"
            val fileFractionKey = "ebook_progress_${key}_fraction"
            val otherChapterKey = "ebook_progress_${otherKey}_chapter"
            val otherFractionKey = "ebook_progress_${otherKey}_fraction"

            assertTrue(
                preferences.edit()
                    .putInt(fileChapterKey, 7)
                    .putFloat(fileFractionKey, 0.75f)
                    .putInt(otherChapterKey, 3)
                    .putFloat(otherFractionKey, 0.4f)
                    .commit()
            )
            assertEquals(
                com.example.comiclab.ebook.EbookProgress(7, 0.75f),
                com.example.comiclab.core.store.EbookProgressStore.load(context, file)
            )

            com.example.comiclab.core.store.EbookProgressStore.save(
                context,
                file,
                com.example.comiclab.ebook.EbookProgress(-4, 1.5f)
            )
            assertEquals(
                com.example.comiclab.ebook.EbookProgress(0, 1f),
                com.example.comiclab.core.store.EbookProgressStore.load(context, file)
            )
            assertEquals(setOf(fileChapterKey, fileFractionKey, otherChapterKey, otherFractionKey), preferences.all.keys)
            assertEquals(3, preferences.getInt(otherChapterKey, -1))
            assertEquals(0.4f, preferences.getFloat(otherFractionKey, -1f))

            com.example.comiclab.core.store.EbookProgressStore.clear(context, file)
            assertNull(com.example.comiclab.core.store.EbookProgressStore.load(context, file))
            assertEquals(setOf(otherChapterKey, otherFractionKey), preferences.all.keys)
            assertEquals(3, preferences.getInt(otherChapterKey, -1))
            assertEquals(0.4f, preferences.getFloat(otherFractionKey, -1f))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun progressKey(file: File): String {
        val identity = com.example.comiclab.core.store.EbookProgressStore.fileIdentity(file)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun comicFile(directory: File, name: String): File =
        File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun tempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}
