package com.example.comiclab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.comiclab.core.settings.ComicLabMangaProgress
import com.example.comiclab.ui.reader.manga.SubsamplingMangaPageAdapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matcher
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed

@RunWith(AndroidJUnit4::class)
class NextMangaDeviceAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var root: File

    @Before fun setUp() {
        assumeTrue("Run only against the isolated test app", context.packageName.endsWith(".nextprobe"))
        root = File(checkNotNull(context.getExternalFilesDir(null)), "next-probe-${System.nanoTime()}")
        check(root.mkdirs())
        AppSettings.setNextMangaEnabled(context, true)
        AppSettings.setReadingDirection(context, AppSettings.READING_DIRECTION_TOP_TO_BOTTOM)
        AppSettings.setDoublePageReadingEnabled(context, false)
        context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE).edit()
            .putString("sort_mode", "NAME_ASC")
            .putBoolean("storage_permission_prompted", true).commit()
    }

    @After fun tearDown() {
        if (!::root.isInitialized) return
        main {
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.distinct().forEach { if (!it.isFinishing && !it.isDestroyed) it.finish() }
        }
        root.deleteRecursively()
    }

    @Test fun consecutiveComicsRestoreProgressAndReturnToCurrentPreview() {
        val first = comic("01.cbz", 3)
        val second = comic("02.cbz", 3)
        val third = pdf("03.pdf", 2)
        MangaReaderActivity.restoreReadingProgress(context, second,
            ComicLabMangaProgress(second.absolutePath, position = 1, offset = 0))
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val firstReader = awaitReader(first)
        reachEnd(firstReader, second.name)
        main { firstReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        onView(withText(R.string.continue_reading)).inRoot(isDialog()).perform(directClick())
        val secondReader = awaitReader(second)
        await("second comic did not restore progress") {
            main { secondReader.currentReaderPositionForTest == 1 && secondReader.pendingRestorePositionForTest == null }
        }
        reachEnd(secondReader, third.name)
        main { secondReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        val thirdReader = awaitReader(third)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val currentPreview = awaitPreview(third)
        assertEquals(third.nameWithoutExtension, main { currentPreview.findViewById<TextView>(R.id.tvComicName).text.toString() })
        assertTrue("third reader should be finishing", thirdReader.isFinishing || thirdReader.isDestroyed)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("exiting current preview did not return to the browser") { main { resumed() is MainActivity } }
    }

    @Test fun damagedNextComicReturnsToPreviousReader() {
        val first = comic("01.cbz", 3)
        val damaged = File(root, "02.cbz").apply { writeText("not an archive") }
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val reader = awaitReader(first)
        reachEnd(reader, damaged.name)
        main { reader.findViewById<Button>(R.id.btnNextManga).performClick() }
        await("damaged comic did not show a preparation error") {
            main {
                val active = resumed()
                (active is MangaReaderPrepareActivity && active.findViewById<View>(R.id.progressPrepareReader).visibility == View.GONE) ||
                    (active is MangaReaderActivity && active !== reader && active.tvReaderStatusForTest.visibility == View.VISIBLE && active.pageAdapterForTest == null)
            }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("failed next comic did not return to previous reader") { main { resumed() === reader } }
        assertFalse(reader.isFinishing)
    }

    @Test fun resumedNextComicReturnsToItsOwnPreview() {
        val first = comic("01.cbz", 3)
        val second = comic("02.cbz", 3)
        MangaReaderActivity.restoreReadingProgress(context, second,
            ComicLabMangaProgress(second.absolutePath, position = 1, offset = 0))
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val firstReader = awaitReader(first)
        reachEnd(firstReader, second.name)
        main { firstReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        onView(withText(R.string.continue_reading)).inRoot(isDialog()).perform(directClick())
        val reader = awaitReader(second)
        await("second comic did not restore progress") {
            main { reader.currentReaderPositionForTest == 1 && reader.pendingRestorePositionForTest == null }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val currentPreview = awaitPreview(second)
        assertEquals(second.nameWithoutExtension, main { currentPreview.findViewById<TextView>(R.id.tvComicName).text.toString() })
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("exiting the second preview did not return to the browser") { main { resumed() is MainActivity } }
    }

    @Test fun consecutiveUnfinishedComicsReachPdfAndItsPreview() {
        val first = comic("01.cbz", 3)
        val second = comic("02.cbz", 6)
        val third = pdf("03.pdf", 4)
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val firstReader = awaitReader(first)
        reachEnd(firstReader, second.name)
        main { firstReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        val secondReader = awaitReader(second)
        reachEnd(secondReader, third.name)
        main { secondReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        val thirdReader = awaitReader(third)
        scrollEnd(thirdReader)
        await("last comic should show no-next message") { main {
            val button = thirdReader.findViewById<Button>(R.id.btnNextManga)
            button.visibility == View.VISIBLE && button.text.toString() == context.getString(R.string.reader_no_next_manga)
        } }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPreview(third)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("exiting the PDF preview did not return to the browser") { main { resumed() is MainActivity } }
    }

    @Test fun rotatingNextComicStillReturnsToItsPreview() {
        val first = comic("01.cbz", 3)
        val second = comic("02.cbz", 6)
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val firstReader = awaitReader(first)
        reachEnd(firstReader, second.name)
        main { firstReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        val secondReader = awaitReader(second)
        main { secondReader.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val rotated = awaitReader(second, secondReader)
        assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            main { rotated.resources.configuration.orientation })
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPreview(second)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("exiting rotated reader preview did not return to browser") { main { resumed() is MainActivity } }
    }

    @Test fun deletedNextComicDoesNotLeaveCurrentReader() {
        val first = comic("01.cbz", 3)
        val second = comic("02.cbz", 3)
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val reader = awaitReader(first)
        reachEnd(reader, second.name)
        assertTrue(second.delete())
        main { reader.findViewById<Button>(R.id.btnNextManga).performClick() }
        instrumentation.waitForIdleSync()
        assertSame(reader, main { resumed() })
        assertFalse(reader.isFinishing)
    }

    @Test fun failedPdfStartOverDoesNotEraseExistingProgress() {
        val first = comic("01.cbz", 3)
        val damaged = File(root, "02.pdf").apply { writeText("not a pdf") }
        MangaReaderActivity.restoreReadingProgress(context, damaged,
            ComicLabMangaProgress(damaged.absolutePath, position = 2, offset = 0))
        assertEquals(2, MangaReaderActivity.savedReadingProgress(context, damaged)?.position)
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val reader = awaitReader(first)
        reachEnd(reader, damaged.name)
        main { reader.findViewById<Button>(R.id.btnNextManga).performClick() }
        onView(withText(R.string.read_from_beginning)).inRoot(isDialog()).perform(directClick())
        await("damaged PDF did not show reader error") { main {
            val active = resumed() as? MangaReaderActivity
            active != null && active !== reader && active.tvReaderStatusForTest.visibility == View.VISIBLE &&
                active.pageAdapterForTest == null && active.tvReaderStatusForTest.text.toString() != context.getString(R.string.loading_reading)
        } }
        assertNotNull("failed opening must not erase the existing PDF reading position",
            MangaReaderActivity.savedReadingProgress(context, damaged))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("failed PDF did not return to previous reader") { main { resumed() === reader } }
    }

    @Test fun undecodableNextComicKeepsPreviousReaderAndOldProgress() {
        val first = comic("01.cbz", 3)
        val damaged = File(root, "02.cbz")
        ZipOutputStream(damaged.outputStream()).use { zip ->
            repeat(3) { index ->
                zip.putNextEntry(ZipEntry("page_%03d.png".format(index)))
                zip.write("not a decodable image".toByteArray())
                zip.closeEntry()
            }
        }
        MangaReaderActivity.restoreReadingProgress(context, damaged,
            ComicLabMangaProgress(damaged.absolutePath, position = 2, offset = 0))
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val previousReader = awaitReader(first)
        reachEnd(previousReader, damaged.name)
        main { previousReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        onView(withText(R.string.read_from_beginning)).inRoot(isDialog()).perform(directClick())
        awaitReader(damaged, requireDecoded = false)
        SystemClock.sleep(1800)
        assertFalse("previous reader must remain available until a page actually decodes", previousReader.isFinishing)
        assertEquals("failed decoding must preserve the old progress", 2,
            MangaReaderActivity.savedReadingProgress(context, damaged)?.position)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("undecodable comic should return to previous reader") { main { resumed() === previousReader } }
    }

    @Test fun badInitialPageWithGoodNeighborDoesNotCompleteNextComicHandoff() {
        val first = comic("01.cbz", 3)
        val mixed = comic("02.cbz", 3, badPage = 0)
        MangaReaderActivity.restoreReadingProgress(context, mixed,
            ComicLabMangaProgress(mixed.absolutePath, position = 2, offset = 0))
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val previous = awaitReader(first)
        reachEnd(previous, mixed.name)
        main { previous.findViewById<Button>(R.id.btnNextManga).performClick() }
        onView(withText(R.string.read_from_beginning)).inRoot(isDialog()).perform(directClick())
        val reader = awaitReader(mixed, requireDecoded = false)
        val pages = reader.listReaderPagesForTest
        await("bad initial page did not show the page failure") { main {
            val holder = pages.findViewHolderForAdapterPosition(0) as? SubsamplingMangaPageAdapter.PageViewHolder
            holder?.statusText?.visibility == View.VISIBLE &&
                holder.statusText.text.toString() == context.getString(R.string.reader_page_load_failed)
        } }

        main { reader.jumpReaderToPageForTest(1) }
        await("healthy neighboring page did not decode") { main {
            val holder = pages.findViewHolderForAdapterPosition(1) as? SubsamplingMangaPageAdapter.PageViewHolder
            holder?.imageView?.hasLoadedReaderImage() == true
        } }
        assertFalse(main { reader.firstReaderPageReadyMetricReportedForTest })
        assertFalse(main { previous.isFinishing })
        assertEquals(2, MangaReaderActivity.savedReadingProgress(context, mixed)?.position)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("back did not return to the previous reader") { main { resumed() === previous } }
    }

    @Test fun successfulStartOverClearsOldProgressAfterDecoding() {
        val first = comic("01.cbz", 3)
        val next = comic("02.cbz", 4)
        MangaReaderActivity.restoreReadingProgress(context, next,
            ComicLabMangaProgress(next.absolutePath, position = 2, offset = 0))
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val previousReader = awaitReader(first)
        reachEnd(previousReader, next.name)
        main { previousReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        onView(withText(R.string.read_from_beginning)).inRoot(isDialog()).perform(directClick())
        val reader = awaitReader(next)
        assertEquals(0, main { reader.currentReaderPositionForTest })
        val progress = MangaReaderActivity.savedReadingProgress(context, next)
        assertTrue("old position must be cleared after a page is decoded", progress == null || progress.position == 0)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPreview(next)
    }

    @Test fun rotatingWhileNextPageCannotDecodePreservesProgressAndBackPath() {
        val first = comic("01.cbz", 3)
        val damaged = File(root, "02.cbz")
        ZipOutputStream(damaged.outputStream()).use { zip ->
            repeat(3) { index ->
                zip.putNextEntry(ZipEntry("page_%03d.png".format(index)))
                zip.write("not a decodable image".toByteArray())
                zip.closeEntry()
            }
        }
        MangaReaderActivity.restoreReadingProgress(context, damaged,
            ComicLabMangaProgress(damaged.absolutePath, position = 2, offset = 0))
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val previousReader = awaitReader(first)
        reachEnd(previousReader, damaged.name)
        main { previousReader.findViewById<Button>(R.id.btnNextManga).performClick() }
        onView(withText(R.string.read_from_beginning)).inRoot(isDialog()).perform(directClick())
        val damagedReader = awaitReader(damaged, requireDecoded = false)
        main { damagedReader.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        awaitReader(damaged, damagedReader, requireDecoded = false)
        assertEquals(2, MangaReaderActivity.savedReadingProgress(context, damaged)?.position)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("after rotation, an undecodable comic should return to the previous reader") { main {
            (resumed() as? MangaReaderActivity)?.intent?.getStringExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH) == first.absolutePath
        } }
        assertEquals(2, MangaReaderActivity.savedReadingProgress(context, damaged)?.position)
    }

    @Test fun disabledSwitchHidesNextComicAtEnd() {
        val first = comic("01.cbz", 2)
        comic("02.cbz", 2)
        AppSettings.setNextMangaEnabled(context, false)
        val preview = launchPreview(first)
        main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
        val reader = awaitReader(first)
        scrollEnd(reader)
        SystemClock.sleep(1200)
        assertEquals(View.GONE, main { reader.findViewById<Button>(R.id.btnNextManga).visibility })
        AppSettings.setNextMangaEnabled(context, true)
        main { reader.recreate() }
        val newReader = awaitReader(first, reader)
        reachEnd(newReader, "02.cbz")
    }

    @Test fun horizontalReadingCanReachNextComicInBothDirections() {
        val first = comic("01.cbz", 3)
        comic("02.cbz", 2)
        for (direction in listOf(AppSettings.READING_DIRECTION_LEFT_TO_RIGHT, AppSettings.READING_DIRECTION_RIGHT_TO_LEFT)) {
            AppSettings.setReadingDirection(context, direction)
            val preview = launchPreview(first)
            // Previous iteration may have saved progress for this fixture.
            val hasProgress = MangaReaderActivity.hasSavedReadingProgress(context, first)
            main { preview.findViewById<View>(R.id.btnFullRead).performClick() }
            if (hasProgress) {
                onView(withText(R.string.read_from_beginning)).inRoot(isDialog()).perform(directClick())
            }
            val reader = awaitReader(first)
            reachEnd(reader, "02.cbz")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitPreview(first)
            main { resumed()?.finish() }
        }
    }

    private fun launchPreview(file: File): MangaPreviewActivity {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -n ${context.packageName}/com.example.comiclab.MainActivity -f 0x10008000"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        await("test browser did not resume") { main { resumed() is MainActivity } }
        val intent = Intent(context, MangaPreviewActivity::class.java)
            .putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
        main { checkNotNull(resumed()).startActivity(intent) }
        return awaitPreview(file)
    }

    private fun awaitPreview(file: File): MangaPreviewActivity {
        await("preview not resumed for ${file.name}") { main {
            (resumed() as? MangaPreviewActivity)?.intent?.getStringExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH) == file.absolutePath
        } }
        return main { resumed() as MangaPreviewActivity }
    }

    private fun awaitReader(
        file: File,
        previous: MangaReaderActivity? = null,
        requireDecoded: Boolean = true
    ): MangaReaderActivity {
        await("reader not ready for ${file.name}") { main {
            val activity = resumed() as? MangaReaderActivity
            val handoffReady = activity != null && (!requireDecoded ||
                !activity.intent.getBooleanExtra(MangaReaderActivity.EXTRA_RETURN_TO_CURRENT_PREVIEW, false) ||
                MangaReaderActivity::class.java.getDeclaredField("nextReaderWasReady")
                    .apply { isAccessible = true }.getBoolean(activity))
            activity != null && activity !== previous &&
                activity.intent.getStringExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH) == file.absolutePath &&
                activity.imageEntriesForTest.isNotEmpty() && activity.pageAdapterForTest != null && handoffReady
        } }
        return main { resumed() as MangaReaderActivity }
    }

    private fun scrollEnd(reader: MangaReaderActivity) {
        main { reader.jumpReaderToPageForTest(reader.imageEntriesForTest.lastIndex) }
        SystemClock.sleep(400)
        main {
            val list = reader.listReaderPagesForTest
            val layout = reader.readerLayoutManagerForTest
            if (layout.orientation == androidx.recyclerview.widget.LinearLayoutManager.VERTICAL) list.scrollBy(0, 5000)
            else list.scrollBy(if (layout.reverseLayout) -5000 else 5000, 0)
        }
    }

    private fun reachEnd(reader: MangaReaderActivity, expectedName: String) {
        scrollEnd(reader)
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val shown = main {
                val button = reader.findViewById<Button>(R.id.btnNextManga)
                button.visibility == View.VISIBLE && button.isEnabled && button.text.toString().contains(expectedName)
            }
            if (shown) return
            main {
                val list = reader.listReaderPagesForTest
                val layout = reader.readerLayoutManagerForTest
                if (layout.orientation == androidx.recyclerview.widget.LinearLayoutManager.VERTICAL) list.scrollBy(0, 2000)
                else list.scrollBy(if (layout.reverseLayout) -2000 else 2000, 0)
            }
            SystemClock.sleep(100)
        }
        val details = main {
            val list = reader.listReaderPagesForTest
            val layout = reader.readerLayoutManagerForTest
            val button = reader.findViewById<Button>(R.id.btnNextManga)
            val atEnd = MangaReaderActivity::class.java.getDeclaredField("atMangaEnd").apply { isAccessible = true }.get(reader)
            "first=${layout.findFirstVisibleItemPosition()} last=${layout.findLastVisibleItemPosition()} atEnd=$atEnd " +
                "items=${list.adapter?.itemCount} scrollDown=${list.canScrollVertically(1)} " +
                "scrollLeft=${list.canScrollHorizontally(-1)} scrollRight=${list.canScrollHorizontally(1)} " +
                "button=${button.visibility}/${button.isEnabled}/${button.text} pending=${reader.pendingRestorePositionForTest}"
        }
        fail("next comic button did not show $expectedName: $details")
    }

    private fun resumed(): Activity? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).firstOrNull { !it.isFinishing && !it.isDestroyed }

    private fun directClick() = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun getDescription(): String = "invoke the selected dialog button"
        override fun perform(uiController: UiController, view: View) { check(view.performClick()) }
    }

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }

    private fun comic(name: String, pages: Int, badPage: Int? = null): File {
        val file = File(root, name)
        val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.rgb(100, 140, 190))
        val data = ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        image.recycle()
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pages) { i ->
                zip.putNextEntry(ZipEntry("page_%03d.png".format(i)))
                zip.write(if (i == badPage) "not a decodable image".toByteArray() else data)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun pdf(name: String, pages: Int): File {
        val file = File(root, name)
        val document = PdfDocument()
        try {
            repeat(pages) { i ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, i + 1).create())
                page.canvas.drawColor(Color.LTGRAY)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }
}
