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
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ebook.EbookReaderProgressMapper
import com.example.comiclab.ui.browser.transfer.FileTransferMode
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
import org.junit.Assert.assertFalse
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
    fun nativeContentsJumpsToBothEpub3AnchorsInTheSameChapter() {
        verifyContentsAnchors("nav.xhtml", """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="landmarks"><ol><li><a href="chapter-one.xhtml">Landmark</a></li></ol></nav>
                <nav epub:type="toc"><ol>
                  <li><a href="chapter-one.xhtml#first-stop">Early stop</a></li>
                  <li><a href="chapter-one.xhtml#%E7%AC%AC%E4%BA%8C">Later stop</a></li>
                  <li><a href="chapter-two.xhtml#missing">Chapter fallback</a></li>
                </ol></nav>
              </body>
            </html>
        """.trimIndent())
    }

    @Test
    fun nativeContentsJumpsToBothNestedNcxAnchorsInTheSameChapter() {
        verifyContentsAnchors("toc.ncx", """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="first"><navLabel><text>Early stop</text></navLabel>
                  <content src="chapter-one.xhtml#first-stop"/>
                  <navPoint id="second"><navLabel><text>Later stop</text></navLabel>
                    <content src="chapter-one.xhtml#%E7%AC%AC%E4%BA%8C"/>
                  </navPoint>
                </navPoint>
                <navPoint id="third"><navLabel><text>Chapter fallback</text></navLabel>
                  <content src="chapter-two.xhtml#missing"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent())
    }

    private fun verifyContentsAnchors(navigationFile: String, navigation: String) {
        writeEpubFixture(bookFile, navigationFile, navigation)
        val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/com.example.comiclab.MainActivity"
            )
        ).bufferedReader().use { it.readText() }
        check("Status: ok" in launchOutput) { launchOutput }
        browserActivity = awaitBrowser()
        val intent = Intent(context, EbookReaderActivity::class.java)
            .putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, bookFile.absolutePath)
        onMain { browserActivity!!.startActivity(intent) }
        val activity = awaitReader(expectedOrientation = null, requireRestoredScroll = false)
        trackedActivities += activity
        awaitCondition("EPUB chapter did not render before opening contents") {
            evaluate(activity, "!!document.querySelector('#chapter-0 h3')") == "true"
        }

        listOf(
            Triple("1. Early stop", "first-stop", 0),
            Triple("2. Later stop", "第二", 0),
            Triple("3. Chapter fallback", null, 1)
        ).forEach { (label, anchor, chapter) ->
            onMain { activity.findViewById<View>(R.id.btnEbookContents).performClick() }
            onView(withText(label)).inRoot(isDialog()).perform(click())
            val id = if (anchor == null) "chapter-$chapter" else {
                "chapter-$chapter-a-" + anchor.toByteArray(Charsets.UTF_8).joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
            }
            awaitCondition("Selecting $label did not scroll to $id") {
                evaluate(activity,
                    "Math.abs(document.getElementById('$id')?.getBoundingClientRect().top) < 3") == "true" &&
                    onMain { activity.hasWindowFocus() && activity.progressForTest?.chapterIndex == chapter }
            }
        }
        assertEquals("1", evaluate(activity, "document.querySelectorAll('#chapter-0').length"))
    }

    @Test
    fun oneScreenBookReportsAndRestoresCompletedProgress() {
        val shortBook = File(testRoot, "one-screen.txt").apply { writeText("A short story.") }
        EbookProgressStore.save(context, shortBook, EbookProgress(0, 0f, coordinateVersion = 1))
        val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/com.example.comiclab.MainActivity"
            )
        ).bufferedReader().use { it.readText() }
        check("Status: ok" in launchOutput) { launchOutput }
        browserActivity = awaitBrowser()
        onMain {
            browserActivity!!.startActivity(
                Intent(context, EbookReaderActivity::class.java)
                    .putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, shortBook.absolutePath)
            )
        }

        val before = awaitReader(null, false)
        assertOneScreenBookCompleted(before)
        assertEquals(EbookProgress(0, 1f), EbookProgressStore.load(context, shortBook))

        onMain { before.recreate() }
        awaitCondition("one-screen reader was not recreated") { onMain { resumedEbookActivity()?.let { it !== before } == true } }
        val after = awaitReader(null, false)
        assertOneScreenBookCompleted(after)
        assertEquals(EbookProgress(0, 1f), EbookProgressStore.load(context, shortBook))
    }

    @Test
    fun optionalNeighborFailureKeepsProgressReportingAndLoadsAfterRecovery() {
        val threeChapterBook = File(testRoot, "optional-neighbor.epub")
        writeEpubFixture(threeChapterBook, includeThirdChapter = true)
        val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/com.example.comiclab.MainActivity"
            )
        ).bufferedReader().use { it.readText() }
        check("Status: ok" in launchOutput) { launchOutput }
        browserActivity = awaitBrowser()
        onMain {
            browserActivity!!.startActivity(
                Intent(context, EbookReaderActivity::class.java)
                    .putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, threeChapterBook.absolutePath)
            )
        }

        val activity = awaitReader(expectedOrientation = null, requireRestoredScroll = false)
        val documentsDir = File(context.cacheDir, "ebook-documents")
        val chapterTwoFile = checkNotNull(
            documentsDir.listFiles()?.singleOrNull { it.name.endsWith("-chapter-2.html") }
        ) { "Prepared chapter 2 file was not found in $documentsDir" }
        val blockedFile = File(chapterTwoFile.parentFile, "${chapterTwoFile.name}.blocked")
        check(chapterTwoFile.renameTo(blockedFile)) { "Could not block $chapterTwoFile" }
        try {
            evaluate(
                activity,
                """
                    window.scrollTo(0, document.getElementById('chapter-1').getBoundingClientRect().top + window.scrollY +
                        document.getElementById('chapter-1').offsetHeight - window.innerHeight * 0.5);
                    window.dispatchEvent(new Event('scroll'));
                    true;
                """.trimIndent()
            )
            awaitCondition("Current chapter progress was blocked by missing optional neighbor") {
                onMain {
                    activity.findViewById<View>(R.id.tvEbookReaderStatus).visibility == View.GONE &&
                        activity.progressForTest?.let { it.chapterIndex == 1 && it.scrollFraction > 0.1f } == true
                } && EbookProgressStore.load(context, threeChapterBook)?.let {
                    it.chapterIndex == 1 && it.scrollFraction > 0.1f
                } == true
            }
            assertEquals("false", evaluate(activity, "!!document.querySelector('#chapter-2')"))

            check(blockedFile.renameTo(chapterTwoFile)) { "Could not restore $chapterTwoFile" }
            evaluate(activity, "window.scrollBy(0, -12); window.dispatchEvent(new Event('scroll')); true;")
            awaitCondition("Recovered optional neighbor chapter 2 did not load") {
                evaluate(activity, "!!document.querySelector('#chapter-2')") == "true"
            }
            evaluate(
                activity,
                "window.scrollTo(0, document.getElementById('chapter-2').getBoundingClientRect().top + window.scrollY); window.dispatchEvent(new Event('scroll')); true;"
            )
            awaitCondition("Recovered chapter 2 progress was not saved") {
                onMain { activity.progressForTest?.chapterIndex == 2 } &&
                    EbookProgressStore.load(context, threeChapterBook)?.chapterIndex == 2
            }
        } finally {
            if (blockedFile.exists() && !chapterTwoFile.exists()) {
                blockedFile.renameTo(chapterTwoFile)
            }
        }
    }

    private fun assertOneScreenBookCompleted(activity: EbookReaderActivity) {
        assertEquals(
            "true",
            evaluate(activity, """
                document.querySelectorAll('[data-chapter-index]').length === 1 &&
                    document.documentElement.scrollHeight <= window.innerHeight && window.scrollY === 0
            """.trimIndent())
        )
        onMain {
            assertEquals(EbookProgress(0, 1f), activity.progressForTest)
            val slider = activity.findViewById<android.widget.SeekBar>(R.id.sliderEbookReaderProgress)
            assertEquals(slider.max, slider.progress)
            val label = activity.findViewById<android.widget.TextView>(R.id.tvEbookReaderProgress)
            assertEquals(activity.getString(R.string.ebook_reader_progress_value, 100f, 1, 1), label.text.toString())
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
                "am start -W -n ${context.packageName}/com.example.comiclab.MainActivity"
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

    @Test
    fun chapterTailRestoresTheSamePhysicalPositionAfterRecreation() {
        val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/com.example.comiclab.MainActivity"
            )
        ).bufferedReader().use { it.readText() }
        check("Status: ok" in launchOutput)
        browserActivity = awaitBrowser()
        onMain { browserActivity!!.startActivity(Intent(context, EbookReaderActivity::class.java)
            .putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, bookFile.absolutePath)) }
        val before = awaitReader(null, false)
        evaluate(before, "window.scrollTo(0, document.getElementById('chapter-1').getBoundingClientRect().top + window.scrollY - window.innerHeight * 0.25); window.dispatchEvent(new Event('scroll')); true;")
        awaitCondition("chapter-tail progress was not saved using the new coordinates") {
            EbookProgressStore.load(context, bookFile)?.let {
                it.coordinateVersion == 2 && it.chapterIndex == 0 && it.scrollFraction > 0.9f
            } == true
        }
        val expectedY = evaluate(before, "window.scrollY").toDouble()
        onMain { before.recreate() }
        awaitCondition("reader was not recreated") { onMain { resumedEbookActivity()?.let { it !== before } == true } }
        val after = awaitReader(null, true)
        val restoredY = evaluate(after, "window.scrollY").toDouble()
        assertTrue("chapter-tail position moved from $expectedY to $restoredY", abs(expectedY - restoredY) <= 2)
    }

    @Test
    fun cuttingEpubParentStillOpensAtTheSavedChapterAndScrollPosition() {
        val original = File(testRoot, "source").apply { check(mkdir()) }
        val source = File(original, bookFile.name)
        check(bookFile.renameTo(source))
        val destination = File(testRoot, "destination").apply { check(mkdir()) }
        val target = File(destination, "source/${source.name}")
        val expected = EbookProgress(chapterIndex = 1, scrollFraction = 0.62f)
        EbookProgressStore.save(context, source, expected)

        val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start -W -n ${context.packageName}/com.example.comiclab.MainActivity"
            )
        ).bufferedReader().use { it.readText() }
        check("Status: ok" in launchOutput)
        val browser = awaitBrowser().also { browserActivity = it }
        onMain {
            browser.currentPathForTest = destination.absolutePath
            browser.beginFileTransferForTest(original, FileTransferMode.CUT)
            assertTrue(browser.findViewById<View>(R.id.btnPasteFile).performClick())
        }
        awaitCondition("cut did not move the EPUB and finish the workflow") {
            target.isFile && onMain { browser.fileTransferControllerForTest.pendingTransfer == null }
        }
        assertFalse(source.exists())
        assertEquals(expected, EbookProgressStore.load(context, target))

        onMain {
            browser.startActivity(Intent(context, EbookReaderActivity::class.java)
                .putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, target.absolutePath))
        }
        assertReaderProgress(awaitReader(null, requireRestoredScroll = true), expected)
    }

    private fun evaluate(activity: EbookReaderActivity, script: String): String {
        val result = AtomicReference<String>()
        val done = java.util.concurrent.CountDownLatch(1)
        onMain { activity.findViewById<WebView>(R.id.webEbookReader).evaluateJavascript(script) {
            result.set(it)
            done.countDown()
        } }
        check(done.await(10, TimeUnit.SECONDS)) { "WebView did not complete the probe" }
        return result.get()
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

    private fun writeEpubFixture(
        file: File,
        navigationFile: String? = null,
        navigation: String = "",
        includeThirdChapter: Boolean = false
    ) {
        val isNcx = navigationFile?.endsWith(".ncx") == true
        val navItem = when {
            isNcx -> """<item id="navigation" href="$navigationFile" media-type="application/x-dtbncx+xml"/>"""
            navigationFile != null -> """<item id="navigation" href="$navigationFile" media-type="application/xhtml+xml" properties="nav"/>"""
            else -> ""
        }
        val thirdManifest = if (includeThirdChapter) {
            """<item id="chapter-three" href="chapter-three.xhtml" media-type="application/xhtml+xml"/>"""
        } else ""
        val thirdSpine = if (includeThirdChapter) """<itemref idref="chapter-three"/>""" else ""
        val spineAttributes = if (isNcx) """ toc="navigation"""" else ""
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
                        $thirdManifest
                        $navItem
                      </manifest>
                      <spine$spineAttributes><itemref idref="chapter-one"/><itemref idref="chapter-two"/>$thirdSpine</spine>
                    </package>""".trimIndent()
            )
            if (navigationFile != null) output.putEntry("OEBPS/$navigationFile", navigation)
            output.putEntry("OEBPS/chapter-one.xhtml", chapterXhtml("Chapter one", navigationFile != null))
            output.putEntry("OEBPS/chapter-two.xhtml", chapterXhtml("Chapter two"))
            if (includeThirdChapter) output.putEntry("OEBPS/chapter-three.xhtml", chapterXhtml("Chapter three"))
        }
    }

    private fun chapterXhtml(title: String, withAnchors: Boolean = false): String = buildString {
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>")
        append(title)
        append("</h1>")
        repeat(PARAGRAPH_COUNT) { index ->
            if (withAnchors && index == 6) append("""<h3 id="first-stop">Early target</h3>""")
            if (withAnchors && index == 18) append("""<h3 id="第二">Later target</h3>""")
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
