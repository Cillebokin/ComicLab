package com.example.comiclab.core.archive

import com.example.comiclab.ComicArchive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveEntryListerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun zip(vararg names: String): File {
        val file = File(temporaryFolder.root, "pages-${temporaryFolder.root.listFiles()!!.size}.cbz")
        ZipOutputStream(file.outputStream()).use { output ->
            for (name in names) {
                output.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) output.write(byteArrayOf(1))
                output.closeEntry()
            }
        }
        return file
    }

    private fun imageEntries(file: File): List<String> = ComicArchive.imageEntries(file).also { result ->
        if (file.extension in listOf("zip", "cbz")) {
            assertEquals(result, ArchiveEntryLister.imageEntries(
                file,
                sevenZipImages = { error("ZIP listing must not open SevenZip") },
                pdfImages = { error("ZIP listing must not open PDF") }
            ))
        }
    }

    @Test fun extractedPureHelpersPreserveFilteringAndNaturalOrder() {
        assertEquals(listOf(".hidden.JPG", "a2.jpg", "a10.jpg"),
            ArchiveEntryLister.imageEntryNames(sequenceOf("a10.jpg", "note.txt", "a2.jpg", ".hidden.JPG")))
        assertEquals(listOf("a2.txt", "a10.jpg"),
            ArchiveEntryLister.fileEntryNames(sequenceOf("a10.jpg", "a2.txt")))
        for (name in listOf("a.JPG", "a.jpeg", "a.png", "a.webp", "a.gif", "a.bmp")) {
            assertTrue(ArchiveEntryLister.isImageEntryName(name))
        }
        for (name in listOf("a", "a.txt", "a.jpg/", "pdf_page_0")) {
            assertFalse(ArchiveEntryLister.isImageEntryName(name))
        }
    }

    @Test fun hiddenImagesIncludedDirectoriesAndOtherFilesExcluded() {
        val file = zip("dir.jpg/", "notes.txt", "page.GIF", ".hidden.PNG", "nested/page.jpeg", "page.bmp", "page.webp", "page.jpg")
        assertEquals(listOf(".hidden.PNG", "nested/page.jpeg", "page.bmp", "page.GIF", "page.jpg", "page.webp"), imageEntries(file))
        assertEquals(".hidden.PNG", ComicArchive.firstImageEntryIfFirstFileIsImage(file))
    }

    @Test fun firstNaturallySortedFileMustBeImage() {
        assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(zip("0.txt", "1.jpg", "000/")))
        assertEquals("2.jpg", ComicArchive.firstImageEntryIfFirstFileIsImage(zip("10.txt", "2.jpg", "1/")))
    }

    @Test fun pdfTokensAreAcceptedOnlyByFirstFileCheck() {
        for (token in listOf("pdf_page_0", "pdf_page_+1", "pdf_page_-0", "pdf_page_2147483647")) {
            val file = zip(token)
            assertEquals(emptyList<String>(), imageEntries(file))
            assertEquals(token, ComicArchive.firstImageEntryIfFirstFileIsImage(file))
        }
        for (token in listOf("pdf_page_-1", "pdf_page_2147483648", "pdf_page_a", "PDF_PAGE_0")) {
            assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(zip(token)))
        }
    }

    @Test fun naturalSortPreservesHugeNumbersLeadingZerosCaseAndNestedPaths() {
        val file = zip("p999999999999999999999.jpg", "p1000000000000000000000.jpg", "p10.jpg", "p2.jpg", "p02.jpg", "p002.jpg", "p000.jpg", "p0.jpg", "a/10.jpg", "a/2.jpg", "b.jpg", "B.jpg")
        assertEquals(listOf("a/2.jpg", "a/10.jpg", "B.jpg", "b.jpg", "p0.jpg", "p000.jpg", "p002.jpg", "p02.jpg", "p2.jpg", "p10.jpg", "p999999999999999999999.jpg", "p1000000000000000000000.jpg"), imageEntries(file))
    }

    @Test fun emptyZipHasNoImagesOrFirstFile() {
        val file = zip()
        assertEquals(emptyList<String>(), imageEntries(file))
        assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(file))
    }

    @Test fun damagedAndUnsupportedArchivesReturnEmptyOrNull() {
        for (extension in listOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "pdf", "tar", "gz", "txt")) {
            val file = temporaryFolder.newFile("damaged.$extension").apply { writeText("invalid archive") }
            assertEquals(extension, emptyList<String>(), imageEntries(file))
            assertNull(extension, ComicArchive.firstImageEntryIfFirstFileIsImage(file))
        }
        val missing = File(temporaryFolder.root, "missing.zip")
        val directory = temporaryFolder.newFolder("directory.cbz")
        for (file in listOf(missing, directory)) {
            assertEquals(emptyList<String>(), imageEntries(file))
            assertNull(ComicArchive.firstImageEntryIfFirstFileIsImage(file))
        }
    }
}
