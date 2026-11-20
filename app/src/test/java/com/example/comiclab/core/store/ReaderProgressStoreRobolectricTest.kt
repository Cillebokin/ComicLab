package com.example.comiclab.core.store

import android.content.SharedPreferences
import com.example.comiclab.core.settings.ComicLabMangaProgress
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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
    fun queuedSpreadCoverageSurvivesMoveAndClearsWithItsResumeAnchor() {
        val file = temporaryFolder.newFile("spread.cbz")
        val destination = temporaryFolder.newFile("moved-spread.cbz")
        ReaderProgressStore.prepareSave(context, destination, 0, 0).commit()
        lateinit var queued: ReaderProgressStore.PreparedWrite
        ReaderProgressStore.enqueueSave(context, ReaderProgressStore.readerArchiveKey(file), 0, 0, coveredPosition = 1) {
            queued = it
        }
        assertEquals(0 to 0, ReaderProgressStore.readPositionAndOffset(context, file))
        assertEquals(2, ReaderProgressStore.savedReadingPageCount(context, file, 4))
        assertTrue(ReaderProgressStore.hasSavedReadingProgress(context, file))

        ReaderProgressStore.moveFile(context, file, destination)
        assertEquals(0, ReaderProgressStore.savedReadingPageCount(context, file, 4))
        assertTrue(queued.commit())
        assertEquals(0 to 0, ReaderProgressStore.readPositionAndOffset(context, destination))
        assertEquals(2, ReaderProgressStore.savedReadingPageCount(context, destination, 4))
        assertEquals(
            ComicLabMangaProgress(destination.absolutePath, 0, 0, coveredPosition = 1),
            ReaderProgressStore.savedReadingProgress(context, destination)
        )

        ReaderProgressStore.clearSavedReadingProgress(context, destination)
        assertEquals(0, ReaderProgressStore.savedReadingPageCount(context, destination, 4))
        assertFalse(ReaderProgressStore.hasSavedReadingProgress(context, destination))
        assertNull(ReaderProgressStore.savedReadingProgress(context, destination))
    }

    @Test
    fun clearImmediatelyHidesQueuedProgressAndPreventsItsWrite() {
        val file = temporaryFolder.newFile("clear-queued.cbz")
        lateinit var queued: ReaderProgressStore.PreparedWrite
        ReaderProgressStore.enqueueSave(context, ReaderProgressStore.readerArchiveKey(file), 5, -24) {
            queued = it
        }
        assertEquals(5 to -24, ReaderProgressStore.readPositionAndOffset(context, file))

        ReaderProgressStore.clearSavedReadingProgress(context, file)

        assertEquals(0 to 0, ReaderProgressStore.readPositionAndOffset(context, file))
        val destination = temporaryFolder.newFile("after-clear-move.cbz")
        ReaderProgressStore.moveFile(context, file, destination)
        assertNull(ReaderProgressStore.savedReadingProgress(context, destination))
        assertFalse(queued.commit())
        assertNull(ReaderProgressStore.savedReadingProgress(context, file))
        ReaderProgressStore.restoreReadingProgress(context, file, ComicLabMangaProgress(file.absolutePath, 2, -8))
        assertEquals(2 to -8, ReaderProgressStore.readPositionAndOffset(context, file))
    }

    @Test
    fun clearCompletesWhileOlderCommitIsBlockedAndLateWriteCannotRestoreProgress() {
        val file = temporaryFolder.newFile("clear-during-commit.cbz")
        val original = context.getSharedPreferences(PREFS_NAME, 0)
        val commitStarted = CountDownLatch(1)
        val finishCommit = CountDownLatch(1)
        val prefs = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val editor = original.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                        editor.putInt(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        commitStarted.countDown()
                        check(finishCommit.await(5, TimeUnit.SECONDS))
                        return editor.commit()
                    }
                }
            }
        }
        val oldWrite = ReaderProgressStore.prepareSave(prefs, file, 5, -24)
        val writer = FutureTask { oldWrite.commit() }
        Thread(writer).apply { isDaemon = true }.start()
        try {
            assertTrue(commitStarted.await(3, TimeUnit.SECONDS))
            val clearAndRead = FutureTask {
                ReaderProgressStore.clearSavedReadingProgress(context, file)
                ReaderProgressStore.readPositionAndOffset(context, file)
            }
            Thread(clearAndRead).apply { isDaemon = true }.start()
            assertEquals(0 to 0, clearAndRead.get(2, TimeUnit.SECONDS))
        } finally {
            finishCommit.countDown()
        }
        assertFalse(writer.get(5, TimeUnit.SECONDS))
        assertEquals(0 to 0, ReaderProgressStore.readPositionAndOffset(context, file))
        assertNull(ReaderProgressStore.savedReadingProgress(context, file))
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
                "${positionKey(file)}=3",
                "${offsetKey(file)}=12",
                "remove ${archiveKey(file)}:coveredPosition",
                "apply",
                "${positionKey(file)}=4",
                "${offsetKey(file)}=13",
                "remove ${archiveKey(file)}:coveredPosition",
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
                "remove" -> {
                    calls += "remove ${arguments!![0]}"
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
