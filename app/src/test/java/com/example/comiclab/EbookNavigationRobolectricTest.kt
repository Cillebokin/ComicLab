package com.example.comiclab

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.example.comiclab.ebook.html.ebookAnchorFromUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EbookNavigationRobolectricTest {
    @Test fun onlyCurrentBookAnchorsAreAccepted() {
        assertEquals("chapter-1-a-6e6f7465", ebookAnchorFromUrl(Uri.parse("https://comiclab.invalid/#chapter-1-a-6e6f7465")))
        assertEquals("chapter-0", ebookAnchorFromUrl(Uri.parse("#chapter-0")))
        listOf("https://external/#chapter-1", "file:///book#chapter-1", "javascript:bad()",
            "https://comiclab.invalid/other#chapter-1", "https://comiclab.invalid/?x=1#chapter-1",
            "https://user@comiclab.invalid/#chapter-1", "https://comiclab.invalid/#unexpected").forEach {
            assertNull(it, ebookAnchorFromUrl(Uri.parse(it)))
        }
    }

    @Test fun clickJumpsInsideTheExistingDocumentAndExternalNavigationDoesNotExecuteScript() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(EbookReaderActivity::class.java,
            Intent(context, EbookReaderActivity::class.java)).create()
        try {
            val activity = controller.get()
            EbookReaderActivity::class.java.getDeclaredField("pageReady").apply { isAccessible = true; setBoolean(activity, true) }
            val web = activity.findViewById<WebView>(R.id.webEbookReader)
            val shadow = Shadows.shadowOf(web)
            val client = shadow.webViewClient
            val previousUrl = shadow.lastLoadedUrl
            assertTrue(client.shouldOverrideUrlLoading(web, request("https://comiclab.invalid/#chapter-1-a-6e6f7465")))
            assertEquals("window.jumpToComicLabAnchor(\"chapter-1-a-6e6f7465\");", shadow.lastEvaluatedJavascript)
            assertEquals("navigation must not load another document", previousUrl, shadow.lastLoadedUrl)
            val script = shadow.lastEvaluatedJavascript
            client.shouldOverrideUrlLoading(web, request("https://external/#chapter-2"))
            client.shouldOverrideUrlLoading(web, request("https://comiclab.invalid/#chapter-2", mainFrame = false))
            assertEquals(script, shadow.lastEvaluatedJavascript)
        } finally { controller.destroy() }
    }

    private fun request(value: String, mainFrame: Boolean = true) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(value)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
