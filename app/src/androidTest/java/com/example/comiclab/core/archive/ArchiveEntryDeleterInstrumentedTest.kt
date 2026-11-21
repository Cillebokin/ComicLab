package com.example.comiclab.core.archive

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.ComicArchive
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
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
    fun gbkAndUtf8ZipAndCbzStayReadableAfterDeletingOnePage() {
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
        val first = "漫画/第001页.png"
        val second = "漫画/第002页.png"
        val note = "漫画/说明.txt"
        val gbk = Charset.forName("GBK")
        for ((extension, charset) in listOf(
            "zip" to gbk,
            "cbz" to Charsets.UTF_8,
            "ZIP" to Charsets.UTF_8,
            "CBZ" to gbk
        )) {
            val archive = File(temporaryFolder.root, "comic.$extension")
            ZipOutputStream(archive.outputStream(), charset).use { output ->
                listOf(first, second).forEach { name ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(png)
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry(note))
                output.write("keep".toByteArray())
                output.closeEntry()
            }
            assertTrue(ComicArchive.canDeleteEntry(archive))
            assertEquals(listOf(first, second), ComicArchive.imageEntries(archive))
            assertReadablePage(archive, first)
            val extracted = File(temporaryFolder.root, "${archive.name}-extracted.png")
            assertTrue(ComicArchive.extractImageEntryToFile(archive, first, extracted))
            assertArrayEquals(png, extracted.readBytes())
            val prepared = ComicArchive.extractImagesToDirectory(
                archive, File(temporaryFolder.root, "${archive.name}-prepared")
            ) { _, _ -> }
            assertEquals(2, prepared.size)
            prepared.forEach { assertArrayEquals(png, it.readBytes()) }

            assertTrue(ComicArchive.deleteEntry(archive, second))
            assertTrue(archive.isFile)
            assertEquals(extension, archive.extension)
            assertArrayEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), archive.readBytes().take(4).toByteArray())
            assertEquals(listOf(first), ComicArchive.imageEntries(archive))
            ZipFile(archive).use { zip ->
                assertArrayEquals(png, zip.getInputStream(zip.getEntry(first)).use { it.readBytes() })
                assertEquals("keep", zip.getInputStream(zip.getEntry(note)).bufferedReader().use { it.readText() })
                assertEquals(null, zip.getEntry(second))
            }
            assertReplacementFilesCleaned(archive)
            assertReadablePage(archive, first)
        }
    }

    @Test
    fun gbkFallbackRespectsFlaggedUtf8NamesInTheSameArchive() {
        val archive = File(temporaryFolder.root, "mixed-encoding.cbz")
        val gbkName = "漫画/第1页.png"
        val utf8Name = "漫画/星🌟.png"
        val utf8Bytes = utf8Name.encodeToByteArray()
        val placeholder = "x".repeat(utf8Bytes.size)
        val gbkContent = byteArrayOf(1, 2, 3)
        val utf8Content = byteArrayOf(4, 5, 6)
        ZipOutputStream(archive.outputStream(), Charset.forName("GBK")).use { output ->
            for ((name, content) in listOf(placeholder to utf8Content, gbkName to gbkContent)) {
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }

        // Change the first entry's name and UTF-8 flag in both headers, keeping their lengths.
        val bytes = archive.readBytes()
        val zipHeaders = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val endOfCentralDirectory = bytes.size - 22 // This fixture has no archive comment.
        val centralDirectory = zipHeaders.getInt(endOfCentralDirectory + 16)
        for ((headerOffset, flagsOffset, nameOffset) in listOf(
            Triple(0, 6, 30),
            Triple(centralDirectory, 8, 46)
        )) {
            val start = headerOffset + nameOffset
            assertEquals(placeholder, bytes.copyOfRange(start, start + utf8Bytes.size).decodeToString())
            val flags = headerOffset + flagsOffset
            zipHeaders.putShort(flags, (zipHeaders.getShort(flags).toInt() or (1 shl 11)).toShort())
            utf8Bytes.copyInto(bytes, destinationOffset = start)
        }
        archive.writeBytes(bytes)

        assertEquals(setOf(gbkName, utf8Name), ComicArchive.imageEntries(archive).toSet())
        val extracted = File(temporaryFolder.root, "mixed-extracted.png")
        assertTrue(ComicArchive.extractImageEntryToFile(archive, gbkName, extracted))
        assertArrayEquals(gbkContent, extracted.readBytes())
        assertTrue(ComicArchive.extractImageEntryToFile(archive, utf8Name, extracted))
        assertArrayEquals(utf8Content, extracted.readBytes())
    }

    @Test
    fun gbkZipMissingEntryLeavesTheOriginalAndLegacyBackupUntouched() {
        val archive = File(temporaryFolder.root, "missing.cbz")
        val entry = "漫画/第1页.png"
        ZipOutputStream(archive.outputStream(), Charset.forName("GBK")).use { output ->
            output.putNextEntry(ZipEntry(entry))
            output.write(byteArrayOf(1, 2, 3))
            output.closeEntry()
        }
        val originalBytes = archive.readBytes()
        val legacyBackup = File(ArchiveBackupManager.backupDirectory(archive), "legacy.cbz")
        check(legacyBackup.parentFile?.mkdirs() == true)
        legacyBackup.writeBytes(originalBytes)

        assertFalse(ComicArchive.deleteEntry(archive, "漫画/不存在.png"))

        assertArrayEquals(originalBytes, archive.readBytes())
        assertArrayEquals(originalBytes, legacyBackup.readBytes())
        assertEquals(listOf(entry), ComicArchive.imageEntries(archive))
        assertReplacementFilesCleaned(archive, legacyBackup)
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

    private fun assertReplacementFilesCleaned(archive: File, legacyBackup: File? = null) {
        val siblingFiles = archive.parentFile?.listFiles().orEmpty()
        assertFalse(siblingFiles.any {
            it.name.startsWith("${archive.name}.delete_") || it.name.startsWith("${archive.name}.restore_")
        })
        assertEquals(
            listOfNotNull(legacyBackup),
            ArchiveBackupManager.backupDirectory(archive).listFiles().orEmpty().toList()
        )
    }

}
