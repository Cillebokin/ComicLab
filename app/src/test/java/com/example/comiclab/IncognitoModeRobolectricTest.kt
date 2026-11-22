package com.example.comiclab

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.example.comiclab.core.settings.ComicLabMangaProgress
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.ebook.EbookProgress
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class IncognitoModeRobolectricTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun resetStateBefore() {
        clearAll()
    }

    @After
    fun resetStateAfter() {
        clearAll()
    }

    private fun clearAll() {
        listOf(
            "app_settings",
            "reading_history_prefs",
            "reader_prefs",
            "ebook_progress_prefs",
            "saf_prefs"
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun mainToolbarButtonTogglesPersistsAndHighlightsIncognitoState() {
        val storageRoot = temporaryFolder.newFolder("incognito-storage")
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
        context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("current_path", storageRoot.absolutePath)
            .putBoolean("storage_permission_prompted", true)
            .commit()

        assertFalse(AppSettings.isIncognitoModeEnabled(context))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val button = controller.get().findViewById<ImageButton>(R.id.btnIncognitoMode)
            assertFalse(button.isSelected)
            assertEquals(
                ContextCompat.getColor(context, R.color.comiclab_icon),
                button.imageTintList?.defaultColor
            )

            button.performClick()
            assertTrue(AppSettings.isIncognitoModeEnabled(context))
            assertTrue(button.isSelected)
            assertEquals(
                ContextCompat.getColor(context, R.color.comiclab_text_on_accent),
                button.imageTintList?.defaultColor
            )
            assertEquals(R.drawable.bg_incognito_active, shadowOf(button.background).createdFromResId)
            assertEquals(
                context.getString(R.string.incognito_mode_enabled),
                ShadowToast.getTextOfLatestToast()
            )

            controller.recreate()
            val recreatedButton = controller.get().findViewById<ImageButton>(R.id.btnIncognitoMode)
            assertTrue(AppSettings.isIncognitoModeEnabled(context))
            assertTrue(recreatedButton.isSelected)
            assertEquals(
                ContextCompat.getColor(context, R.color.comiclab_text_on_accent),
                recreatedButton.imageTintList?.defaultColor
            )
            assertEquals(R.drawable.bg_incognito_active, shadowOf(recreatedButton.background).createdFromResId)

            recreatedButton.performClick()
            assertFalse(AppSettings.isIncognitoModeEnabled(context))
            assertFalse(recreatedButton.isSelected)
            assertEquals(
                ContextCompat.getColor(context, R.color.comiclab_icon),
                recreatedButton.imageTintList?.defaultColor
            )
            assertTrue(shadowOf(recreatedButton.background).createdFromResId != R.drawable.bg_incognito_active)
            assertEquals(
                context.getString(R.string.incognito_mode_disabled),
                ShadowToast.getTextOfLatestToast()
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun mangaReaderSkipsHistoryProgressWritesAndStartOverClearInIncognitoMode() {
        val archive = File(temporaryFolder.root, "incognito-manga.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            repeat(5) { index ->
                zip.putNextEntry(ZipEntry("page-$index.png"))
                zip.write(byteArrayOf(1, 2, 3, index.toByte()))
                zip.closeEntry()
            }
        }
        val originalProgress = ComicLabMangaProgress(archive.absolutePath, position = 3, offset = -20, totalCount = 5)
        MangaReaderActivity.restoreReadingProgress(context, archive, originalProgress)
        AppSettings.setIncognitoModeEnabled(context, true)

        val intent = Intent(context, MangaReaderActivity::class.java).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
            putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, true)
        }
        val controller = Robolectric.buildActivity(MangaReaderActivity::class.java, intent)
        val activity = controller.get()
        try {
            controller.create()
            val deadline = System.currentTimeMillis() + 5000
            val imageEntriesField = MangaReaderActivity::class.java.getDeclaredField("imageEntries")
                .apply { isAccessible = true }
            while ((imageEntriesField.get(activity) as List<*>).isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(5, (imageEntriesField.get(activity) as List<*>).size)

            // Opening in incognito mode must not record reading history.
            assertTrue(ReadingHistoryStore.items(context).isEmpty())

            // Decoding page 0 on start-from-beginning must not clear existing saved progress.
            MangaReaderActivity::class.java.getDeclaredMethod("onReaderPageDecoded", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, 0)
            assertEquals(originalProgress, MangaReaderActivity.savedReadingProgress(context, archive))

            // Saving reader position in incognito mode must not overwrite existing progress.
            MangaReaderActivity::class.java.getDeclaredField("pendingRestorePosition")
                .apply { isAccessible = true }
                .set(activity, 1)
            MangaReaderActivity::class.java.getDeclaredField("pendingRestoreOffset")
                .apply { isAccessible = true }
                .set(activity, -5)
            MangaReaderActivity::class.java.getDeclaredMethod("saveReaderPosition", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(activity, true)
            activity.readerProgressExecutorForTest.submit {}.get(5, TimeUnit.SECONDS)

            assertEquals(originalProgress, MangaReaderActivity.savedReadingProgress(context, archive))
            assertTrue(ReadingHistoryStore.items(context).isEmpty())
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun ebookReaderSkipsHistoryProgressWritesAndStartOverClearInIncognitoMode() {
        val book = File(temporaryFolder.root, "incognito-book.txt").apply {
            writeText("Chapter 1\nHello incognito reader.\n")
        }
        val originalProgress = EbookProgress(chapterIndex = 0, scrollFraction = 0.6f)
        EbookProgressStore.save(context, book, originalProgress, chapterCount = 1)
        AppSettings.setIncognitoModeEnabled(context, true)

        val intent = Intent(context, EbookReaderActivity::class.java).apply {
            putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, book.absolutePath)
            putExtra(EbookReaderActivity.EXTRA_START_FROM_BEGINNING, true)
        }
        val controller = Robolectric.buildActivity(EbookReaderActivity::class.java, intent)
        val activity = controller.get()
        try {
            controller.create()
            val loadExecutor = EbookReaderActivity::class.java.getDeclaredField("loadExecutor")
                .apply { isAccessible = true }
                .get(activity) as ExecutorService
            loadExecutor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()

            // Start-from-beginning must not clear saved progress in incognito mode.
            assertEquals(originalProgress, EbookProgressStore.load(context, book))
            assertEquals(600, EbookProgressStore.savedSliderProgress(context, book))
            // Loading book must not add it to reading history.
            assertTrue(ReadingHistoryStore.items(context).isEmpty())

            // Progress updates must not overwrite saved progress in incognito mode.
            activity.progressForTest = EbookProgress(chapterIndex = 0, scrollFraction = 0.2f)
            activity.saveProgressForTest()
            assertEquals(originalProgress, EbookProgressStore.load(context, book))
            assertEquals(600, EbookProgressStore.savedSliderProgress(context, book))

            // Recreating (e.g. rotating screen) keeps the in-memory incognito reading position without writing disk.
            controller.recreate()
            assertEquals(EbookProgress(chapterIndex = 0, scrollFraction = 0.2f), controller.get().progressForTest)
            assertEquals(originalProgress, EbookProgressStore.load(context, book))
            assertTrue(ReadingHistoryStore.items(context).isEmpty())
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Start-from-beginning + immediate recreate in incognito keeps null position instead of jumping to old disk progress.
        val startOverController = Robolectric.buildActivity(EbookReaderActivity::class.java, intent)
        try {
            startOverController.create()
            assertEquals(null, startOverController.get().progressForTest)
            startOverController.recreate()
            assertEquals(null, startOverController.get().progressForTest)
            assertEquals(originalProgress, EbookProgressStore.load(context, book))
        } finally {
            startOverController.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }

        // Turning incognito off restores normal recording.
        AppSettings.setIncognitoModeEnabled(context, false)
        val normalIntent = Intent(context, EbookReaderActivity::class.java).apply {
            putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, book.absolutePath)
        }
        val normalController = Robolectric.buildActivity(EbookReaderActivity::class.java, normalIntent)
        val normalActivity = normalController.get()
        try {
            normalController.create()
            val loadExecutor = EbookReaderActivity::class.java.getDeclaredField("loadExecutor")
                .apply { isAccessible = true }
                .get(normalActivity) as ExecutorService
            loadExecutor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(1, ReadingHistoryStore.items(context).size)
            normalActivity.progressForTest = EbookProgress(chapterIndex = 0, scrollFraction = 0.8f)
            normalActivity.saveProgressForTest()
            assertEquals(EbookProgress(0, 0.8f), EbookProgressStore.load(context, book))
            assertEquals(800, EbookProgressStore.savedSliderProgress(context, book))
        } finally {
            normalController.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
