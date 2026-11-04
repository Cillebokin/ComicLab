package com.example.comiclab.ebook

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EbookPreviewTest {
    @Test fun largeCoverIsSampledAndBothInputStreamsAndSessionCloseBeforeDelivery() {
        val bitmap = Bitmap.createBitmap(1600, 2400, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        var opened = 0
        var closed = 0
        val session = FakeSession {
            opened++
            object : ByteArrayInputStream(bytes) { override fun close() { closed++; super.close() } }
        }
        val preview = prepareEbookPreview(session, 400)
        try {
            assertEquals("book", preview.title)
            assertEquals(1, preview.chapterCount)
            assertEquals(1, session.closeCount)
            assertEquals(2, opened)
            assertEquals(opened, closed)
            assertNotNull(preview.cover)
            assertTrue(maxOf(preview.cover!!.width, preview.cover.height) <= 400)
        } finally { preview.cover?.recycle() }
    }

    @Test fun coverOpenFailureStillClosesTheParsedBook() {
        val session = FakeSession { throw IllegalStateException("cover failed") }
        assertThrows(IllegalStateException::class.java) { prepareEbookPreview(session, 400) }
        assertEquals(1, session.closeCount)
    }

    @Test fun cancelledPreviewClosesTheSessionWithoutOpeningTheCover() {
        var opened = false
        val session = FakeSession { opened = true; null }
        Thread.currentThread().interrupt()
        try { assertThrows(CancellationException::class.java) { prepareEbookPreview(session, 400) } }
        finally { Thread.interrupted() }
        assertFalse(opened)
        assertEquals(1, session.closeCount)
    }

    private class FakeSession(private val resource: () -> InputStream?) : EbookSession {
        override val book = EbookBook("book", "author", listOf(EbookChapter(0, "chapter", "body")),
            listOf(EbookResource("cover", 0, "image/png")), coverResourceId = "cover")
        var closeCount = 0
        override fun openResource(resourceId: String) = resource()
        override fun close() { closeCount++ }
    }
}
