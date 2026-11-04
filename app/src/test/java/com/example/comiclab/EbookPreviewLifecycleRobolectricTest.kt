package com.example.comiclab

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.example.comiclab.ebook.EbookSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class EbookPreviewLifecycleRobolectricTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun readyPreviewAndOpeningReaderRetainMetadataWithoutRetainingTheBookSession() {
        val book = temporaryFolder.newFile("preview.txt").apply { writeText("Reader content. ".repeat(10000)) }
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(EbookPreviewActivity::class.java,
            Intent(context, EbookPreviewActivity::class.java).putExtra(EbookPreviewActivity.EXTRA_BOOK_PATH, book.absolutePath))
            .setup()
        try {
            val activity = controller.get()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (activity.findViewById<Button>(R.id.btnEbookContinue).visibility != View.VISIBLE && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertEquals(View.VISIBLE, activity.findViewById<Button>(R.id.btnEbookContinue).visibility)
            assertEquals("preview", activity.findViewById<TextView>(R.id.tvEbookTitle).text.toString())
            assertFalse("a ready preview must not retain chapter HTML through its session", activity.javaClass.declaredFields.any {
                it.isAccessible = true
                EbookSession::class.java.isAssignableFrom(it.type) && it.get(activity) != null
            })
            activity.findViewById<Button>(R.id.btnEbookContinue).performClick()
            controller.pause().stop().start().resume()
            assertEquals("preview", activity.findViewById<TextView>(R.id.tvEbookTitle).text.toString())
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
