package com.example.comiclab.core.store

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
class StoredCollectionsSafetyRobolectricTest(private val kind: Kind) {
    enum class Kind(val preferences: String, val key: String, val limit: Int) {
        COMICS("favorite_comic_prefs", "favorite_comics", 100),
        PATHS("favorite_path_prefs", "favorite_paths", 20),
        HISTORY("reading_history_prefs", "reading_history", 30)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun stores(): List<Array<Any>> = Kind.entries.map { arrayOf<Any>(it) }
    }

    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(kind.preferences, 0)

    @Before fun reset() { prefs.edit().clear().commit() }
    @After fun cleanUp() { prefs.edit().clear().commit() }

    private fun target(parent: File, name: String): File = when (kind) {
        Kind.PATHS -> File(parent, name).apply { check(mkdirs()) }
        else -> File(parent, "$name.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    private fun json(vararg files: File): String = JSONArray().apply {
        files.forEachIndexed { index, file ->
            put(JSONObject().put("path", file.absolutePath).put("addedAt", 100L + index)
                .put("lastReadAt", 200L + index).put("fileSize", 3L).put("modifiedAt", 400L))
        }
    }.toString()

    private fun seed(raw: String) { check(prefs.edit().putString(kind.key, raw).commit()) }
    private fun raw(): String? = prefs.getString(kind.key, null)
    private fun paths(): List<String> = when (kind) {
        Kind.COMICS -> FavoriteComicStore.items(context).map { it.file.absolutePath }
        Kind.PATHS -> FavoritePathStore.items(context).map { it.directory.absolutePath }
        Kind.HISTORY -> ReadingHistoryStore.items(context).map { it.file.absolutePath }
    }

    private fun record(file: File): String = when (kind) {
        Kind.COMICS -> FavoriteComicStore.record(context, file).name
        Kind.PATHS -> FavoritePathStore.record(context, file).name
        Kind.HISTORY -> { ReadingHistoryStore.record(context, file); "HISTORY" }
    }

    private fun merge(file: File?): Int = when (kind) {
        Kind.COMICS -> FavoriteComicStore.merge(context, listOfNotNull(file?.let {
            FavoriteComicStore.Item(it, 1000L, 3L, 400L)
        }))
        Kind.PATHS -> FavoritePathStore.merge(context, listOfNotNull(file?.let {
            FavoritePathStore.Item(it, 1000L)
        }))
        Kind.HISTORY -> ReadingHistoryStore.merge(context, listOfNotNull(file?.let {
            ReadingHistoryStore.Item(it, 1000L, 3L, 400L)
        }))
    }

    private fun remove(file: File) = when (kind) {
        Kind.COMICS -> FavoriteComicStore.remove(context, file)
        Kind.PATHS -> FavoritePathStore.remove(context, file)
        Kind.HISTORY -> ReadingHistoryStore.remove(context, file)
    }

    private fun move(from: File, to: File) = when (kind) {
        Kind.COMICS -> FavoriteComicStore.moveDirectory(context, from, to)
        Kind.PATHS -> FavoritePathStore.moveDirectory(context, from, to)
        Kind.HISTORY -> ReadingHistoryStore.moveDirectory(context, from, to)
    }

    private fun clear() = when (kind) {
        Kind.COMICS -> FavoriteComicStore.clear(context)
        Kind.PATHS -> FavoritePathStore.clear(context)
        Kind.HISTORY -> ReadingHistoryStore.clear(context)
    }

    @Test fun listReadsDoNotRewriteMixedUnavailableRecords() {
        val visible = target(temporaryFolder.root, "visible")
        val missing = File(temporaryFolder.root, "offline.cbz")
        val original = json(missing, visible)
        seed(original)
        repeat(2) {
            assertEquals(listOf(visible.absolutePath), paths())
            assertEquals(original, raw())
        }
        when (kind) {
            Kind.COMICS -> FavoriteComicStore.favoriteFilePaths(context)
            Kind.PATHS -> FavoritePathStore.favoriteDirectoryPaths(context)
            Kind.HISTORY -> Unit
        }
        assertEquals(original, raw())
    }

    @Test fun temporarilyUnavailableStorageReappearsWithOriginalMetadata() {
        val mounted = temporaryFolder.newFolder("mounted")
        val file = target(mounted, "book")
        seed(json(file))
        val original = raw()
        val before = when (kind) {
            Kind.COMICS -> FavoriteComicStore.items(context)
            Kind.PATHS -> FavoritePathStore.items(context)
            Kind.HISTORY -> ReadingHistoryStore.items(context)
        }
        val offline = File(temporaryFolder.root, "offline")
        assertTrue(mounted.renameTo(offline))
        assertTrue(paths().isEmpty())
        assertEquals(original, raw())
        assertTrue(offline.renameTo(mounted))
        val after = when (kind) {
            Kind.COMICS -> FavoriteComicStore.items(context)
            Kind.PATHS -> FavoritePathStore.items(context)
            Kind.HISTORY -> ReadingHistoryStore.items(context)
        }
        assertEquals(before, after)
        assertEquals(original, raw())
    }

    @Test fun addingAnotherItemDoesNotDiscardUnavailableRecords() {
        val missing = File(temporaryFolder.root, "offline.cbz")
        val added = target(temporaryFolder.root, "new")
        seed(json(missing))
        record(added)
        val stored = JSONArray(raw())
        assertEquals(setOf(missing.absolutePath, added.absolutePath),
            (0 until stored.length()).map { stored.getJSONObject(it).getString("path") }.toSet())
    }

    @Test fun mergingAnotherItemDoesNotDiscardUnavailableRecords() {
        val missing = File(temporaryFolder.root, "offline.cbz")
        val added = target(temporaryFolder.root, "new")
        seed(json(missing))
        assertEquals(1, merge(added))
        val stored = JSONArray(raw())
        assertEquals(setOf(missing.absolutePath, added.absolutePath),
            (0 until stored.length()).map { stored.getJSONObject(it).getString("path") }.toSet())
    }

    @Test fun unavailableFavoritesStillCountTowardExistingLimit() {
        if (kind == Kind.HISTORY) return // History retains its existing rolling window.
        val files = (0 until kind.limit).map { File(temporaryFolder.root, "offline-$it.cbz") }
        val original = json(*files.toTypedArray())
        seed(original)
        assertEquals("LIMIT_REACHED", record(target(temporaryFolder.root, "new")))
        assertEquals(original, raw())
    }

    @Test fun readsDoNotNormalizeOrTruncateStoredCollections() {
        val files = (0..kind.limit).map { target(temporaryFolder.root, "book-$it") }
        val original = json(*files.reversed().toTypedArray())
        seed(original)
        paths()
        assertEquals(original, raw())
    }

    @Test fun malformedJsonIsPreservedByEveryMutation() {
        assertCorruptDataProtected("[{\"path\":")
    }

    @Test fun malformedEntryDoesNotBecomePartialCollectionOnWrite() {
        val valid = target(temporaryFolder.root, "valid")
        assertCorruptDataProtected(JSONArray(json(valid)).put(7).toString())
    }

    @Test fun missingPathDoesNotBecomeEmptyCollectionOnWrite() {
        assertCorruptDataProtected("[{\"addedAt\":123}]")
    }

    @Test fun nonArrayJsonDoesNotBecomeEmptyCollectionOnWrite() {
        assertCorruptDataProtected("{}")
    }

    @Test fun trailingContentDoesNotGetDiscardedOnWrite() {
        assertCorruptDataProtected("[] trailing")
    }

    @Test fun nonStringPathDoesNotGetCoercedOnWrite() {
        assertCorruptDataProtected("[{\"path\":123}]")
    }

    @Test fun blankPathDoesNotBecomeEmptyCollectionOnWrite() {
        assertCorruptDataProtected("[{\"path\":\"  \"}]")
    }

    @Test fun removingOneItemKeepsUnrelatedUnavailableRecords() {
        val missing = File(temporaryFolder.root, "offline.cbz")
        val removed = target(temporaryFolder.root, "removed")
        seed(json(missing, removed))
        remove(removed)
        val stored = JSONArray(raw())
        assertEquals(1, stored.length())
        assertEquals(missing.absolutePath, stored.getJSONObject(0).getString("path"))
    }

    @Test fun movingDirectoryKeepsUnrelatedUnavailableRecords() {
        val old = temporaryFolder.newFolder("old")
        val file = target(old, "book")
        val missing = File(temporaryFolder.root, "offline.cbz")
        seed(json(missing, file))
        val new = File(temporaryFolder.root, "new")
        assertTrue(old.renameTo(new))
        move(old, new)
        val stored = JSONArray(raw())
        assertEquals(setOf(missing.absolutePath, File(new, file.name).absolutePath),
            (0 until stored.length()).map { stored.getJSONObject(it).getString("path") }.toSet())
    }

    @Test fun historyAtCapacityEvictsOnlyTheLastStoredItem() {
        if (kind != Kind.HISTORY) return
        val offline = (0 until kind.limit).map { File(temporaryFolder.root, "offline-$it.cbz") }
        seed(json(*offline.toTypedArray()))
        val added = target(temporaryFolder.root, "new")
        record(added)
        val stored = JSONArray(raw())
        assertEquals(listOf(added.absolutePath) + offline.dropLast(1).map { it.absolutePath },
            (0 until stored.length()).map { stored.getJSONObject(it).getString("path") })
    }

    @Test fun wrongPreferenceTypeIsProtectedWithoutCrashingReads() {
        check(prefs.edit().putInt(kind.key, 123).commit())
        assertTrue(paths().isEmpty())
        record(target(temporaryFolder.root, "new"))
        assertEquals(123, prefs.getInt(kind.key, 0))
    }

    private fun assertCorruptDataProtected(original: String) {
        val file = target(temporaryFolder.root, "new")
        val moved = File(temporaryFolder.root, "moved")
        seed(original)
        assertTrue(paths().isEmpty())
        assertEquals(original, raw())
        val result = record(file)
        if (kind != Kind.HISTORY) assertEquals("STORAGE_ERROR", result)
        assertEquals(original, raw())
        assertThrows(IllegalStateException::class.java) { merge(file) }
        assertEquals(original, raw())
        assertThrows(IllegalStateException::class.java) { merge(null) }
        assertEquals(original, raw())
        remove(file)
        assertEquals(original, raw())
        move(temporaryFolder.root, moved)
        assertEquals(original, raw())
        if (kind == Kind.HISTORY) {
            ReadingHistoryStore.moveFile(context, file, moved)
            assertEquals(original, raw())
        }
    }

    @Test fun missingPreferenceAndValidEmptyArrayAllowNormalWrites() {
        val file = target(temporaryFolder.root, "valid")
        for (original in listOf(null, "[]")) {
            prefs.edit().clear().commit()
            if (original != null) seed(original)
            record(file)
            assertEquals(listOf(file.absolutePath), paths())
            remove(file)
            assertTrue(paths().isEmpty())
            assertEquals(1, merge(file))
            assertEquals(listOf(file.absolutePath), paths())
        }
    }

    @Test fun explicitClearAllowsRecoveryFromCorruptData() {
        seed("broken")
        clear()
        assertFalse(prefs.contains(kind.key))
        val file = target(temporaryFolder.root, "valid")
        record(file)
        assertEquals(listOf(file.absolutePath), paths())
    }
}
