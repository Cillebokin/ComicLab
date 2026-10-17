package com.example.comiclab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.os.ParcelFileDescriptor
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.ui.reader.manga.ReaderDoublePageSnapHelper
import com.example.comiclab.ui.reader.manga.ReaderPageZoomLayout
import com.example.comiclab.ui.reader.manga.ReaderSubsamplingImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder

@RunWith(AndroidJUnit4::class)
class MangaReaderPhase5DeviceAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val trackedActivities = mutableListOf<Activity>()
    private var storedPathSandbox: StoredPathPreferenceSandbox? = null
    private lateinit var savedPreferences: Map<String, Map<String, Any?>>

    @get:Rule
    val temporaryFolder = TemporaryFolder(context.cacheDir)

    @Before
    fun snapshotReaderPreferences() {
        savedPreferences = PREFERENCE_FILES.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
        }
        storedPathSandbox = StoredPathPreferenceSandbox(context)
        check(
            context.getSharedPreferences("saf_prefs", Context.MODE_PRIVATE).edit()
                .putString("current_path", temporaryFolder.root.absolutePath)
                .putBoolean("storage_permission_prompted", true)
                .commit()
        )
    }

    @After
    fun closeReadersAndRestorePreferences() {
        val active = onMain { resumedReader() }
        if (active != null && !active.isFinishing && !active.isDestroyed) {
            finishReader(active)
        }
        trackedActivities.forEach { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                onMain { activity.finish() }
            }
            (activity as? MangaReaderActivity)?.let { reader ->
                progressExecutor(reader)?.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
        storedPathSandbox?.restore()
        savedPreferences.forEach { (name, values) ->
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = preferences.edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            assertTrue("Could not restore $name", editor.commit())
        }
    }

    @Test
    fun ordinaryArchiveSupportsZoomFastFlipRotationProgressAndExit() {
        val archive = createArchive("ordinary-reader.cbz", pageCount = 18)
        var activity = launchReader(archive, debugStressIterations = 8)

        awaitDebugStress(activity, expectedSteps = 8)
        exercisePinchAndFastSwipe(activity, "ordinary-page-zoom.png")
        val positionBeforeRotation = activity.currentReaderPositionForTest
        assertTrue("Page flipping should advance beyond the first page", positionBeforeRotation > 0)

        val previous = activity
        activity.intent.putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS, false)
        val oldProgressExecutor = checkNotNull(progressExecutor(previous))
        onMain { previous.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        activity = awaitReadyReader(previous)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, onMain { activity.resources.configuration.orientation })
        awaitExecutor(oldProgressExecutor)
        awaitCondition("reader-position restoration retries did not finish") {
            activity.pendingRestorePositionForTest == null
        }
        val persistedPosition = ReaderProgressStore.readPositionAndOffset(context, archive).first
        val restored = runCatching {
            awaitCondition("saved page was not visible after rotation") {
                positionBeforeRotation in visibleSourcePositions(activity)
            }
        }.isSuccess
        val restoredPosition = activity.currentReaderPositionForTest
        val visibleSources = visibleSourcePositions(activity)
        val expectedPosition = activity.readerSpreadStartSourcePositionForTest(persistedPosition)
        assertEquals("rotation should preserve the saved source page", positionBeforeRotation, persistedPosition)
        assertTrue("saved source page should still be visible: $visibleSources", restored)
        assertEquals(
            "rotation should restore the saved reading anchor: " +
                "source=$positionBeforeRotation expected=$expectedPosition visible=$visibleSources " +
                readerPositionSnapshot(activity),
            expectedPosition,
            restoredPosition
        )
        assertEquals(18, activity.imageEntriesForTest.size)

        finishReader(activity)
        assertTrue("reader progress writer should drain on exit", checkNotNull(progressExecutor(activity)).awaitTermination(10, TimeUnit.SECONDS))
        assertTrue("exit should persist the page position", (ReaderProgressStore.savedReadingProgress(context, archive)?.position ?: 0) > 0)
    }

    @Test
    fun horizontalLandscapeReaderShowsAndSnapsCompleteDoublePageSpreads() {
        val archive = createArchive("double-page-reader.cbz", pageCount = 8)
        AppSettings.setReadingDirection(context, AppSettings.READING_DIRECTION_LEFT_TO_RIGHT)
        AppSettings.setDoublePageReadingEnabled(context, true)
        AppSettings.setDoublePageCoverSingleEnabled(context, true)

        val previous = launchReader(archive)
        onMain { previous.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val activity = awaitReadyReader(previous)
        assertEquals(
            Configuration.ORIENTATION_LANDSCAPE,
            onMain { activity.resources.configuration.orientation }
        )
        assertTrue(
            "landscape horizontal double-page mode should attach its spread snap helper",
            onMain {
                activity.listReaderPagesForTest.onFlingListener is ReaderDoublePageSnapHelper
            }
        )

        onMain { activity.jumpReaderToPageForTest(1) }
        awaitCondition("the double-page spread did not lay out both source pages") {
            val visible = visibleSourcePositions(activity)
            visible.contains(1) && visible.contains(2)
        }
        val visible = visibleSourcePositions(activity)
        assertTrue("the visible spread should contain source pages 2 and 3: $visible", visible.contains(1) && visible.contains(2))
        captureR17Screenshot("double-page-spread.png")

        finishReader(activity)
        assertTrue(
            "double-page reader progress writer should drain on exit",
            checkNotNull(progressExecutor(activity)).awaitTermination(10, TimeUnit.SECONDS)
        )
    }

    @Test
    fun pdfArchivePathRendersPagesOnDeviceAndClosesOnExit() {
        val pdf = createPdf("pdf-reader.pdf", pageCount = 4)
        val activity = launchReader(pdf)

        assertEquals(4, activity.imageEntriesForTest.size)
        assertTrue("PDF page should reach the reader-ready callback", activity.firstReaderPageReadyMetricReportedForTest)
        assertTrue("PDF should use the ordinary page adapter", activity.pageAdapterForTest?.javaClass?.simpleName == "MangaPageAdapter")
        captureR17Screenshot("pdf-page.png")
        onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        val rotated = awaitReadyReader(activity)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, onMain { rotated.resources.configuration.orientation })
        finishReader(rotated)
        assertTrue("PDF reader progress writer should drain on exit", checkNotNull(progressExecutor(rotated)).awaitTermination(10, TimeUnit.SECONDS))
    }

    @Test
    fun preparedImagePathKeepsCacheAcrossRotationAndDeletesItAfterExit() {
        val archive = createArchive("prepared-reader.cbz", pageCount = 12)
        val cacheDirectory = File(temporaryFolder.root, "prepared-reader-cache").apply { mkdirs() }
        val entries = (1..12).map { "page_%03d.png".format(it) }
        entries.forEachIndexed { index, entryName ->
            val imageFile = ComicArchive.preparedImageFile(cacheDirectory, index, entryName)
            imageFile.parentFile?.mkdirs()
            imageFile.writeBytes(createPagePng(index))
        }
        ComicArchive.writePreparedReaderManifest(cacheDirectory, entries)

        var activity = launchReader(archive, preparedCacheDirectory = cacheDirectory)
        assertEquals("SubsamplingMangaPageAdapter", activity.pageAdapterForTest?.javaClass?.simpleName)
        assertEquals(12, activity.imageEntriesForTest.size)
        assertTrue(cacheDirectory.isDirectory)
        exercisePinchAndFastSwipe(activity, "prepared-page-zoom.png")

        val previous = activity
        val oldProgressExecutor = checkNotNull(progressExecutor(previous))
        onMain { previous.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        activity = awaitReadyReader(previous)
        awaitExecutor(oldProgressExecutor)
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, onMain { activity.resources.configuration.orientation })
        assertEquals("SubsamplingMangaPageAdapter", activity.pageAdapterForTest?.javaClass?.simpleName)
        assertTrue("prepared cache must remain leased by the rotated reader", cacheDirectory.isDirectory)

        finishReader(activity)
        assertTrue("prepared reader progress writer should drain on exit", checkNotNull(progressExecutor(activity)).awaitTermination(10, TimeUnit.SECONDS))
        awaitCondition("prepared cache was not deleted after the last reader owner closed") { !cacheDirectory.exists() }
    }

    private fun launchReader(
        archive: File,
        preparedCacheDirectory: File? = null,
        debugStressIterations: Int = 0
    ): MangaReaderActivity {
        val browser = ensureBrowserForeground()
        val intent = Intent(context, MangaReaderActivity::class.java)
            .putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
            .putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, true)
        if (preparedCacheDirectory != null) {
            intent.putExtra(MangaReaderActivity.EXTRA_PREPARED_READER_CACHE_DIR, preparedCacheDirectory.absolutePath)
        }
        if (debugStressIterations > 0) {
            intent.putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS, true)
            intent.putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_ITERATIONS, debugStressIterations)
            intent.putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_DELAY_MS, 90L)
        }
        onMain { browser.startActivity(intent) }
        return awaitReadyReader()
    }

    private fun ensureBrowserForeground(): MainActivity {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -n com.example.comiclab/.MainActivity -f 0x10008000"
        )
        val shellOutput = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertTrue("device shell could not foreground the isolated browser: $shellOutput", shellOutput.contains("Starting:"))
        awaitCondition("isolated MainActivity did not resume") { onMain { resumedBrowser() != null } }
        return checkNotNull(onMain { resumedBrowser() }).also { browser ->
            if (browser !in trackedActivities) trackedActivities += browser
        }
    }

    private fun awaitReadyReader(previous: MangaReaderActivity? = null): MangaReaderActivity {
        var readyReader: MangaReaderActivity? = null
        try {
            awaitCondition("reader did not load and render its first page") {
                val current = onMain { resumedReader() }
                if (current == null || current === previous) return@awaitCondition false
                val ready = onMain {
                    val adapter = current.pageAdapterForTest
                    val recyclerView = current.listReaderPagesForTest
                    val pageReady = if (adapter?.javaClass?.simpleName == "SubsamplingMangaPageAdapter") {
                        (0 until recyclerView.childCount).any { index ->
                            (findZoomView(recyclerView.getChildAt(index)) as? ReaderSubsamplingImageView)
                                ?.isImageLoaded() == true
                        }
                    } else {
                        current.firstReaderPageReadyMetricReportedForTest
                    }
                    pageReady && current.imageEntriesForTest.isNotEmpty() && adapter != null
                }
                if (ready) readyReader = current
                ready
            }
        } catch (failure: AssertionError) {
            val state = onMain {
                resumedReader()?.let { current ->
                    "activity=${System.identityHashCode(current)} " +
                        "firstReady=${current.firstReaderPageReadyMetricReportedForTest} " +
                        "entries=${current.imageEntriesForTest.size} " +
                        "adapter=${current.pageAdapterForTest?.javaClass?.simpleName} " +
                        "status=${current.tvReaderStatusForTest.text} " +
                        "orientation=${current.resources.configuration.orientation} " +
                        "current=${current.currentReaderPositionForTest} " +
                        "offset=${current.currentReaderOffsetForTest} " +
                        "pending=${current.pendingRestorePositionForTest} " +
                        "children=" +
                        current.listReaderPagesForTest.let { recyclerView ->
                            (0 until recyclerView.childCount).map { index ->
                                val child = recyclerView.getChildAt(index)
                                "${recyclerView.getChildAdapterPosition(child)}:${child.top}-${child.bottom}"
                            }
                        }
                } ?: "no resumed reader"
            }
            throw AssertionError("reader did not load and render its first page: $state", failure)
        }
        val activity = checkNotNull(readyReader)
        if (activity !in trackedActivities) trackedActivities += activity
        return activity
    }

    private fun awaitDebugStress(activity: MangaReaderActivity, expectedSteps: Int) {
        awaitCondition("debug reader stress did not exercise all flip/zoom/preload steps") {
            activity.debugReaderStressStepForTest >= expectedSteps
        }
    }

    private fun exercisePinchAndFastSwipe(activity: MangaReaderActivity, screenshotName: String) {
        onView(withId(R.id.listReaderPages)).check(matches(isDisplayed()))
        instrumentation.waitForIdleSync()
        val zoomView = onMain {
            findVisibleZoomView(activity.listReaderPagesForTest)
        }
        assertNotNull("a visible page should expose its zoom view", zoomView)
        val receivedEvents = java.util.Collections.synchronizedList(mutableListOf<String>())
        onMain {
            checkNotNull(zoomView).setOnTouchListener { _, event ->
                val secondPointerX = if (event.pointerCount > 1) event.getX(1) else event.x
                receivedEvents += "${event.actionMasked}:${event.pointerCount}:${event.x}:$secondPointerX"
                false
            }
        }
        injectPinchOpen(checkNotNull(zoomView))
        val zoomedScale = zoomScale(checkNotNull(zoomView))
        onMain { checkNotNull(zoomView).setOnTouchListener(null) }
        assertTrue(
            "pinch gesture should increase the page scale; scale=$zoomedScale events=$receivedEvents",
            zoomedScale > 1.05f
        )
        captureR17Screenshot(screenshotName)
        onMain {
            when (val view = checkNotNull(zoomView)) {
                is ReaderPageZoomLayout -> view.resetZoom()
                is ReaderSubsamplingImageView -> view.resetScaleAndCenter()
            }
        }

        onMain { activity.jumpReaderToPageForTest(0) }
        onView(withId(R.id.listReaderPages)).perform(
            GeneralSwipeAction(
                Swipe.FAST,
                GeneralLocation.CENTER,
                GeneralLocation.TOP_CENTER,
                Press.FINGER
            )
        )
        awaitCondition("fast page swipe did not advance the reader") {
            activity.currentReaderPositionForTest > 0
        }
        awaitCondition("fast page swipe did not settle before rotation checks") {
            onMain {
                activity.listReaderPagesForTest.scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
        }
    }

    private fun finishReader(activity: MangaReaderActivity) {
        if (activity.isDestroyed || activity.isFinishing) return
        onMain { activity.finish() }
        awaitCondition("reader Activity did not finish") { activity.isDestroyed || activity.isFinishing }
    }

    private fun progressExecutor(activity: MangaReaderActivity): ExecutorService? =
        activity.readerProgressExecutorForTest

    private fun awaitExecutor(executor: ExecutorService) {
        assertTrue("previous Activity progress writer did not drain", executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    private fun createArchive(name: String, pageCount: Int): File {
        val archive = File(temporaryFolder.root, name)
        ZipOutputStream(archive.outputStream()).use { output ->
            repeat(pageCount) { index ->
                output.putNextEntry(ZipEntry("page_%03d.png".format(index + 1)))
                output.write(createPagePng(index))
                output.closeEntry()
            }
        }
        return archive
    }

    private fun createPdf(name: String, pageCount: Int): File {
        val pdf = File(temporaryFolder.root, name)
        val document = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(900, 1400, index + 1).create()
                )
                page.canvas.drawColor(Color.rgb(235, 239, 245))
                page.canvas.drawText(
                    "ComicLab PDF page ${index + 1}",
                    80f,
                    160f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.DKGRAY
                        textSize = 48f
                    }
                )
                document.finishPage(page)
            }
            FileOutputStream(pdf).use(document::writeTo)
        } finally {
            document.close()
        }
        return pdf
    }

    private fun createPagePng(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 1400, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(35 + index * 11 % 180, 55 + index * 17 % 160, 75 + index * 23 % 140))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 58f
            }
            canvas.drawText("Page ${index + 1}", 80f, 160f, paint)
            canvas.drawRect(90f, 260f, 810f, 1250f, Paint().apply { color = Color.argb(100, 255, 255, 255) })
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureR17Screenshot(fileName: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(750)
        instrumentation.waitForIdleSync()
        val screenshotDirectory = File(checkNotNull(context.getExternalFilesDir(null)), "w3-1j-r17")
        check(screenshotDirectory.isDirectory || screenshotDirectory.mkdirs()) {
            "Could not create the app-specific R17 screenshot directory"
        }
        val screenshot = File(screenshotDirectory, fileName)
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "screencap -p ${screenshot.absolutePath}"
        )
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        assertTrue("R17 screenshot was not written: $screenshot", screenshot.isFile && screenshot.length() > 0L)
    }

    private fun findZoomView(view: View): View? {
        if (view is ReaderPageZoomLayout || view is ReaderSubsamplingImageView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findZoomView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findVisibleZoomView(recyclerView: RecyclerView): View? {
        val centerChild = recyclerView.findChildViewUnder(
            recyclerView.width / 2f,
            recyclerView.height / 2f
        )
        centerChild?.let(::findZoomView)?.let { return it }
        val visibleRect = android.graphics.Rect()
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            if (!child.getGlobalVisibleRect(visibleRect) || visibleRect.isEmpty) continue
            findZoomView(child)?.let { return it }
        }
        return null
    }

    private fun injectPinchOpen(layout: View) {
        val position = IntArray(2)
        onMain {
            layout.getLocationOnScreen(position)
        }
        val centerX = position[0] + layout.width / 2f
        val centerY = position[1] + layout.height / 2f
        val minScalingSpan = ViewConfiguration.get(context).scaledMinimumScalingSpan
        val startSpan = maxOf(100f, minScalingSpan / 2f + 24f)
        val downTime = SystemClock.uptimeMillis()

        fun send(action: Int, count: Int, halfSpan: Float) {
            val properties = Array(count) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coordinates = Array(count) { index ->
                MotionEvent.PointerCoords().apply {
                    x = centerX + if (index == 0) -halfSpan else halfSpan
                    y = centerY
                    pressure = 1f
                    size = 1f
                }
            }
            val eventTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                count,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0
            )
            try {
                assertTrue("device rejected a synthetic pinch event", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }

        send(MotionEvent.ACTION_DOWN, 1, startSpan)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, startSpan)
        repeat(6) { step ->
            send(MotionEvent.ACTION_MOVE, 2, startSpan + (step + 1) * 15f)
            SystemClock.sleep(16)
        }
        val finalSpan = startSpan + 90f
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, finalSpan)
        send(MotionEvent.ACTION_UP, 1, finalSpan)
        instrumentation.waitForIdleSync()
    }

    private fun zoomScale(view: View): Float = when (view) {
        is ReaderPageZoomLayout -> view.scaleForTest
        is ReaderSubsamplingImageView -> view.scale
        else -> error("Unsupported reader zoom view: ${view.javaClass.name}")
    }

    private fun visibleSourcePositions(activity: MangaReaderActivity): List<Int> {
        val imageCount = activity.imageEntriesForTest.size
        val (firstAdapterPosition, lastAdapterPosition) = onMain {
            val layoutManager = activity.readerLayoutManagerForTest
            layoutManager.findFirstVisibleItemPosition() to layoutManager.findLastVisibleItemPosition()
        }
        val mapper = onMain { activity.readerPagePositionMapperForTest(imageCount) }
        val visibleCount = if (firstAdapterPosition == RecyclerView.NO_POSITION || lastAdapterPosition == RecyclerView.NO_POSITION) {
            1
        } else {
            (lastAdapterPosition - firstAdapterPosition + 1).coerceAtLeast(1)
        }
        return onMain { mapper.sourcePositionsInAdapterRange(firstAdapterPosition, visibleCount) }
    }

    private fun readerPositionSnapshot(activity: MangaReaderActivity): String = onMain {
        val recyclerView = activity.listReaderPagesForTest
        val layoutManager = activity.readerLayoutManagerForTest
        val positions = (0 until recyclerView.childCount).map { index ->
            val child = recyclerView.getChildAt(index)
            "${recyclerView.getChildAdapterPosition(child)}:${child.top}-${child.bottom}"
        }
        "orientation=${activity.resources.configuration.orientation} " +
            "current=${activity.currentReaderPositionForTest} " +
            "offset=${activity.currentReaderOffsetForTest} " +
            "pending=${activity.pendingRestorePositionForTest} " +
            "pendingOffset=${activity.pendingRestoreOffsetForTest} " +
            "restoreAttempts=${activity.restoreAttemptCountForTest} " +
            "first=${layoutManager.findFirstVisibleItemPosition()} " +
            "last=${layoutManager.findLastVisibleItemPosition()} " +
            "children=$positions"
    }

    private fun resumedReader(): MangaReaderActivity? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MangaReaderActivity>()
            .firstOrNull { !it.isFinishing && !it.isDestroyed }

    private fun resumedBrowser(): MainActivity? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>()
            .firstOrNull { !it.isFinishing && !it.isDestroyed }

    private fun <T> onMain(action: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(action()) }
        return value.get()
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue(message, condition())
    }

    private companion object {
        val PREFERENCE_FILES = listOf(
            "app_settings",
            "saf_prefs",
            "reader_prefs",
            "reading_history_prefs",
            "reader_diagnostics",
            "favorite_comic_prefs",
            "favorite_path_prefs"
        )
    }
}
