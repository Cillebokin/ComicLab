package com.example.comiclab.core.store

import com.example.comiclab.core.nas.NasCachedArchiveStore
import com.example.comiclab.core.nas.NasDirectoryEntry
import com.example.comiclab.core.nas.NasShareArchiveDownloader
import com.example.comiclab.core.nas.NasShareProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NasCacheRecoveryRobolectricTest {
    @Test
    fun restoresLegacyProgressForTheSameVersionAndKeepsUpdatedVersionSeparate() {
        val context = RuntimeEnvironment.getApplication()
        val cache = NasShareArchiveDownloader.cacheDirectory(context.cacheDir)
        val share = NasShareProfile("share", "Books", "fixture.invalid", "books", "", false)
        val entry = NasDirectoryEntry("book.cbz", "shelf\\book.cbz", false, 4, 1_700_000_000_000L)
        val file = NasShareArchiveDownloader.cacheFile(cache, share, entry)
        val legacyTimestamp = 1_710_000_000_000L
        file.parentFile!!.mkdirs()
        file.writeText("book")
        assertTrue(file.setLastModified(legacyTimestamp))
        assertTrue(ReaderProgressStore.prepareSave(context, file, 3, -27).commit())
        NasCachedArchiveStore.prepareForReading(context, file, share, entry, downloaded = false)

        assertTrue(NasShareArchiveDownloader.clearCache(context.cacheDir).completed)
        assertEquals(entry.relativePath, NasCachedArchiveStore.source(context, file)?.relativePath)
        file.parentFile!!.mkdirs()
        file.writeText("book")
        assertEquals(0 to 0, ReaderProgressStore.readPositionAndOffset(context, file))
        NasCachedArchiveStore.prepareForReading(context, file, share, entry, downloaded = true)
        assertEquals(legacyTimestamp, file.lastModified())
        assertEquals(3 to -27, ReaderProgressStore.readPositionAndOffset(context, file))

        val revision = entry.copy(modifiedAt = entry.modifiedAt + 2000)
        val revised = NasShareArchiveDownloader.cacheFile(cache, share, revision)
        revised.parentFile!!.mkdirs()
        revised.writeText("new!")
        NasCachedArchiveStore.prepareForReading(context, revised, share, revision, downloaded = true)
        assertEquals(0 to 0, ReaderProgressStore.readPositionAndOffset(context, revised))
        assertEquals(3 to -27, ReaderProgressStore.readPositionAndOffset(context, file))
    }

    @Test
    fun evictingCachedArchiveKeepsNasEntriesVisible() {
        val context = RuntimeEnvironment.getApplication()
        val cache = NasShareArchiveDownloader.cacheDirectory(context.cacheDir)
        val share = NasShareProfile("share", "Books", "fixture.invalid", "books", "", false)
        val entry = NasDirectoryEntry("book.cbz", "shelf\\book.cbz", false, 4, 1_700_000_000_000L)
        val file = NasShareArchiveDownloader.cacheFile(cache, share, entry)
        file.parentFile!!.mkdirs()
        file.writeText("book")
        assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, file))
        ReadingHistoryStore.record(context, file)

        assertTrue(NasShareArchiveDownloader.clearCache(context.cacheDir).completed)
        assertEquals(listOf(file), FavoriteComicStore.items(context).map { it.file })
        assertEquals(listOf(file), ReadingHistoryStore.items(context).map { it.file })
    }
}
