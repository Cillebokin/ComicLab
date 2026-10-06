package com.example.comiclab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun sevenZipDeleteRebuildsArchiveAndKeepsPersistentOriginalBackup() {
        val archive = copySevenZipFixture("seven-zip-delete-success.7z")
        val originalBytes = archive.readBytes()
        val originalEntries = ComicArchive.imageEntries(archive)
        assertTrue(originalEntries.contains(ENTRY_TO_DELETE))

        assertTrue(ComicArchive.deleteEntry(archive, ENTRY_TO_DELETE))

        assertFalse(ComicArchive.imageEntries(archive).contains(ENTRY_TO_DELETE))
        assertEquals(originalEntries.size - 1, ComicArchive.imageEntries(archive).size)
        val backups = persistentBackups(archive)
        assertEquals(1, backups.size)
        assertArrayEquals(originalBytes, backups.single().readBytes())
    }

    @Test
    fun sevenZipMissingEntryLeavesOriginalAndPersistentBackupUntouched() {
        val archive = copySevenZipFixture("seven-zip-delete-missing.7z")
        val originalBytes = archive.readBytes()

        assertFalse(ComicArchive.deleteEntry(archive, "missing-entry.jpg"))

        assertTrue(archive.isFile)
        assertArrayEquals(originalBytes, archive.readBytes())
        val backups = persistentBackups(archive)
        assertEquals(1, backups.size)
        assertArrayEquals(originalBytes, backups.single().readBytes())
        assertFalse(archive.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("seven-zip-delete-missing.7z.delete_") })
    }

    private fun copySevenZipFixture(targetName: String): File {
        return File(temporaryFolder.root, targetName).also { target ->
            instrumentation.context.assets.open("task21/entries.7z").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun persistentBackups(archive: File): List<File> =
        File(archive.parentFile, ".ComicLabBackups")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == archive.extension }

    private companion object {
        const val ENTRY_TO_DELETE = "2.PNG"
    }
}
