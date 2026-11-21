package com.example.comiclab

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import kotlin.math.abs
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
    fun expandingAndScrollingLongPreviewBoundsViewsAndKeepsImagesMatchedToPages() {
        val pageCount = 360
        val archive = createArchiveWithImages(pageCount)
        var activity: MangaPreviewActivity? = null
        try {
            val browser = ensureBrowserForeground()
            val intent = Intent(context, MangaPreviewActivity::class.java)
                .putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
            val openedAt = SystemClock.elapsedRealtime()
            onMain { browser.startActivity(intent) }
            activity = awaitPreviewActivity()
            val preview = checkNotNull(activity)
            val grid = preview.findViewById<ViewGroup>(R.id.gridPreview)
            val scroll = preview.findViewById<ScrollView>(R.id.main)
            val toggle = preview.findViewById<View>(R.id.btnTogglePreview)
            val rowHeight = context.resources.getDimensionPixelSize(R.dimen.preview_image_height) +
                2 * context.resources.getDimensionPixelSize(R.dimen.preview_image_margin)

            awaitPageImage(grid, 0)
            val firstImageMs = SystemClock.elapsedRealtime() - openedAt
            val expandedAt = SystemClock.elapsedRealtime()
            onMain { check(toggle.performClick()) }
            awaitCondition { onMain { grid.height == pageCount / 3 * rowHeight } }
            val expandMs = SystemClock.elapsedRealtime() - expandedAt
            var peakViews = 0
            var peakThumbs = 0
            var peakBitmapBytes = 0L
            var peakPssKb = 0
            val scrollTimes = mutableListOf<Long>()
            val visited = (0 until pageCount step 18).toList() + listOf(pageCount - 1, 72, 0)
            for (index in visited) {
                val startedAt = SystemClock.elapsedRealtime()
                onMain {
                    val gridTop = (grid.parent as View).top + grid.top
                    scroll.scrollTo(0, if (index == 0) 0 else gridTop + index / 3 * rowHeight)
                }
                awaitPageImage(grid, index)
                scrollTimes += SystemClock.elapsedRealtime() - startedAt
                onMain {
                    val bitmaps = (0 until grid.childCount).mapNotNull { child ->
                        (grid.getChildAt(child) as? ImageView)?.let(::thumbnailBitmap)
                    }.distinct()
                    peakViews = maxOf(peakViews, grid.childCount)
                    peakThumbs = maxOf(peakThumbs, bitmaps.size)
                    peakBitmapBytes = maxOf(peakBitmapBytes, bitmaps.sumOf { it.allocationByteCount.toLong() })
                }
                val memoryInfo = Debug.MemoryInfo()
                Debug.getMemoryInfo(memoryInfo)
                peakPssKb = maxOf(peakPssKb, memoryInfo.totalPss)
            }

            val viewportBound = onMain { ((scroll.height + rowHeight - 1) / rowHeight + 3) * 3 }
            val metrics = "pages=$pageCount firstImageMs=$firstImageMs expandMs=$expandMs " +
                "scrollSamples=${scrollTimes.size} scrollMedianMs=${scrollTimes.sorted()[scrollTimes.size / 2]} " +
                "scrollMaxMs=${scrollTimes.maxOrNull()} peakViews=$peakViews peakThumbs=$peakThumbs " +
                "peakBitmapBytes=$peakBitmapBytes peakPssKb=$peakPssKb viewportBound=$viewportBound"
            Log.i("PreviewWindowAcceptance", metrics)
            assertTrue(metrics, peakViews <= viewportBound && peakThumbs <= viewportBound)

            onMain {
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                check(toggle.performClick())
            }
            awaitCondition { onMain { grid.height == 7 * rowHeight } }
            onMain { scroll.scrollTo(0, scroll.getChildAt(0).height) }
            awaitPageImage(grid, 19)
            assertTrue(onMain { grid.findViewWithTag<ImageView>(20) == null })
            onMain { scroll.scrollTo(0, 0) }
            awaitPageImage(grid, 0)

            onMain { preview.finish() }
            awaitCondition { onMain { preview.isDestroyed } }
            onMain { browser.startActivity(intent) }
            activity = awaitPreviewActivity()
            awaitPageImage(checkNotNull(activity).findViewById(R.id.gridPreview), 0)
        } finally {
            activity?.let { preview ->
                instrumentation.runOnMainSync { preview.finish() }
            }
        }
    }

    private fun awaitPageImage(grid: ViewGroup, index: Int) {
        val expected = pageColor(index)
        awaitCondition(timeoutMs = 15_000L) {
            onMain {
                val cell = grid.findViewWithTag<ImageView>(index)
                val bitmap = cell?.let(::thumbnailBitmap) ?: return@onMain false
                val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                abs(Color.red(pixel) - Color.red(expected)) < 15 &&
                    abs(Color.green(pixel) - Color.green(expected)) < 15 &&
                    abs(Color.blue(pixel) - Color.blue(expected)) < 15
            }
        }
    }

    private fun thumbnailBitmap(view: ImageView): Bitmap? = (view.drawable as? BitmapDrawable)?.bitmap

    private fun pageColor(index: Int): Int = Color.rgb(
        12 + (index % 8) * 33,
        12 + (index / 8 % 8) * 33,
        12 + (index / 64 % 8) * 33
    )

    private fun createArchiveWithImages(pageCount: Int): File {
        val archive = temporaryFolder.newFile("lazy-preview.zip")
        val bitmap = Bitmap.createBitmap(384, 576, Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { output ->
                repeat(pageCount) { index ->
                    bitmap.eraseColor(pageColor(index))
                    output.putNextEntry(ZipEntry("page_${index.toString().padStart(3, '0')}.jpg"))
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                    output.closeEntry()
                }
            }
        } finally {
            bitmap.recycle()
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
