package com.example.comiclab.core.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.ComicArchive
import java.io.File
import java.util.zip.ZipEntry
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

    private fun archive() = File(temporaryFolder.root, "comic.cbz").apply {
        ZipOutputStream(outputStream()).use { output ->
            for (name in listOf("001.png", "002.png")) {
                output.putNextEntry(ZipEntry(name))
                output.write(name.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun interrupt(file: File, installed: Boolean) {
        assertThrows(Interrupted::class.java) {
            ArchiveEntryDeleter.deleteEntry(file, "002.png") { source, destination ->
                val moved = ArchiveReplacementRecovery.moveWithoutReplacing(source, destination)
                if (moved && if (installed) source.extension == "tmp" && destination == file
                    else source == file && destination.extension == "bak") throw Interrupted()
                moved
            }
        }
    }

    private fun backup(file: File) = ComicArchive.deleteBackupDirectory(file).listFiles().orEmpty()
        .single { it.extension == "cbz" }

    @Test fun directoryVisitRestoresInterruptedOriginalAndPreservesFullBackup() {
        val file = archive()
        val bytes = file.readBytes()
        val time = file.lastModified()
        interrupt(file, installed = false)
        assertFalse(file.exists())
        assertTrue(ArchiveReplacementRecovery.recoverDirectory(temporaryFolder.root))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(time, file.lastModified())
        assertEquals(listOf("001.png", "002.png"), ComicArchive.imageEntries(file))
        assertArrayEquals(bytes, backup(file).readBytes())
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
    }

    @Test fun reopeningInstalledReplacementKeepsDeletionAndCleansOnlyTemporaryFiles() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file, installed = true)
        assertEquals(listOf("001.png"), ComicArchive.imageEntries(file))
        assertArrayEquals(bytes, backup(file).readBytes())
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { ".delete_" in it.name || ".restore_" in it.name })
    }

    @Test fun conflictingFileIsNotOverwrittenAndEvidenceRemains() {
        val file = archive()
        val original = file.readBytes()
        interrupt(file, installed = false)
        file.writeText("new user content")
        assertFalse(ArchiveReplacementRecovery.recoverDirectory(temporaryFolder.root))
        assertEquals("new user content", file.readText())
        assertFalse(ComicArchive.deleteEntry(file, "001.png"))
        assertArrayEquals(original, backup(file).readBytes())
        assertTrue(ArchiveReplacementRecovery.hasPending(file))
    }
}
