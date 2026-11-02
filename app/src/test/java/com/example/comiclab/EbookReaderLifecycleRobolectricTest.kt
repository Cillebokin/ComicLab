package com.example.comiclab

import android.content.Intent
import android.os.Looper
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.core.store.EbookProgressStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class EbookReaderLifecycleRobolectricTest {

    @Test
    fun rendererExitReleasesTheBookAndHandlesTheFailureWithoutUsingTheDeadWebView() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(EbookReaderActivity::class.java,
            Intent(context, EbookReaderActivity::class.java)).create()
        var closes = 0
        val fake = object : com.example.comiclab.ebook.EbookSession {
            override val book = com.example.comiclab.ebook.EbookBook("book", null, emptyList(), emptyList())
            override fun openResource(resourceId: String): java.io.InputStream? = null
            override fun close() { closes++ }
        }
        val activity = controller.get()
        EbookReaderActivity::class.java.getDeclaredField("session").apply { isAccessible = true; set(activity, fake) }
        val web = activity.findViewById<android.webkit.WebView>(R.id.webEbookReader)
        val client = org.robolectric.Shadows.shadowOf(web).webViewClient
        try {
            val handled = client.onRenderProcessGone(web, object : android.webkit.RenderProcessGoneDetail() {
                override fun didCrash() = false
                override fun rendererPriorityAtExit() = android.webkit.WebView.RENDERER_PRIORITY_IMPORTANT
            })
            org.junit.Assert.assertTrue("renderer exit must be handled by the reader", handled)
            assertEquals(1, closes)
            assertNull(web.parent)
            val dialog = EbookReaderActivity::class.java.getDeclaredField("rendererErrorDialog")
                .apply { isAccessible = true }.get(activity) as android.app.Dialog
            org.junit.Assert.assertTrue(dialog.isShowing)
        } finally {
            controller.destroy()
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals("destroy must not close an already-released book again", 1, closes)
    }

    @Test
    fun legacyStoredProgressKeepsItsCoordinatesUntilTheWebReaderReportsANewPosition() {
        val application = RuntimeEnvironment.getApplication()
        val book = File(application.cacheDir, "legacy-progress.epub").apply { writeText("fixture") }
        try {
            EbookProgressStore.save(application, book, EbookProgress(1, 0.75f))
            val prefs = application.getSharedPreferences("ebook_progress_prefs", 0)
            val editor = prefs.edit()
            prefs.all.keys.filter { it.endsWith("_coordinate_version") }.forEach(editor::remove)
            editor.commit()
            assertEquals(EbookProgress(1, 0.75f, 1), EbookProgressStore.load(application, book))
            val clamped = com.example.comiclab.ebook.EbookReaderProgressMapper.clampToBook(
                checkNotNull(EbookProgressStore.load(application, book)), 3
            )
            assertEquals(1, clamped.coordinateVersion)
            EbookProgressStore.save(application, book, EbookProgress(1, 0.5f))
            assertEquals(2, EbookProgressStore.load(application, book)?.coordinateVersion)
        } finally {
            EbookProgressStore.clear(application, book)
            book.delete()
        }
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun startFromBeginningClearsOnFreshLaunchButRestoresProgressAfterRecreation() {
        val application = RuntimeEnvironment.getApplication()
        val book = temporaryFolder.newFile("start-from-beginning.epub")
        EbookProgressStore.save(application, book, EbookProgress(chapterIndex = 4, scrollFraction = 0.8f))
        val controller = buildReaderController(application, book, startFromBeginning = true)

        try {
            controller.create()
            assertNull("a fresh start command clears older progress", EbookProgressStore.load(application, book))
            assertNull(currentProgress(controller.get()))

            val progressAfterStarting = EbookProgress(chapterIndex = 2, scrollFraction = 0.35f)
            setCurrentProgress(controller.get(), progressAfterStarting)
            saveCurrentProgress(controller.get())
            assertEquals(progressAfterStarting, EbookProgressStore.load(application, book))

            controller.recreate()

            assertEquals(
                "the persistent start command must not clear progress during Activity recreation",
                progressAfterStarting,
                currentProgress(controller.get())
            )
            assertEquals(progressAfterStarting, EbookProgressStore.load(application, book))
        } finally {
            if (!controller.get().isDestroyed) {
                runCatching { controller.destroy() }
            }
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun storedProgressLoadsAgainAfterRecreationWithoutStartCommand() {
        val application = RuntimeEnvironment.getApplication()
        val book = temporaryFolder.newFile("restore-progress.epub")
        val savedProgress = EbookProgress(chapterIndex = 3, scrollFraction = 0.62f)
        EbookProgressStore.save(application, book, savedProgress)
        val controller = buildReaderController(application, book, startFromBeginning = false)

        try {
            controller.create()
            assertEquals(savedProgress, currentProgress(controller.get()))

            controller.recreate()

            assertEquals(savedProgress, currentProgress(controller.get()))
            assertEquals(savedProgress, EbookProgressStore.load(application, book))
        } finally {
            if (!controller.get().isDestroyed) {
                runCatching { controller.destroy() }
            }
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun buildReaderController(
        application: android.app.Application,
        book: File,
        startFromBeginning: Boolean
    ) = Robolectric.buildActivity(
        EbookReaderActivity::class.java,
        Intent(application, EbookReaderActivity::class.java).apply {
            putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, book.absolutePath)
            if (startFromBeginning) {
                putExtra(EbookReaderActivity.EXTRA_START_FROM_BEGINNING, true)
            }
        }
    )

    private fun currentProgress(activity: EbookReaderActivity): EbookProgress? =
        activity.progressForTest

    private fun setCurrentProgress(activity: EbookReaderActivity, progress: EbookProgress) {
        activity.progressForTest = progress
    }

    private fun saveCurrentProgress(activity: EbookReaderActivity) {
        activity.saveProgressForTest()
    }
}
