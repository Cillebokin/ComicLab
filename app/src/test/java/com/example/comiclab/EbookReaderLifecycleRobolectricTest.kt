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
        EbookReaderActivity::class.java.getDeclaredField("currentProgress")
            .apply { isAccessible = true }
            .get(activity) as EbookProgress?

    private fun setCurrentProgress(activity: EbookReaderActivity, progress: EbookProgress) {
        EbookReaderActivity::class.java.getDeclaredField("currentProgress")
            .apply { isAccessible = true }
            .set(activity, progress)
    }

    private fun saveCurrentProgress(activity: EbookReaderActivity) {
        EbookReaderActivity::class.java
            .getDeclaredMethod("saveCurrentProgress", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(activity, true)
    }
}
