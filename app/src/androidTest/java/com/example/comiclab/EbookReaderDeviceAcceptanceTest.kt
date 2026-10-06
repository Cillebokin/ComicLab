package com.example.comiclab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ebook.EbookReaderProgressMapper
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EbookReaderDeviceAcceptanceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val trackedActivities = linkedSetOf<EbookReaderActivity>()
    private lateinit var savedPreferences: Map<String, Map<String, Any?>>
    private lateinit var testRoot: File
    private lateinit var bookFile: File
    private var browserActivity: MainActivity? = null

    @Before
    fun prepareIsolatedEbookFixture() {
        savedPreferences = PREFERENCE_FILES.associateWith { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
        }

        val cacheRoot = context.cacheDir.canonicalFile
        testRoot = File(cacheRoot, "comiclab-ebook-device-${UUID.randomUUID()}").canonicalFile
        check(testRoot.parentFile == cacheRoot)
        check(!testRoot.exists())
        check(testRoot.mkdirs())
        bookFile = File(testRoot, "rotation-progress.epub")
        writeEpubFixture(bookFile)
    }

    @After
    fun closeReaderAndRestorePreferences() {
        onMain {
            resumedEbookActivity()?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
            }
            trackedActivities.forEach { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
            }
            browserActivity?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
            }
        }
        instrumentation.waitForIdleSync()

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
            check(editor.commit()) { "Could not restore $name" }
        }

        if (::testRoot.isInitialized && testRoot.exists()) {
            val cacheRoot = context.cacheDir.canonicalFile.toPath()
            check(testRoot.canonicalFile.toPath().startsWith(cacheRoot))
            check(testRoot.deleteRecursively())
        }
    }

    @Test
    fun rotatingEbookReaderRestoresSavedChapterAndScrollProgress() {
        val expected = EbookProgress(chapterIndex = 1, scrollFraction = 0.62f)
        EbookProgressStore.save(context, bookFile, expected)

        val intent = Intent(context, EbookReaderActivity::class.java)
            .putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, bookFile.absolutePath)
        val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n com.example.comiclab/.MainActivity"
            )
        ).bufferedReader().use { it.readText() }
        check("Status: ok" in launchOutput) {
            "Could not bring the app to the foreground through adb shell: $launchOutput"
        }
        browserActivity = awaitBrowser()
        onMain { browserActivity!!.startActivity(intent) }

        var activity = awaitReader(expectedOrientation = null, requireRestoredScroll = true)
        onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        activity = awaitReader(Configuration.ORIENTATION_PORTRAIT, requireRestoredScroll = true)
        assertReaderProgress(activity, expected)

        onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        activity = awaitReader(Configuration.ORIENTATION_LANDSCAPE, requireRestoredScroll = true)
        assertReaderProgress(activity, expected)

        val persisted = checkNotNull(EbookProgressStore.load(context, bookFile))
        assertEquals(expected.chapterIndex, persisted.chapterIndex)
        assertTrue(
            "Saved progress changed unexpectedly after rotation: $persisted",
            abs(expected.scrollFraction - persisted.scrollFraction) <= PROGRESS_TOLERANCE
        )
    }

    private fun awaitReader(
        expectedOrientation: Int?,
        requireRestoredScroll: Boolean
    ): EbookReaderActivity {
        var ready: EbookReaderActivity? = null
        awaitCondition("Ebook reader did not load and restore progress in orientation $expectedOrientation") {
            val activity = onMain { resumedEbookActivity() } ?: return@awaitCondition false
            ready = activity
            onMain {
                val webView = activity.findViewById<WebView>(R.id.webEbookReader)
                val status = activity.findViewById<View>(R.id.tvEbookReaderStatus)
                (expectedOrientation == null ||
                    activity.resources.configuration.orientation == expectedOrientation) &&
                    status.visibility == View.GONE &&
                    (!requireRestoredScroll || webView.scrollY > 0)
            }
        }
        return checkNotNull(ready)
    }

    private fun awaitBrowser(): MainActivity {
        var ready: MainActivity? = null
        awaitCondition("ComicLab MainActivity did not resume after shell launch") {
            ready = onMain {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull { activity -> !activity.isFinishing && !activity.isDestroyed }
            }
            ready != null
        }
        return checkNotNull(ready)
    }

    private fun assertReaderProgress(activity: EbookReaderActivity, expected: EbookProgress) {
        val chapterCount = 2
        val expectedSlider = EbookReaderProgressMapper.toSlider(expected, chapterCount)
        val actualSlider = onMain {
            activity.findViewById<android.widget.SeekBar>(R.id.sliderEbookReaderProgress).progress
        }
        assertTrue(
            "Reader progress slider $actualSlider did not restore expected chapter/position $expectedSlider",
            abs(expectedSlider - actualSlider) <= SLIDER_TOLERANCE
        )
        val scrollY = onMain { activity.findViewById<WebView>(R.id.webEbookReader).scrollY }
        assertTrue("Reader should restore to a non-zero WebView position", scrollY > 0)
    }

    private fun resumedEbookActivity(): EbookReaderActivity? =
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<EbookReaderActivity>()
            .firstOrNull { activity ->
                trackedActivities += activity
                !activity.isFinishing && !activity.isDestroyed
            }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }

    private fun writeEpubFixture(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { output ->
            output.putEntry("mimetype", "application/epub+zip")
            output.putEntry(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""".trimIndent()
            )
            output.putEntry(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">comiclab-device-fixture</dc:identifier>
                        <dc:title>Rotation fixture</dc:title>
                      </metadata>
                      <manifest>
                        <item id="chapter-one" href="chapter-one.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-two" href="chapter-two.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="chapter-one"/><itemref idref="chapter-two"/></spine>
                    </package>""".trimIndent()
            )
            output.putEntry("OEBPS/chapter-one.xhtml", chapterXhtml("Chapter one"))
            output.putEntry("OEBPS/chapter-two.xhtml", chapterXhtml("Chapter two"))
        }
    }

    private fun chapterXhtml(title: String): String = buildString {
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>")
        append(title)
        append("</h1>")
        repeat(PARAGRAPH_COUNT) { index ->
            append("<p>Paragraph ${index + 1}: ")
            append("This fixture exists only in app cache and provides scrollable reader content. ".repeat(5))
            append("</p>")
        }
        append("</body></html>")
    }

    private fun ZipOutputStream.putEntry(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private companion object {
        val PREFERENCE_FILES = listOf("ebook_progress_prefs", "reading_history_prefs", "saf_prefs")
        const val PARAGRAPH_COUNT = 36
        const val PROGRESS_TOLERANCE = 0.12f
        const val SLIDER_TOLERANCE = 60
    }
}
