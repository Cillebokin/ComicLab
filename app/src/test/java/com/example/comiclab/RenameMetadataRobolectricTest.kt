package com.example.comiclab

import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.ReadingHistoryStore
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RenameMetadataRobolectricTest {

    private val context = RuntimeEnvironment.getApplication()
    private val root: File = Files.createTempDirectory("comiclab-rename-metadata").toFile()

    @Before
    fun clearPreferences() {
        listOf("favorite_comic_prefs", "reading_history_prefs").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    @After
    fun cleanUp() {
        listOf("favorite_comic_prefs", "reading_history_prefs").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        root.deleteRecursively()
    }

    @Test
    fun renamingDirectoryRemapsFavoriteChildrenAndLeavesOutsideItemsAlone() {
        val oldDirectory = File(root, "series").apply { mkdirs() }
        val nested = File(oldDirectory, "vol").apply { mkdirs() }
        val child = File(nested, "v1.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outside = File(root, "outside.cbz").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, child))
        assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, outside))

        // 对应 renameDirectory：先在磁盘上重命名，再迁移元数据。
        val newDirectory = File(root, "renamed-series")
        assertTrue(oldDirectory.renameTo(newDirectory))
        FavoriteComicStore.moveDirectory(context, oldDirectory, newDirectory)

        val expectedChild = File(newDirectory, "vol/v1.cbz").absolutePath
        val paths = FavoriteComicStore.items(context).map { it.file.absolutePath }.toSet()
        assertEquals(setOf(expectedChild, outside.absolutePath), paths)
    }

    @Test
    fun renamingDirectoryRemapsReadingHistoryChildrenAndKeepsLastReadTime() {
        val oldDirectory = File(root, "series").apply { mkdirs() }
        val child = File(oldDirectory, "v2.cbz").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        ReadingHistoryStore.merge(
            context,
            listOf(ReadingHistoryStore.Item(child, lastReadAt = 555L, fileSize = 3L, modifiedAt = 0L))
        )

        val newDirectory = File(root, "renamed-series")
        assertTrue(oldDirectory.renameTo(newDirectory))
        ReadingHistoryStore.moveDirectory(context, oldDirectory, newDirectory)

        val items = ReadingHistoryStore.items(context)
        assertEquals(listOf(File(newDirectory, "v2.cbz").absolutePath), items.map { it.file.absolutePath })
        assertEquals(555L, items.single().lastReadAt)
    }

    @Test
    fun renamingDirectoryKeepsNewerHistoryWhenTwoEntriesCollapseOntoOnePath() {
        val oldDirectory = File(root, "series").apply { mkdirs() }
        val newDirectory = File(root, "renamed-series").apply { mkdirs() }
        val oldChild = File(oldDirectory, "v3.cbz").apply { writeBytes(byteArrayOf(1)) }
        val staleChild = File(newDirectory, "v3.cbz").apply { writeBytes(byteArrayOf(1)) }
        ReadingHistoryStore.merge(
            context,
            listOf(
                ReadingHistoryStore.Item(oldChild, lastReadAt = 100L, fileSize = 1L, modifiedAt = 0L),
                ReadingHistoryStore.Item(staleChild, lastReadAt = 900L, fileSize = 1L, modifiedAt = 0L)
            )
        )

        // 旧路径与新路径的记录映射到同一文件时，保留阅读时间较新的一条（决策 D3）。
        ReadingHistoryStore.moveDirectory(context, oldDirectory, newDirectory)

        val items = ReadingHistoryStore.items(context)
        assertEquals(1, items.size)
        assertEquals(900L, items.single().lastReadAt)
        assertEquals(staleChild.absolutePath, items.single().file.absolutePath)
    }
}
