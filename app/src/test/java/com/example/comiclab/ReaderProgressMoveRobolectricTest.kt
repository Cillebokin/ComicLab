package com.example.comiclab

import com.example.comiclab.core.settings.ComicLabMangaProgress
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
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
class ReaderProgressMoveRobolectricTest {

    private val context = RuntimeEnvironment.getApplication()
    private val root: File = Files.createTempDirectory("comiclab-progress-move").toFile()

    @Before
    fun clearPreferences() {
        listOf("reader_prefs", "reading_history_prefs").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    @After
    fun cleanUp() {
        listOf("reader_prefs", "reading_history_prefs").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        root.deleteRecursively()
    }

    @Test
    fun moveDirectoryMigratesProgressForMatchingArchiveAndRemovesOldKey() {
        val oldDirectory = File(root, "old").apply { mkdirs() }
        val newDirectory = File(root, "new").apply { mkdirs() }
        val oldArchive = File(oldDirectory, "sub/book.cbz").apply { parentFile?.mkdirs() }
        oldArchive.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        ReaderProgressStore.restoreReadingProgress(
            context,
            oldArchive,
            ComicLabMangaProgress(path = oldArchive.absolutePath, position = 3, offset = 7)
        )

        // 模拟剪切：文件移动到新目录，长度保持不变。
        val newArchive = File(newDirectory, "sub/book.cbz").apply { parentFile?.mkdirs() }
        newArchive.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        oldArchive.delete()

        ReaderProgressStore.moveDirectory(context, oldDirectory, newDirectory)

        assertEquals(3 to 7, ReaderProgressStore.readPositionAndOffset(context, newArchive))
        assertFalse(
            "old progress keys should be removed after a successful migration",
            hasKeyWithPathPrefix(oldArchive.absolutePath)
        )
    }

    @Test
    fun moveDirectoryMigratesProgressWhenMtimeChangesAfterCopy() {
        val oldDirectory = File(root, "old").apply { mkdirs() }
        val newDirectory = File(root, "new").apply { mkdirs() }
        val oldArchive = File(oldDirectory, "book.cbz")
        oldArchive.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        oldArchive.setLastModified(1_000_000L)
        ReaderProgressStore.restoreReadingProgress(
            context,
            oldArchive,
            ComicLabMangaProgress(path = oldArchive.absolutePath, position = 6, offset = 2)
        )

        // 复制后 mtime 改变，旧键失效；迁移必须按新文件的 mtime 重算键。
        val newArchive = File(newDirectory, "book.cbz")
        newArchive.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        newArchive.setLastModified(2_000_000L)
        oldArchive.delete()

        ReaderProgressStore.moveDirectory(context, oldDirectory, newDirectory)

        assertEquals(6 to 2, ReaderProgressStore.readPositionAndOffset(context, newArchive))
    }

    @Test
    fun moveDirectoryKeepsProgressWhenTargetFileIsMissing() {
        val oldDirectory = File(root, "old").apply { mkdirs() }
        val newDirectory = File(root, "new").apply { mkdirs() }
        val oldArchive = File(oldDirectory, "lost.cbz")
        oldArchive.writeBytes(byteArrayOf(1, 2, 3))
        ReaderProgressStore.restoreReadingProgress(
            context,
            oldArchive,
            ComicLabMangaProgress(path = oldArchive.absolutePath, position = 1, offset = 0)
        )
        oldArchive.delete()

        // 新目录中没有对应文件：不得删除旧进度（决策 D2）。
        ReaderProgressStore.moveDirectory(context, oldDirectory, newDirectory)

        assertTrue(
            "progress for a missing target must be kept",
            hasKeyWithPathPrefix(oldArchive.absolutePath)
        )
    }

    @Test
    fun moveDirectoryKeepsOldKeyWhenTargetLengthDoesNotMatch() {
        val oldDirectory = File(root, "old").apply { mkdirs() }
        val newDirectory = File(root, "new").apply { mkdirs() }
        val oldArchive = File(oldDirectory, "book.cbz")
        oldArchive.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        ReaderProgressStore.restoreReadingProgress(
            context,
            oldArchive,
            ComicLabMangaProgress(path = oldArchive.absolutePath, position = 2, offset = 9)
        )

        // 新位置文件长度不同，无法确认是同一个文件，保留旧键（决策 D2）。
        File(newDirectory, "book.cbz").writeBytes(byteArrayOf(1, 2, 3))
        oldArchive.delete()

        ReaderProgressStore.moveDirectory(context, oldDirectory, newDirectory)

        assertTrue(
            "unmatched progress must be kept, not deleted",
            hasKeyWithPathPrefix(oldArchive.absolutePath)
        )
    }

    @Test
    fun moveFileKeepsTargetProgressWhenItIsFurtherAlong() {
        val source = File(root, "a.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val target = File(root, "b.cbz").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        ReaderProgressStore.restoreReadingProgress(
            context,
            source,
            ComicLabMangaProgress(path = source.absolutePath, position = 2, offset = 0)
        )
        ReaderProgressStore.restoreReadingProgress(
            context,
            target,
            ComicLabMangaProgress(path = target.absolutePath, position = 5, offset = 0)
        )
        source.delete()

        ReaderProgressStore.moveFile(context, source, target)

        assertEquals(5 to 0, ReaderProgressStore.readPositionAndOffset(context, target))
    }

    @Test
    fun moveFileReplacesTargetProgressWhenSourceIsFurtherAlong() {
        val source = File(root, "a.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val target = File(root, "b.cbz").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        ReaderProgressStore.restoreReadingProgress(
            context,
            source,
            ComicLabMangaProgress(path = source.absolutePath, position = 4, offset = 3)
        )
        ReaderProgressStore.restoreReadingProgress(
            context,
            target,
            ComicLabMangaProgress(path = target.absolutePath, position = 1, offset = 0)
        )
        source.delete()

        ReaderProgressStore.moveFile(context, source, target)

        assertEquals(4 to 3, ReaderProgressStore.readPositionAndOffset(context, target))
    }

    @Test
    fun moveFileUsesFurtherVerticalOffsetWhenPagePositionMatches() {
        val source = File(root, "a.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val target = File(root, "b.cbz").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        ReaderProgressStore.restoreReadingProgress(
            context,
            source,
            ComicLabMangaProgress(path = source.absolutePath, position = 4, offset = -300)
        )
        ReaderProgressStore.restoreReadingProgress(
            context,
            target,
            ComicLabMangaProgress(path = target.absolutePath, position = 4, offset = -100)
        )
        source.delete()

        ReaderProgressStore.moveFile(context, source, target)

        assertEquals(4 to -300, ReaderProgressStore.readPositionAndOffset(context, target))
    }

    @Test
    fun moveFileKeepsTargetWhenItsVerticalOffsetIsFurther() {
        val source = File(root, "a.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val target = File(root, "b.cbz").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        ReaderProgressStore.restoreReadingProgress(
            context,
            source,
            ComicLabMangaProgress(path = source.absolutePath, position = 4, offset = -100)
        )
        ReaderProgressStore.restoreReadingProgress(
            context,
            target,
            ComicLabMangaProgress(path = target.absolutePath, position = 4, offset = -300)
        )
        source.delete()

        ReaderProgressStore.moveFile(context, source, target)

        assertEquals(4 to -300, ReaderProgressStore.readPositionAndOffset(context, target))
    }

    @Test
    fun moveFileChoosesFurthestOfProgressKeysForSourceWithChangedMtime() {
        val source = File(root, "a.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        source.setLastModified(1_000_000L)
        ReaderProgressStore.restoreReadingProgress(
            context,
            source,
            ComicLabMangaProgress(path = source.absolutePath, position = 4, offset = -100)
        )
        source.setLastModified(2_000_000L)
        ReaderProgressStore.restoreReadingProgress(
            context,
            source,
            ComicLabMangaProgress(path = source.absolutePath, position = 4, offset = -300)
        )
        assertEquals(
            "different mtimes must leave distinct historical keys for migration to reconcile",
            4,
            context.getSharedPreferences("reader_prefs", 0).all.keys.count {
                it.startsWith("reader:${source.absolutePath}:")
            }
        )
        val target = File(root, "b.cbz").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        source.delete()

        ReaderProgressStore.moveFile(context, source, target)

        assertEquals(4 to -300, ReaderProgressStore.readPositionAndOffset(context, target))
        assertFalse(hasKeyWithPathPrefix(source.absolutePath))
    }

    @Test
    fun moveFileDoesNotLetPendingTargetWriteOverwriteFurtherSourceProgress() {
        val source = File(root, "a.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val target = File(root, "b.cbz").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        ReaderProgressStore.restoreReadingProgress(
            context,
            source,
            ComicLabMangaProgress(path = source.absolutePath, position = 8, offset = -50)
        )
        val pendingTargetWrite = ReaderProgressStore.prepareSave(
            context,
            target,
            position = 2,
            offset = 0
        )
        source.delete()

        ReaderProgressStore.moveFile(context, source, target)
        assertTrue(pendingTargetWrite.commit())

        assertEquals(8 to -50, ReaderProgressStore.readPositionAndOffset(context, target))
    }

    @Test
    fun moveFileRedirectsPreparedProgressWriteToNewPath() {
        val source = File(root, "a.cbz").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val target = File(root, "b.cbz").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        val preparedWrite = ReaderProgressStore.prepareSave(
            context,
            source,
            position = 7,
            offset = -240
        )
        source.delete()

        ReaderProgressStore.moveFile(context, source, target)
        preparedWrite.apply()

        assertEquals(7 to -240, ReaderProgressStore.readPositionAndOffset(context, target))
        assertFalse(hasKeyWithPathPrefix(source.absolutePath))
    }

    @Test
    fun readingHistoryMoveFileKeepsTheNewerEntryWhenTargetAlreadyExists() {
        val source = File(root, "a.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val target = File(root, "b.cbz").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        ReadingHistoryStore.merge(
            context,
            listOf(
                ReadingHistoryStore.Item(source, lastReadAt = 100L, fileSize = 3L, modifiedAt = 0L),
                ReadingHistoryStore.Item(target, lastReadAt = 200L, fileSize = 3L, modifiedAt = 0L)
            )
        )

        ReadingHistoryStore.moveFile(context, source, target)

        val items = ReadingHistoryStore.items(context)
        assertEquals(listOf(target.absolutePath), items.map { it.file.absolutePath })
        assertEquals(200L, items.single().lastReadAt)
    }

    private fun hasKeyWithPathPrefix(path: String): Boolean {
        return context.getSharedPreferences("reader_prefs", 0).all.keys.any { key ->
            key.startsWith("reader:$path:")
        }
    }
}
