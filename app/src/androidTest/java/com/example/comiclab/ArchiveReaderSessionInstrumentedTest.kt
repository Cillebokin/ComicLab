package com.example.comiclab

import android.graphics.Bitmap
import android.graphics.Rect
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveReaderSessionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val temporaryFolder = TemporaryFolder(instrumentation.targetContext.cacheDir)

    @Test
    fun sevenZipReaderSessionReadsEntriesAndReleasesArchiveAndCacheOnClose() {
        val archive = copySevenZipFixture()
        val cacheRoot = File(temporaryFolder.root, "sevenzip-cache").apply { mkdirs() }
        val marker = File(cacheRoot, "keep.txt").apply { writeText("keep") }
        val session = ComicArchive.openReaderSession(archive, cacheRoot)

        assertFalse(session.isPdfSource())
        assertNoOpenFileDescriptors(archive)
        assertEquals(ComicArchive.ImageBounds(160, 320), session.readBounds(IMAGE_ENTRY))
        assertNoOpenFileDescriptors(archive)

        val bitmap = session.decodeImageForWidth(IMAGE_ENTRY, 80)
        assertNotNull(bitmap)
        try {
            assertEquals(80, bitmap!!.width)
            assertEquals(160, bitmap.height)
        } finally {
            bitmap?.recycle()
        }
        assertNoOpenFileDescriptors(archive)

        val region = session.decodeRegionForWidth(IMAGE_ENTRY, Rect(0, 0, 160, 320), 80)
        assertNotNull(region)
        try {
            assertEquals(80, region!!.width)
            assertEquals(160, region.height)
        } finally {
            region?.recycle()
        }
        assertNoOpenFileDescriptors(archive)

        session.close()
        session.close()
        assertNoOpenFileDescriptors(archive)
        assertEquals("keep", marker.readText())
        assertEquals(listOf(marker.name), cacheRoot.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun invalidSevenZipIndexOpenClosesArchiveFileDescriptor() {
        val archive = temporaryFolder.newFile("reader-session-invalid.7z").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
        }
        val cacheRoot = File(temporaryFolder.root, "invalid-sevenzip-cache").apply { mkdirs() }
        val session = ComicArchive.openReaderSession(archive, cacheRoot)

        val failure = runCatching { session.readBounds(IMAGE_ENTRY) }.exceptionOrNull()

        assertNotNull("Invalid SevenZip index open should report its failure", failure)
        assertNoOpenFileDescriptors(archive)
        session.close()
        assertNoOpenFileDescriptors(archive)
    }

    @Test
    fun zipReaderSessionHoldsArchiveUntilIdempotentClose() {
        val archive = temporaryFolder.newFile("reader-session.cbz")
        writeZip(archive)
        val cacheRoot = File(temporaryFolder.root, "zip-cache").apply { mkdirs() }

        assertNoOpenFileDescriptors(archive)
        val session = ComicArchive.openReaderSession(archive, cacheRoot)
        assertTrue("ZIP reader session did not retain its archive handle", hasOpenFileDescriptor(archive))
        assertEquals(ComicArchive.ImageBounds(120, 240), session.readBounds(IMAGE_ENTRY))

        session.close()
        session.close()
        assertNoOpenFileDescriptors(archive)
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
    }

    private fun copySevenZipFixture(): File {
        return temporaryFolder.newFile("reader-session.7z").also { target ->
            instrumentation.context.assets.open("task22/decode-images.7z").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun writeZip(archive: File) {
        val image = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        val imageBytes = try {
            ByteArrayOutputStream().use { output ->
                image.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            image.recycle()
        }

        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(IMAGE_ENTRY))
            zip.write(imageBytes)
            zip.closeEntry()
        }
    }

    private fun hasOpenFileDescriptor(file: File): Boolean {
        val canonical = file.canonicalFile
        val descriptors = checkNotNull(File("/proc/self/fd").listFiles()) {
            "Cannot inspect this process's file descriptors"
        }
        return descriptors.any { descriptor ->
            runCatching { File(Os.readlink(descriptor.absolutePath)).canonicalFile == canonical }
                .getOrDefault(false)
        }
    }

    private fun assertNoOpenFileDescriptors(file: File) {
        assertFalse(
            "Archive file descriptor still open for ${file.name}",
            hasOpenFileDescriptor(file)
        )
    }

    private companion object {
        const val IMAGE_ENTRY = "comic/page_001.png"
    }
}
