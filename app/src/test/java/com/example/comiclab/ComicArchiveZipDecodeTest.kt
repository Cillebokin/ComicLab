package com.example.comiclab

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComicArchiveZipDecodeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun batchZipDecodeReturnsPreviewBitmapsWithoutLeavingCacheFiles() {
        val archive = File(temporaryFolder.root, "pages.cbz")
        writeZip(archive, "pages/1.png", createPng())
        val cacheRoot = File(temporaryFolder.root, "cache").apply { mkdirs() }
        val decodedBitmap = AtomicReference<Bitmap?>()

        ComicArchive.decodeImages(
            file = archive,
            entryNames = listOf("pages/1.png"),
            maxSize = 360,
            cacheRoot = cacheRoot
        ) { entryName, bitmap ->
            assertEquals("pages/1.png", entryName)
            decodedBitmap.set(bitmap)
            true
        }

        assertNotNull(decodedBitmap.get())
        assertEquals(120, decodedBitmap.get()!!.width)
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun streamDecoderReopensEntryForBoundsAndPixelDecode() {
        val imageBytes = createPng()
        var streamOpenCount = 0

        val bitmap = ComicArchive.decodeImageFromStreamFactory(
            openInputStream = {
                streamOpenCount++
                ByteArrayInputStream(imageBytes)
            },
            preferredConfig = Bitmap.Config.RGB_565,
            sampleSize = { width, height ->
                if (width > 60 || height > 60) 2 else 1
            }
        )

        assertEquals(2, streamOpenCount)
        assertNotNull(bitmap)
        assertEquals(60, bitmap!!.width)
        assertEquals(120, bitmap.height)
    }

    @Test
    fun oneShotZipDecodeStillReturnsSampledBitmap() {
        val archive = File(temporaryFolder.root, "cover.cbz")
        writeZip(archive, "cover.png", createPng())
        val cacheRoot = File(temporaryFolder.root, "oneshot-cache").apply { mkdirs() }

        val bitmap = ComicArchive.decodeImage(archive, "cover.png", maxSize = 64, cacheRoot = cacheRoot)

        assertNotNull(bitmap)
        assertTrue(bitmap!!.width <= 64)
        assertTrue(bitmap.height <= 64)
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun largerImagePreservesBoundsAndSamplesToRequestedSize() {
        val archive = File(temporaryFolder.root, "large.cbz")
        writeZip(archive, "large.png", createPng(1600, 3200))
        val cacheRoot = File(temporaryFolder.root, "large-cache")
        ComicArchive.openReaderSession(archive, cacheRoot).use { session ->
            assertEquals(ComicArchive.ImageBounds(1600, 3200), session.readBounds("large.png"))
            val bitmap = session.decodeImageForPage("large.png", 200, 400)!!
            assertEquals(200, bitmap.width)
            assertEquals(400, bitmap.height)
            bitmap.recycle()
        }
        val preview = ComicArchive.decodeImage(archive, "large.png", 300, cacheRoot)!!
        assertEquals(150, preview.width)
        assertEquals(300, preview.height)
        preview.recycle()
    }

    @Test
    fun nonpositiveTargetWidthPreservesOriginalImageSize() {
        val archive = File(temporaryFolder.root, "invalid-width.cbz")
        writeZip(archive, "page.png", createPng())
        for (width in listOf(0, -1)) {
            val bitmap = ComicArchive.decodeImageForWidth(archive, "page.png", width, temporaryFolder.root)!!
            assertEquals(120, bitmap.width)
            assertEquals(240, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun missingAndCorruptEntriesReturnNullInSingleAndBatchRequests() {
        val archive = File(temporaryFolder.root, "corrupt.cbz")
        writeZip(archive, "broken.png", byteArrayOf(1, 2, 3))
        for (entry in listOf("missing.png", "broken.png")) {
            assertNull(ComicArchive.decodeImage(archive, entry, 64, temporaryFolder.root))
            assertNull(ComicArchive.decodeImageForWidth(archive, entry, 64, temporaryFolder.root))
        }
        val results = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        ComicArchive.decodeImages(archive, listOf("missing.png", "broken.png"), 64, temporaryFolder.root) { name, bitmap ->
            results[name] = bitmap == null
            true
        }
        assertEquals(mapOf("missing.png" to true, "broken.png" to true), results)
    }

    @Test
    fun repeatedZipRequestsLeaveExistingCacheRootFilesReadable() {
        val archive = File(temporaryFolder.root, "repeated.cbz")
        writeZip(archive, "page.png", createPng())
        val cacheRoot = temporaryFolder.newFolder("existing-cache")
        val existing = File(cacheRoot, "existing.txt").apply { writeText("keep") }
        repeat(3) {
            val bitmap = ComicArchive.decodeImageForWidth(archive, "page.png", 60, cacheRoot)!!
            assertEquals(60, bitmap.width)
            assertEquals(120, bitmap.height)
            bitmap.recycle()
            assertEquals("keep", existing.readText())
            assertEquals(listOf("existing.txt"), cacheRoot.listFiles()!!.map { it.name })
        }
    }

    private fun createPng(width: Int = 120, height: Int = 240): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun writeZip(file: File, entryName: String, bytes: ByteArray) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}
