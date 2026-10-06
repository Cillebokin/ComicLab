package com.example.comiclab

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaPreviewLazyDecodeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val temporaryFolder = TemporaryFolder(context.cacheDir)

    @Test
    fun expandingAndScrollingPreviewDoesNotDecodeEveryArchivePage() {
        val archive = createArchiveWithImages(pageCount = 60)
        var activity: MangaPreviewActivity? = null

        try {
            val browser = ensureBrowserForeground()
            val intent = Intent(context, MangaPreviewActivity::class.java)
                .putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
            onMain { browser.startActivity(intent) }
            activity = awaitPreviewActivity()
            val preview = checkNotNull(activity)
            val grid = preview.findViewById<GridLayout>(R.id.gridPreview)
            val scroll = preview.findViewById<ScrollView>(R.id.main)
            val toggle = preview.findViewById<View>(R.id.btnTogglePreview)

            awaitCondition { onMain { grid.childCount == 20 } }
            onMain { toggle.performClick() }
            awaitCondition { onMain { grid.childCount == 60 } }

            onMain {
                scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height) }
            }
            awaitCondition(timeoutMs = 15_000L) {
                onMain { (grid.getChildAt(59) as? ImageView)?.drawable != null }
            }
            SystemClock.sleep(500L)

            val decodedCount = onMain {
                (0 until grid.childCount).count { index ->
                    (grid.getChildAt(index) as? ImageView)?.drawable != null
                }
            }
            assertTrue("Scrolling should decode the last page", decodedCount > 0)
            assertTrue("Off-screen preview cells should remain undecoded", decodedCount < grid.childCount)
        } finally {
            activity?.let { preview ->
                instrumentation.runOnMainSync { preview.finish() }
            }
        }
    }

    private fun createArchiveWithImages(pageCount: Int): File {
        val archive = temporaryFolder.newFile("lazy-preview.zip")
        val bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.RGB_565)
        val pageBytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
            output.toByteArray()
        }
        bitmap.recycle()

        ZipOutputStream(archive.outputStream().buffered()).use { output ->
            repeat(pageCount) { index ->
                output.putNextEntry(ZipEntry("page_${index.toString().padStart(3, '0')}.jpg"))
                output.write(pageBytes)
                output.closeEntry()
            }
        }
        return archive
    }

    private fun awaitPreviewActivity(): MangaPreviewActivity {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(20)
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val activity = onMain {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MangaPreviewActivity>()
                    .firstOrNull { !it.isFinishing && !it.isDestroyed }
            }
            if (activity != null) return activity
            Thread.sleep(50L)
        }
        error("MangaPreviewActivity did not resume")
    }

    private fun ensureBrowserForeground(): MainActivity {
        resumedBrowser()?.let { return it }
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n com.example.comiclab/.MainActivity -f 0x10008000"
        ).use { }
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(20)
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            resumedBrowser()?.let { return it }
            Thread.sleep(50L)
        }
        error("MainActivity did not resume")
    }

    private fun resumedBrowser(): MainActivity? = onMain {
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .firstOrNull { !it.isFinishing && !it.isDestroyed }
    }

    private fun awaitCondition(timeoutMs: Long = 10_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50L)
        }
        assertTrue("Condition did not become true within ${timeoutMs}ms", condition())
    }

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }
}
