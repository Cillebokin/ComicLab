package com.example.comiclab.core.store

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 所有写入使用 UUID 前缀偏好与 cache 沙箱，不写真实收藏和历史。 */
@RunWith(AndroidJUnit4::class)
class StoredCollectionsSafetyDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = listOf("favorite_comic_prefs", "favorite_path_prefs", "reading_history_prefs")
    private lateinit var prefix: String
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var originals: Map<String, Map<String, *>>

    @Before fun setUp() {
        originals = names.associateWith { base.getSharedPreferences(it, 0).all }
        prefix = "store_safety_${UUID.randomUUID()}_"
        root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
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
            check(root.parentFile?.canonicalFile == base.cacheDir.canonicalFile)
            check(root.name == prefix)
            root.deleteRecursively()
        }
    }

    private fun exercise(index: Int) {
        val preferences = context.getSharedPreferences(names[index], 0)
        val key = listOf("favorite_comics", "favorite_paths", "reading_history")[index]
        val mounted = File(root, "mounted").apply { check(mkdirs()) }
        val file = File(mounted, if (index == 1) "shelf" else "book.cbz")
        if (index == 1) check(file.mkdirs()) else file.writeBytes(byteArrayOf(1, 2, 3))
        fun paths(): List<String> = when (index) {
            0 -> FavoriteComicStore.items(context).map { it.file.absolutePath }
            1 -> FavoritePathStore.items(context).map { it.directory.absolutePath }
            else -> ReadingHistoryStore.items(context).map { it.file.absolutePath }
        }
        fun record(): String = when (index) {
            0 -> FavoriteComicStore.record(context, file).name
            1 -> FavoritePathStore.record(context, file).name
            else -> { ReadingHistoryStore.record(context, file); "HISTORY" }
        }
        fun merge() = when (index) {
            0 -> FavoriteComicStore.merge(context, listOf(FavoriteComicStore.Item(file, 123L, 3L, 456L)))
            1 -> FavoritePathStore.merge(context, listOf(FavoritePathStore.Item(file, 123L)))
            else -> ReadingHistoryStore.merge(context, listOf(ReadingHistoryStore.Item(file, 123L, 3L, 456L)))
        }
        fun remove() = when (index) {
            0 -> FavoriteComicStore.remove(context, file)
            1 -> FavoritePathStore.remove(context, file)
            else -> ReadingHistoryStore.remove(context, file)
        }
        fun move() = when (index) {
            0 -> FavoriteComicStore.moveDirectory(context, mounted, File(root, "new"))
            1 -> FavoritePathStore.moveDirectory(context, mounted, File(root, "new"))
            else -> ReadingHistoryStore.moveFile(context, file, File(root, "new.cbz"))
        }
        fun clear() = when (index) {
            0 -> FavoriteComicStore.clear(context)
            1 -> FavoritePathStore.clear(context)
            else -> ReadingHistoryStore.clear(context)
        }
        val original = JSONArray().put(JSONObject().put("path", file.absolutePath)
            .put("addedAt", 123L).put("lastReadAt", 123L).put("fileSize", 3L).put("modifiedAt", 456L)).toString()
        check(preferences.edit().putString(key, original).commit())
        assertEquals(listOf(file.absolutePath), paths())
        val offline = File(root, "offline")
        assertTrue(mounted.renameTo(offline))
        repeat(2) {
            assertTrue(paths().isEmpty())
            assertEquals(original, preferences.getString(key, null))
        }
        assertTrue(offline.renameTo(mounted))
        assertEquals(listOf(file.absolutePath), paths())
        assertEquals(original, preferences.getString(key, null))

        for (corrupt in listOf("[{\"path\":", "[7]", "[{\"addedAt\":1}]", "[] trailing", "{}")) {
            check(preferences.edit().putString(key, corrupt).commit())
            assertTrue(paths().isEmpty())
            assertEquals(corrupt, preferences.getString(key, null))
            val result = record()
            if (index != 2) assertEquals("STORAGE_ERROR", result)
            assertEquals(corrupt, preferences.getString(key, null))
            assertThrows(IllegalStateException::class.java) { merge() }
            assertEquals(corrupt, preferences.getString(key, null))
            remove()
            assertEquals(corrupt, preferences.getString(key, null))
            move()
            assertEquals(corrupt, preferences.getString(key, null))
        }
        clear()
        assertFalse(preferences.contains(key))
        record()
        assertEquals(listOf(file.absolutePath), paths())
        remove()
        assertTrue(paths().isEmpty())
        assertEquals(1, merge())
        assertEquals(listOf(file.absolutePath), paths())
    }

    @Test fun favoriteComicsProtectUnavailableAndCorruptRecords() = exercise(0)
    @Test fun favoritePathsProtectUnavailableAndCorruptRecords() = exercise(1)
    @Test fun readingHistoryProtectsUnavailableAndCorruptRecords() = exercise(2)
}
