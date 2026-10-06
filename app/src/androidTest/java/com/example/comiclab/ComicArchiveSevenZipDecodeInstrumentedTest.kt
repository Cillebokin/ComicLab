package com.example.comiclab

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComicArchiveSevenZipDecodeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val temporaryFolder = TemporaryFolder(instrumentation.targetContext.cacheDir)

    @Test
    fun repeatedSevenZipImageDecodesReturnBitmapsAndCleanTransientCacheFiles() {
        val archive = copyFixture()
        val cacheRoot = File(temporaryFolder.root, "decoder-cache").apply { mkdirs() }
        val marker = File(cacheRoot, "keep.txt").apply { writeText("keep") }

        repeat(3) {
            val bitmap = ComicArchive.decodeImage(
                file = archive,
                entryName = IMAGE_ENTRY,
                maxSize = 80,
                cacheRoot = cacheRoot
            )

            assertNotNull(bitmap)
            try {
                assertEquals(40, bitmap!!.width)
                assertEquals(80, bitmap.height)
                assertEquals(Bitmap.Config.RGB_565, bitmap.config)
            } finally {
                bitmap?.recycle()
            }
            assertCacheRootPreserved(cacheRoot, marker)
        }

        val widthBitmap = ComicArchive.decodeImageForWidth(
            file = archive,
            entryName = IMAGE_ENTRY,
            targetWidth = 80,
            cacheRoot = cacheRoot
        )
        assertNotNull(widthBitmap)
        try {
            assertEquals(80, widthBitmap!!.width)
            assertEquals(160, widthBitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, widthBitmap.config)
        } finally {
            widthBitmap?.recycle()
        }
        assertCacheRootPreserved(cacheRoot, marker)

        assertNull(ComicArchive.decodeImage(archive, "comic/missing.png", 80, cacheRoot))
        assertCacheRootPreserved(cacheRoot, marker)
    }

    @Test
    fun sevenZipBatchDecodeReturnsImageAndNullForMissingEntryAndCleansCache() {
        val archive = copyFixture()
        val cacheRoot = File(temporaryFolder.root, "batch-cache").apply { mkdirs() }
        val marker = File(cacheRoot, "keep.txt").apply { writeText("keep") }
        val results = CopyOnWriteArrayList<Pair<String, Bitmap?>>()

        ComicArchive.decodeImages(
            file = archive,
            entryNames = listOf(IMAGE_ENTRY, "comic/missing.png"),
            maxSize = 80,
            cacheRoot = cacheRoot
        ) { entryName, bitmap ->
            results += entryName to bitmap
            true
        }

        assertEquals(setOf(IMAGE_ENTRY, "comic/missing.png"), results.map { it.first }.toSet())
        val decoded = results.single { it.first == IMAGE_ENTRY }.second
        assertNotNull(decoded)
        try {
            assertEquals(40, decoded!!.width)
            assertEquals(80, decoded.height)
            assertEquals(Bitmap.Config.RGB_565, decoded.config)
        } finally {
            decoded?.recycle()
        }
        assertNull(results.single { it.first == "comic/missing.png" }.second)
        assertCacheRootPreserved(cacheRoot, marker)
    }

    private fun copyFixture(): File {
        return temporaryFolder.newFile("decode-images.7z").also { target ->
            instrumentation.context.assets.open("task22/decode-images.7z").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun assertCacheRootPreserved(cacheRoot: File, marker: File) {
        assertEquals("keep", marker.readText())
        assertEquals(listOf(marker.name), cacheRoot.listFiles().orEmpty().map { it.name })
    }

    private companion object {
        const val IMAGE_ENTRY = "comic/page_001.png"
    }
}
