package com.example.comiclab

import android.content.Intent
import android.os.Looper
import android.webkit.WebView
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
class EbookDocumentLifecycleRobolectricTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun readyReaderRetainsChapterMetadataAndStreamsItsDocumentWithoutKeepingSourceHtml() {
        val file = folder.newFile("stream.txt").apply { writeText("正文内容 & text. ".repeat(10000)) }
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(EbookReaderActivity::class.java,
            Intent(context, EbookReaderActivity::class.java).putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, file.absolutePath)).setup()
        try {
            val activity = controller.get()
            val field = EbookReaderActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (field.get(activity) == null && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            val session = field.get(activity) as? EbookSession
            assertNotNull("book should become ready", session)
            assertEquals(1, session!!.book.chapters.size)
            assertTrue("rendered chapter bodies must be released", session.book.chapters.all { it.html.isEmpty() })
            val web = activity.findViewById<WebView>(R.id.webEbookReader)
            val shadow = Shadows.shadowOf(web)
            assertEquals("https://comiclab.invalid/", shadow.lastLoadedUrl)
            val request = object : android.webkit.WebResourceRequest {
                override fun getUrl() = android.net.Uri.parse("https://comiclab.invalid/")
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = false
                override fun getMethod() = "GET"
                override fun getRequestHeaders() = emptyMap<String, String>()
            }
            assertFalse(shadow.webViewClient.shouldOverrideUrlLoading(web, request))
            val response = shadow.webViewClient.shouldInterceptRequest(web, request)!!
            assertEquals("text/html", response.mimeType)
            val shell = response.data.bufferedReader().use { it.readText() }
            assertFalse(shell.contains("正文内容"))
            val chapterRequest = object : android.webkit.WebResourceRequest by request {
                override fun getUrl() = android.net.Uri.parse("https://comiclab.invalid/chapters/0")
                override fun isForMainFrame() = false
            }
            val chapterResponse = shadow.webViewClient.shouldInterceptRequest(web, chapterRequest)!!
            assertTrue(chapterResponse.data.bufferedReader().use { it.readText() }.contains("正文内容 &amp; text."))
            val scriptRequest = object : android.webkit.WebResourceRequest by chapterRequest {
                override fun getUrl() = android.net.Uri.parse("https://comiclab.invalid/ebook-chapters.js")
            }
            val scriptResponse = shadow.webViewClient.shouldInterceptRequest(web, scriptRequest)!!
            assertEquals("application/javascript", scriptResponse.mimeType)
            assertTrue(scriptResponse.data.use { it.read() } >= 0)
            val invalidRequest = object : android.webkit.WebResourceRequest by chapterRequest {
                override fun getUrl() = android.net.Uri.parse("https://comiclab.invalid/chapters/9000")
            }
            assertEquals(403, shadow.webViewClient.shouldInterceptRequest(web, invalidRequest)!!.statusCode)
            val document = EbookReaderActivity::class.java.getDeclaredField("preparedDocument").apply { isAccessible = true }
                .get(activity) as com.example.comiclab.ebook.PreparedEbookDocument
            assertTrue(document.htmlFile.delete())
            assertEquals(403, shadow.webViewClient.shouldInterceptRequest(web, request)!!.statusCode)
            shadow.webViewClient.onPageFinished(web, "https://comiclab.invalid/")
            assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(R.id.tvEbookReaderStatus).visibility)
        } finally {
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun destroyingBeforeUiHandoffDiscardsThePreparedDocument() {
        val file = folder.newFile("late.txt").apply { writeText("late content") }
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(EbookReaderActivity::class.java,
            Intent(context, EbookReaderActivity::class.java).putExtra(EbookReaderActivity.EXTRA_BOOK_PATH, file.absolutePath)).create()
        val executor = EbookReaderActivity::class.java.getDeclaredField("loadExecutor").apply { isAccessible = true }
            .get(controller.get()) as java.util.concurrent.ExecutorService
        // All background preparation is complete while the main-thread handoff remains queued.
        executor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val documents = java.io.File(context.cacheDir, "ebook-documents").listFiles().orEmpty().toList()
        assertTrue(documents.isNotEmpty())
        controller.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("a discarded result must not leave its file behind", documents.none { it.exists() })
    }
}
