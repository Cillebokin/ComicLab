package com.example.comiclab.core.store

import android.content.SharedPreferences
import com.example.comiclab.core.settings.ComicLabMangaProgress
import java.io.File
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderProgressStoreRobolectricTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferencesBeforeTest() {
        context.getSharedPreferences(PREFS_NAME, 0).edit().clear().commit()
    }

    @After
    fun clearPreferencesAfterTest() {
        context.getSharedPreferences(PREFS_NAME, 0).edit().clear().commit()
    }

    @Test
    fun progressCanBeRestoredReadCountedAndCleared() {
        val file = temporaryFolder.newFile("progress.cbz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        prefs.edit().clear().commit()

        assertNull(ReaderProgressStore.savedReadingProgress(context, file))
        ReaderProgressStore.restoreReadingProgress(
            context,
            file,
            ComicLabMangaProgress(file.absolutePath, position = -4, offset = 23)
        )

        assertEquals(
            ComicLabMangaProgress(file.absolutePath, position = 0, offset = 23),
            ReaderProgressStore.savedReadingProgress(context, file)
        )
        assertTrue(ReaderProgressStore.hasSavedReadingProgress(context, file))
        assertEquals(1, ReaderProgressStore.savedReadingPageCount(context, file, totalCount = 8))
        assertEquals(0, ReaderProgressStore.savedReadingPageCount(context, file, totalCount = 0))

        ReaderProgressStore.clearSavedReadingProgress(context, file)

        assertNull(ReaderProgressStore.savedReadingProgress(context, file))
        assertFalse(ReaderProgressStore.hasSavedReadingProgress(context, file))
        assertEquals(0, ReaderProgressStore.savedReadingPageCount(context, file, totalCount = 8))
    }

    @Test
    fun progressRequiresBothStoredFieldsAndClampsPageCount() {
        val file = temporaryFolder.newFile("partial.cbz").apply { writeBytes(byteArrayOf(1, 2)) }
        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        prefs.edit().clear().putInt(positionKey(file), 14).commit()

        assertNull(ReaderProgressStore.savedReadingProgress(context, file))
        assertTrue(ReaderProgressStore.hasSavedReadingProgress(context, file))
        assertEquals(8, ReaderProgressStore.savedReadingPageCount(context, file, totalCount = 8))

        prefs.edit().putInt(offsetKey(file), -3).commit()
        assertEquals(
            ComicLabMangaProgress(file.absolutePath, position = 14, offset = -3),
            ReaderProgressStore.savedReadingProgress(context, file)
        )
    }

    @Test
    fun fileRenameModificationAndLengthChangesUseDifferentProgressKeys() {
        val file = temporaryFolder.newFile("before.cbz").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(1_600_000_000_000L)
        }
        context.getSharedPreferences(PREFS_NAME, 0).edit().clear().commit()
        ReaderProgressStore.restoreReadingProgress(
            context,
            file,
            ComicLabMangaProgress(file.absolutePath, position = 5, offset = 7)
        )

        val moved = File(temporaryFolder.root, "after.cbz")
        assertTrue(file.renameTo(moved))
        assertNull(ReaderProgressStore.savedReadingProgress(context, moved))

        ReaderProgressStore.restoreReadingProgress(
            context,
            moved,
            ComicLabMangaProgress(moved.absolutePath, position = 2, offset = 9)
        )
        val originalModified = moved.lastModified()
        moved.appendBytes(byteArrayOf(4))
        moved.setLastModified(originalModified)
        assertNull(ReaderProgressStore.savedReadingProgress(context, moved))

        ReaderProgressStore.restoreReadingProgress(
            context,
            moved,
            ComicLabMangaProgress(moved.absolutePath, position = 6, offset = 11)
        )
        moved.setLastModified(originalModified + 60_000L)
        assertNull(ReaderProgressStore.savedReadingProgress(context, moved))
    }

    @Test
    fun preparedWriteCapturesKeyBeforeDeferredQueueExecution() {
        val file = temporaryFolder.newFile("queued.cbz").apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(1_600_000_000_000L)
        }
        val preferences = context.getSharedPreferences(PREFS_NAME, 0)
        preferences.edit().clear().commit()
        val capturedPositionKey = positionKey(file)
        val capturedOffsetKey = offsetKey(file)
        val preparedWrite = ReaderProgressStore.prepareSave(context, file, position = 7, offset = 29)
        val queuedTasks = mutableListOf<() -> Unit>()
        queuedTasks += { preparedWrite.apply() }

        file.appendBytes(byteArrayOf(4, 5))
        file.setLastModified(1_600_000_060_000L)
        assertNull(ReaderProgressStore.savedReadingProgress(context, file))

        queuedTasks.removeAt(0).invoke()

        assertEquals(7, preferences.getInt(capturedPositionKey, -1))
        assertEquals(29, preferences.getInt(capturedOffsetKey, -1))
        assertNull(ReaderProgressStore.savedReadingProgress(context, file))
    }

    @Test
    fun preparedWriteKeepsApplyCommitChoiceAndCommitFailureResult() {
        val file = temporaryFolder.newFile("failure.cbz")
        val calls = mutableListOf<String>()
        val preferences = failingPreferences(calls)

        ReaderProgressStore.prepareSave(preferences, file, position = 3, offset = 12).apply()
        val commitResult = ReaderProgressStore.prepareSave(
            preferences,
            file,
            position = 4,
            offset = 13
        ).commit()

        assertFalse(commitResult)
        assertEquals(
            listOf(
                "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}:position=3",
                "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}:offset=12",
                "apply",
                "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}:position=4",
                "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}:offset=13",
                "commit"
            ),
            calls
        )
    }

    private fun positionKey(file: File): String = "${archiveKey(file)}:position"

    private fun offsetKey(file: File): String = "${archiveKey(file)}:offset"

    private fun archiveKey(file: File): String =
        "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}"

    private fun failingPreferences(calls: MutableList<String>): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, arguments ->
            when (method.name) {
                "putInt" -> {
                    calls += "${arguments!![0]}=${arguments[1]}"
                    proxy
                }
                "apply" -> {
                    calls += "apply"
                    null
                }
                "commit" -> {
                    calls += "commit"
                    false
                }
                "toString" -> "FailingSharedPreferencesEditor"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { proxy, method, _ ->
            when (method.name) {
                "edit" -> editor
                "toString" -> "FailingSharedPreferences"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }

    private companion object {
        const val PREFS_NAME = "reader_prefs"
    }
}
