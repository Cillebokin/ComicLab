package com.example.comiclab.core.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.ComicArchive
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** 只在应用 cache 的独立沙箱中注入中断状态，不操作用户漫画或偏好。 */
@RunWith(AndroidJUnit4::class)
class ArchiveReplacementRecoveryInstrumentedTest {
    @get:Rule val temporaryFolder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
    private class Interrupted : Error()
    private val firstPage = "漫画/第1页.png"
    private val secondPage = "漫画/第2页.png"

    private fun archive() = File(temporaryFolder.root, "comic.cbz").apply {
        ZipOutputStream(outputStream(), Charset.forName("GBK")).use { output ->
            for (name in listOf(firstPage, secondPage)) {
                output.putNextEntry(ZipEntry(name))
                output.write(name.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun interrupt(file: File, installed: Boolean) {
        assertThrows(Interrupted::class.java) {
            ArchiveEntryDeleter.deleteEntry(file, secondPage) { source, destination ->
                val moved = ArchiveReplacementRecovery.moveWithoutReplacing(source, destination)
                if (moved && if (installed) source.extension == "tmp" && destination == file
                    else source == file && destination.extension == "bak") throw Interrupted()
                moved
            }
        }
    }

    private fun rollbackFile() = temporaryFolder.root.listFiles().orEmpty()
        .single { it.name.endsWith(".bak") }

    private fun assertRecoveryCleaned(file: File) {
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { ".delete_" in it.name || ".restore_" in it.name })
        assertTrue(ComicArchive.deleteBackupDirectory(file).listFiles().orEmpty().isEmpty())
    }

    @Test fun directoryVisitRestoresInterruptedGbkArchive() {
        val file = archive()
        val bytes = file.readBytes()
        val time = file.lastModified()
        interrupt(file, installed = false)
        assertFalse(file.exists())
        assertArrayEquals(bytes, rollbackFile().readBytes())
        assertTrue(ArchiveReplacementRecovery.recoverDirectory(temporaryFolder.root))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(time, file.lastModified())
        assertEquals(listOf(firstPage, secondPage), ComicArchive.imageEntries(file))
        assertRecoveryCleaned(file)
    }

    @Test fun reopeningInstalledReplacementKeepsDeletionAndCleansOnlyTemporaryFiles() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file, installed = true)
        assertArrayEquals(bytes, rollbackFile().readBytes())
        assertEquals(listOf(firstPage), ComicArchive.imageEntries(file))
        ZipFile(file).use { zip ->
            assertArrayEquals(firstPage.encodeToByteArray(), zip.getInputStream(zip.getEntry(firstPage)).use { it.readBytes() })
        }
        assertRecoveryCleaned(file)
    }

    @Test fun conflictingFileIsNotOverwrittenAndEvidenceRemains() {
        val file = archive()
        val original = file.readBytes()
        interrupt(file, installed = false)
        file.writeText("new user content")
        assertFalse(ArchiveReplacementRecovery.recoverDirectory(temporaryFolder.root))
        assertEquals("new user content", file.readText())
        assertFalse(ComicArchive.deleteEntry(file, firstPage))
        assertArrayEquals(original, rollbackFile().readBytes())
        assertTrue(ArchiveReplacementRecovery.hasPending(file))
    }
}
