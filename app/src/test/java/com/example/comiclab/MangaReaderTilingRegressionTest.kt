package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaReaderTilingRegressionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun landscapeViewportDoesNotForceSmallSourceImageIntoTiledRendering() {
        val adapter = createAdapter(FakeReaderSession())
        try {
            val shouldTile = invokeShouldUseTiledPage(
                adapter,
                ComicArchive.ImageBounds(width = 1_200, height = 1_800)
            )

            assertFalse("source-sized decode fits this page without tiling", shouldTile)
        } finally {
            invokeClose(adapter)
        }
    }

    @Test
    fun tiledCandidateWithoutPreviewFallsBackToFullPageDecode() {
        val fullDecodeStarted = CountDownLatch(1)
        val fullDecodeWidth = AtomicInteger(0)
        val fullDecodeHeight = AtomicInteger(0)
        val usedBoundedPageDecoder = AtomicBoolean(false)
        val adapter = createAdapter(
            FakeReaderSession(
                bounds = ComicArchive.ImageBounds(width = 3_000, height = 6_000),
                onFullDecode = { width, height, bounded ->
                    fullDecodeWidth.set(width)
                    fullDecodeHeight.set(height)
                    usedBoundedPageDecoder.set(bounded)
                    fullDecodeStarted.countDown()
                }
            )
        )
        try {
            invokeEnsureFullOrTiledPage(adapter)

            assertTrue(
                "a missing tile preview must fall back to a full-page decode",
                fullDecodeStarted.await(3, TimeUnit.SECONDS)
            )
            assertEquals("fallback should use a memory-bounded width", 720, fullDecodeWidth.get())
            assertEquals(8_192, fullDecodeHeight.get())
            assertTrue("fallback must fit the entire page within its decode bounds", usedBoundedPageDecoder.get())
        } finally {
            invokeClose(adapter)
        }
    }

    private fun createAdapter(session: ComicArchive.ImageReaderSession): Any {
        val context = RuntimeEnvironment.getApplication()
        val adapterClass = Class.forName("com.example.comiclab.ui.reader.manga.MangaPageAdapter")
        val constructor = adapterClass.declaredConstructors
            .single { it.parameterCount == 15 }
            .apply { isAccessible = true }
        return constructor.newInstance(
            context,
            RecyclerView(context),
            temporaryFolder.newFile("reader-test.cbz"),
            session,
            listOf("page.png"),
            2_400,
            1_800,
            7_200,
            5_400,
            AppSettings.READING_DIRECTION_TOP_TO_BOTTOM,
            false,
            false,
            { _: Int -> },
            {},
            null
        )
    }

    private fun invokeShouldUseTiledPage(adapter: Any, bounds: ComicArchive.ImageBounds): Boolean {
        val method = adapter.javaClass
            .getDeclaredMethod("shouldUseTiledPage", ComicArchive.ImageBounds::class.java)
            .apply { isAccessible = true }
        return method.invoke(adapter, bounds) as Boolean
    }

    private fun invokeEnsureFullOrTiledPage(adapter: Any) {
        val method = adapter.javaClass.getDeclaredMethod(
            "ensureFullOrTiledPage",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        method.invoke(adapter, 0, 100, 0, false)
    }

    private fun invokeClose(adapter: Any) {
        adapter.javaClass.getDeclaredMethod("close")
            .apply { isAccessible = true }
            .invoke(adapter)
    }

    private class FakeReaderSession(
        private val bounds: ComicArchive.ImageBounds? = null,
        private val onFullDecode: (targetWidth: Int, targetHeight: Int, bounded: Boolean) -> Unit = { _, _, _ -> }
    ) : ComicArchive.ImageReaderSession {
        override fun isPdfSource(): Boolean = false

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? = bounds

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? = null

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? {
            onFullDecode(targetWidth, 0, false)
            return null
        }

        override fun decodeImageForPage(
            entryName: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? {
            onFullDecode(targetWidth, targetHeight, true)
            return null
        }

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? = null

        override fun close() = Unit
    }
}
