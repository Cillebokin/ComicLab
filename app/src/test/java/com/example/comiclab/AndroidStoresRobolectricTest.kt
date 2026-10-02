package com.example.comiclab

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                2,
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
    fun storesRecoverFromCorruptPersistedJsonAndRemapDirectoryMoves() {
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
            assertNull(com.example.comiclab.ebook.EbookProgressStore.load(context, file))

            com.example.comiclab.ebook.EbookProgressStore.save(
                context,
                file,
                com.example.comiclab.ebook.EbookProgress(-2, 2f)
            )
            assertEquals(
                com.example.comiclab.ebook.EbookProgress(0, 1f),
                com.example.comiclab.ebook.EbookProgressStore.load(context, file)
            )

            val key = progressKey(file)
            context.getSharedPreferences("ebook_progress_prefs", 0).edit()
                .remove("ebook_progress_${key}_fraction")
                .commit()
            assertNull(com.example.comiclab.ebook.EbookProgressStore.load(context, file))

            com.example.comiclab.ebook.EbookProgressStore.save(
                context,
                file,
                com.example.comiclab.ebook.EbookProgress(4, 0.25f)
            )
            com.example.comiclab.ebook.EbookProgressStore.clear(context, file)
            assertNull(com.example.comiclab.ebook.EbookProgressStore.load(context, file))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun progressKey(file: File): String {
        val identity = com.example.comiclab.ebook.EbookProgressStore.fileIdentity(file)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun comicFile(directory: File, name: String): File =
        File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun tempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}
