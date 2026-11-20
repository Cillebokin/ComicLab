package com.example.comiclab

import android.widget.ProgressBar
import com.example.comiclab.core.store.ReaderProgressStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaPreviewProgressRobolectricTest {
    @Test
    fun delayedProgressWriteRefreshesVisiblePreviewAndStoppedPreviewCatchesUpOnResume() {
        // No archive decoding is needed to exercise progress and Activity lifecycle.
        val controller = Robolectric.buildActivity(MangaPreviewActivity::class.java).create()
        val activity = controller.get()
        val file = File(activity.cacheDir, "delayed-progress.cbz")
        fun setField(name: String, value: Any) {
            MangaPreviewActivity::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(activity, value)
            }
        }
        ReaderProgressStore.clearSavedReadingProgress(activity, file)
        setField("archiveFile", file)
        setField("imageEntries", List(33) { "$it.jpg" })
        controller.start().resume()
        val progress = activity.findViewById<ProgressBar>(R.id.progressReading)
        try {
            assertEquals(0, progress.progress)
            ReaderProgressStore.prepareSave(activity, file, 11, 402).commit()
            assertEquals(12, progress.progress)

            controller.pause().stop()
            ReaderProgressStore.prepareSave(activity, file, 31, 0, coveredPosition = 32).commit()
            assertEquals(12, progress.progress)
            controller.restart().start().resume()
            assertEquals(33, progress.progress)
            assertEquals(31 to 0, ReaderProgressStore.readPositionAndOffset(activity, file))

            ReaderProgressStore.clearSavedReadingProgress(activity, file)
            assertEquals(0, progress.progress)
        } finally {
            controller.pause().stop().destroy()
            ReaderProgressStore.clearSavedReadingProgress(activity, file)
        }
    }
}
