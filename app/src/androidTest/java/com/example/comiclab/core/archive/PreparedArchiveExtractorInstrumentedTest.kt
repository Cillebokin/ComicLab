package com.example.comiclab.core.archive

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.ComicArchive
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
class PreparedArchiveExtractorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val temporaryFolder = TemporaryFolder(instrumentation.targetContext.cacheDir)

    @Test
    fun manifestReplacementPublishesCompleteJsonAndCleansTemporaryFilesOnDevice() {
        val output = File(temporaryFolder.root, "manifest-replacement").apply { mkdirs() }
        val manifest = File(output, "reader_manifest.json")

        ComicArchive.writePreparedReaderManifest(output, listOf("old.jpg"))
        ComicArchive.writePreparedReaderManifest(output, listOf("chapter/new.jpg", "quote/\"page\".PNG"))

        assertEquals("[\"chapter\\/new.jpg\",\"quote\\/\\\"page\\\".PNG\"]", manifest.readText())
        assertEquals(listOf("chapter/new.jpg", "quote/\"page\".PNG"), ComicArchive.preparedImageEntries(output))
        assertEquals(listOf(manifest.name), output.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun realSevenZipFullExtractionKeepsOrderManifestBytesAndClosesArchive() {
        val archive = copyFixture()
        val output = File(temporaryFolder.root, "full")
        val progress = mutableListOf<Pair<Int, Int>>()

        val files = ComicArchive.extractImagesToDirectory(archive, output) { done, total -> progress += done to total }

        assertEquals(ENTRIES, ComicArchive.preparedImageEntries(output))
        assertEquals("[\".hidden.jpg\",\"2.PNG\",\"10.jpg\",\"folder\\/1.gif\"]", File(output, "reader_manifest.json").readText())
        assertEquals(listOf(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4), progress)
        assertEquals(4, files.size)
        files.forEachIndexed { index, file ->
            assertTrue(file.name.startsWith("%06d_".format(index)))
            assertTrue(file.length() > 0L)
            val single = File(temporaryFolder.root, "single-$index.bin")
            assertTrue(ComicArchive.extractImageEntryToFile(archive, ENTRIES[index], single))
            assertTrue(file.readBytes().contentEquals(single.readBytes()))
        }
        assertFalse(hasOpenFileDescriptor(archive))
    }

    @Test
    fun realSevenZipExtractorReusesSlotsSkipsMissingAndReleasesNativeArchiveOnClose() {
        val archive = copyFixture()
        val output = File(temporaryFolder.root, "slots")
        val extractor = ComicArchive.openPreparedImageExtractor(archive, output)
        assertTrue(hasOpenFileDescriptor(archive))
        try {
            val extracted = checkNotNull(extractor.extract(3, "folder/1.gif"))
            val bytes = extracted.readBytes()
            assertTrue(bytes.isNotEmpty())
            extracted.writeText("cached")
            assertEquals("cached", checkNotNull(extractor.extract(3, "folder/1.gif")).readText())
            assertNull(extractor.extract(4, "missing.jpg"))
            assertTrue(output.listFiles().orEmpty().none { it.name.endsWith(".part") })
            assertFalse(File(output, "reader_manifest.json").exists())
        } finally {
            extractor.close()
        }
        assertFalse(hasOpenFileDescriptor(archive))
    }

    @Test
    fun realSevenZipPreparedBatchCountsMissingAttemptsAndCancellationKeepsPartialFiles() {
        val archive = copyFixture()
        val output = File(temporaryFolder.root, "batch")
        val progress = mutableListOf<Pair<Int, Int>>()
        val files = ComicArchive.extractPreparedImagesToDirectory(archive, listOf(
            ComicArchive.PreparedImageEntry(5, "missing.jpg"),
            ComicArchive.PreparedImageEntry(2, "2.PNG"),
            ComicArchive.PreparedImageEntry(0, ".hidden.jpg")
        ), output) { done, total -> progress += done to total }
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(listOf("000002_", "000000_"), files.map { it.name.take(7) })
        assertFalse(File(output, "reader_manifest.json").exists())
        assertFalse(hasOpenFileDescriptor(archive))

        val cancelledOutput = File(temporaryFolder.root, "cancelled")
        var cancelled = false
        val cancelledProgress = mutableListOf<Pair<Int, Int>>()
        val partial = ComicArchive.extractImagesToDirectory(archive, cancelledOutput, { !cancelled }) { done, total ->
            cancelledProgress += done to total
            if (done == 1) cancelled = true
        }
        assertEquals(1, partial.size)
        assertEquals(listOf(0 to 4, 1 to 4), cancelledProgress)
        assertEquals(ENTRIES, ComicArchive.preparedImageEntries(cancelledOutput))
        assertFalse(hasOpenFileDescriptor(archive))
    }

    private fun copyFixture(): File = temporaryFolder.newFile("prepared.7z").also { target ->
        instrumentation.context.assets.open("task21/entries.7z").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun hasOpenFileDescriptor(file: File): Boolean {
        val canonical = file.canonicalFile
        return checkNotNull(File("/proc/self/fd").listFiles()).any { descriptor ->
            runCatching { File(Os.readlink(descriptor.absolutePath)).canonicalFile == canonical }.getOrDefault(false)
        }
    }

    private companion object {
        val ENTRIES = listOf(".hidden.jpg", "2.PNG", "10.jpg", "folder/1.gif")
    }
}
