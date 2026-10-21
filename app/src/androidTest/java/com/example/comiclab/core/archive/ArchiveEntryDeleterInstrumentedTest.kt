package com.example.comiclab.core.archive

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.ComicArchive
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveEntryDeleterInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val temporaryFolder = TemporaryFolder(instrumentation.targetContext.cacheDir)

    @Test
    fun sevenZipAndCb7DeletionIsRejectedWithoutChangingFormatOrReadability() {
        for (extension in listOf("7z", "cb7", "7Z", "CB7")) {
            assertReadOnlyArchive("task22/decode-images.7z", "seven-zip.$extension")
        }
    }

    @Test
    fun rarAndCbrDeletionIsRejectedWithoutChangingFormatOrReadability() {
        for ((fixture, extension) in listOf(
            "rar/rar3-image.rar" to "rar", "rar/rar3-image.rar" to "RAR",
            "rar/rar5-image.cbr" to "cbr", "rar/rar5-image.cbr" to "CBR"
        )) {
            assertReadOnlyArchive(fixture, "rar-image.$extension")
        }
    }

    private fun assertReadOnlyArchive(fixture: String, name: String) {
        val archive = copyFixture(fixture, name)
        val originalBytes = archive.readBytes()
        val originalTime = archive.lastModified()
        val originalEntries = ComicArchive.imageEntries(archive)
        assertTrue(archive.name, originalEntries.isNotEmpty())
        assertTrue(ComicArchive.isSupportedArchive(archive))
        assertFalse(ComicArchive.canDeleteEntry(archive))
        assertFalse(ComicArchive.deleteEntry(archive, originalEntries.first()))
        assertFalse(ComicArchive.deleteEntry(archive, "missing-entry.jpg"))
        assertArrayEquals(originalBytes, archive.readBytes())
        assertEquals(originalTime, archive.lastModified())
        assertEquals(originalEntries, ComicArchive.imageEntries(archive))
        assertFalse(File(archive.parentFile, ".ComicLabBackups").exists())
        assertFalse(archive.parentFile!!.listFiles().orEmpty().any { ".delete_" in it.name || ".restore_" in it.name })
        assertReadablePage(archive, originalEntries.first())
    }

    @Test
    fun zipAndCbzDeletionKeepsExtensionZipBytesBackupAndDecodableRemainingPage() {
        val image = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        val png = try {
            image.eraseColor(Color.GREEN)
            ByteArrayOutputStream().use { output ->
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            image.recycle()
        }
        for (extension in listOf("zip", "cbz", "ZIP", "CBZ")) {
            val archive = File(temporaryFolder.root, "comic.$extension")
            ZipOutputStream(archive.outputStream()).use { output ->
                listOf("pages/001.png", "pages/002.png").forEach { name ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(png)
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry("notes.txt"))
                output.write("keep".toByteArray())
                output.closeEntry()
            }
            val original = archive.readBytes()
            assertTrue(ComicArchive.canDeleteEntry(archive))
            assertTrue(ComicArchive.deleteEntry(archive, "pages/002.png"))
            assertTrue(archive.isFile)
            assertEquals(extension, archive.extension)
            assertArrayEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), archive.readBytes().take(4).toByteArray())
            assertEquals(listOf("pages/001.png"), ComicArchive.imageEntries(archive))
            ZipFile(archive).use { zip ->
                assertArrayEquals(png, zip.getInputStream(zip.getEntry("pages/001.png")).use { it.readBytes() })
                assertEquals("keep", zip.getInputStream(zip.getEntry("notes.txt")).bufferedReader().use { it.readText() })
            }
            assertArrayEquals(original, persistentBackups(archive).single().readBytes())
            assertReadablePage(archive, "pages/001.png")
        }
    }

    @Test
    fun zipMissingEntryLeavesOriginalAndItsPersistentBackupUntouched() {
        val archive = File(temporaryFolder.root, "missing.cbz")
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("001.png"))
            output.write(byteArrayOf(1, 2, 3))
            output.closeEntry()
        }
        val originalBytes = archive.readBytes()

        assertFalse(ComicArchive.deleteEntry(archive, "missing-entry.jpg"))

        assertTrue(archive.isFile)
        assertArrayEquals(originalBytes, archive.readBytes())
        val backups = persistentBackups(archive)
        assertEquals(1, backups.size)
        assertArrayEquals(originalBytes, backups.single().readBytes())
        assertFalse(archive.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("missing.cbz.delete_") })
    }

    private fun assertReadablePage(archive: File, entry: String) {
        val cache = File(temporaryFolder.root, "${archive.name}-cache").apply { mkdirs() }
        ComicArchive.openReaderSession(archive, cache).use { session ->
            assertNotNull(session.readBounds(entry))
            val bitmap = session.decodeImageForWidth(entry, 16)
            assertNotNull("Cannot decode $entry in ${archive.name}", bitmap)
            bitmap?.recycle()
        }
    }

    private fun copyFixture(fixture: String, targetName: String): File {
        return File(temporaryFolder.root, targetName).also { target ->
            instrumentation.context.assets.open(fixture).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun persistentBackups(archive: File): List<File> =
        File(archive.parentFile, ".ComicLabBackups")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == archive.extension }

}
