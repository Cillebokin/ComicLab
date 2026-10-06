package com.example.comiclab

import android.graphics.pdf.PdfDocument
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveEntryListerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val temporaryFolder = TemporaryFolder(instrumentation.targetContext.cacheDir)

    @Test
    fun sevenZipListsImageFilesNaturallyAndClosesFileDescriptors() {
        val archive = copyFixture("entries.7z", "comic.CB7")
        val expected = listOf(".hidden.jpg", "2.PNG", "10.jpg", "folder/1.gif")

        repeat(3) {
            assertEquals(expected, ComicArchive.imageEntries(archive))
            assertNoOpenFileDescriptors(archive)
            assertEquals(".hidden.jpg", ComicArchive.firstImageEntryIfFirstFileIsImage(archive))
            assertNoOpenFileDescriptors(archive)
        }
    }

    @Test
    fun sevenZipFirstFileMustBeImageAndEmptyArchiveReturnsNoEntries() {
        val archive = copyFixture("non-image-first.7z", "comic.7z")
        assertEquals(listOf("1.jpg", "2.jpg"), ComicArchive.imageEntries(archive))
        assertNoOpenFileDescriptors(archive)
        assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(archive))
        assertNoOpenFileDescriptors(archive)

        val empty = copyFixture("empty.7z", "empty.7z")
        assertTrue(ComicArchive.imageEntries(empty).isEmpty())
        assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(empty))
        assertNoOpenFileDescriptors(empty)
    }

    @Test
    fun pdfListsPageNamesInOrderAndClosesFileDescriptors() {
        val pdf = temporaryFolder.newFile("comic.PDF")
        val document = PdfDocument()
        try {
            for (pageNumber in 1..2) {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(100, 200, pageNumber).create()
                )
                page.canvas.drawRGB(255, 255, 255)
                document.finishPage(page)
            }
            pdf.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }

        repeat(3) {
            assertEquals(listOf("pdf_page_000000", "pdf_page_000001"), ComicArchive.imageEntries(pdf))
            assertNoOpenFileDescriptors(pdf)
            assertEquals("pdf_page_000000", ComicArchive.firstImageEntryIfFirstFileIsImage(pdf))
            assertNoOpenFileDescriptors(pdf)
        }
    }

    @Test
    fun sharedPdfReaderSessionReadsBoundsRendersPagesAndClosesAfterLastLease() {
        val pdf = temporaryFolder.newFile("session.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(100, 200, 1).create()
            )
            page.canvas.drawRGB(255, 255, 255)
            document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }

        val entry = "pdf_page_000000"
        repeat(2) {
            val sharedSession = ComicArchive.SharedImageReaderSession.wrap(
                ComicArchive.openReaderSession(pdf, temporaryFolder.root)
            )
            val primaryLease = sharedSession.acquire()
            val secondaryLease = sharedSession.acquire()
            assertTrue(primaryLease.isPdfSource())
            assertEquals(ComicArchive.ImageBounds(100, 200), primaryLease.readBounds(entry))
            assertNull(primaryLease.readBounds("pdf_page_000001"))
            assertNull(primaryLease.readBounds("wrong-entry"))

            val widthBitmap = assertNotNullBitmap(primaryLease.decodeImageForWidth(entry, 50))
            try {
                assertEquals(50, widthBitmap.width)
                assertEquals(100, widthBitmap.height)
            } finally {
                widthBitmap.recycle()
            }

            val previewBitmap = assertNotNullBitmap(primaryLease.decodePreviewForWidth(entry, 40))
            try {
                assertEquals(40, previewBitmap.width)
                assertEquals(80, previewBitmap.height)
            } finally {
                previewBitmap.recycle()
            }

            val fitBitmap = assertNotNullBitmap(primaryLease.decodeImageForPage(entry, 50, 50))
            try {
                assertEquals(25, fitBitmap.width)
                assertEquals(50, fitBitmap.height)
            } finally {
                fitBitmap.recycle()
            }

            val regionBitmap = assertNotNullBitmap(
                primaryLease.decodeRegionForWidth(
                    entry,
                    android.graphics.Rect(-10, 20, 60, 120),
                    120
                )
            )
            try {
                assertEquals(120, regionBitmap.width)
                assertEquals(200, regionBitmap.height)
            } finally {
                regionBitmap.recycle()
            }

            assertNull(primaryLease.decodeImageForWidth("pdf_page_000001", 50))
            primaryLease.close()
            primaryLease.close()
            assertEquals(ComicArchive.ImageBounds(100, 200), secondaryLease.readBounds(entry))
            secondaryLease.close()
            secondaryLease.close()
            assertNull(secondaryLease.decodeImageForWidth(entry, 50))
            assertNoOpenFileDescriptors(pdf)
        }

        for (name in listOf("empty.pdf", "damaged.pdf")) {
            val invalidPdf = temporaryFolder.newFile(name)
            if (name.startsWith("damaged")) {
                invalidPdf.writeText("not a pdf")
            }
            val opened = runCatching {
                ComicArchive.openReaderSession(invalidPdf, temporaryFolder.root)
            }
            assertFalse("Invalid PDF should fail to open: $name", opened.isSuccess)
            assertNoOpenFileDescriptors(invalidPdf)
        }
    }

    @Test
    fun damagedSevenZipAndPdfReturnFallbacksAndCloseFileDescriptors() {
        for (extension in listOf("7z", "pdf")) {
            val damaged = temporaryFolder.newFile("damaged.$extension").apply {
                writeText("not an archive")
            }
            assertTrue(ComicArchive.imageEntries(damaged).isEmpty())
            assertNoOpenFileDescriptors(damaged)
            assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(damaged))
            assertNoOpenFileDescriptors(damaged)
        }
    }

    private fun copyFixture(assetName: String, fileName: String): File {
        return temporaryFolder.newFile(fileName).also { target ->
            instrumentation.context.assets.open("task21/$assetName").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun assertNoOpenFileDescriptors(file: File) {
        val descriptors = checkNotNull(File("/proc/self/fd").listFiles()) {
            "Cannot inspect this process's file descriptors"
        }
        val stillOpen = descriptors.mapNotNull { descriptor ->
            runCatching { File(Os.readlink(descriptor.absolutePath)).canonicalFile }.getOrNull()
        }.filter { it == file.canonicalFile }
        assertTrue("Archive file descriptors still open for ${file.name}: $stillOpen", stillOpen.isEmpty())
    }

    private fun assertNotNullBitmap(bitmap: android.graphics.Bitmap?): android.graphics.Bitmap {
        return checkNotNull(bitmap) { "Expected PDF page rendering to return a bitmap" }
    }
}
