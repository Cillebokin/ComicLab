package com.example.comiclab

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
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

    private fun createPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
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
