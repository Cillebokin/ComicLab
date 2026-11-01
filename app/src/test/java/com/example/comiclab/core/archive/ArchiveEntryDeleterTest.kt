package com.example.comiclab.core.archive

import com.example.comiclab.ComicArchive
import com.example.comiclab.core.archive.ArchiveEntryDeleter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveEntryDeleterTest {
    @Test
    fun nonZipFormatsRejectDeletionBeforeCreatingBackupsOrChangingBytes() = withTempDirectory { root ->
        for (extension in listOf("rar", "cbr", "7z", "cb7", "RAR", "CBR", "7Z", "CB7", "pdf")) {
            val archive = File(root, "comic.$extension")
            writeZip(archive, "001.jpg" to "one", "002.jpg" to "two")
            val original = archive.readBytes()
            assertFalse(extension, ComicArchive.canDeleteEntry(archive))
            assertFalse(extension, deleteEntryWithRename(archive, "001.jpg") { _, _ ->
                error("Unsupported formats must not rename any files")
            })
            assertArrayEquals(extension, original, archive.readBytes())
            assertFalse(extension, File(root, ".ComicLabBackups").exists())
            assertFalse(root.listFiles().orEmpty().any { ".delete_" in it.name || ".restore_" in it.name })
        }
    }

    @Test
    fun zipAndCbzKeepTheirFormatNameAndRemainReadableAfterDeletion() = withTempDirectory { root ->
        for (extension in listOf("zip", "cbz", "ZIP", "CBZ")) {
            val archive = File(root, "comic.$extension")
            writeZip(archive, "001.jpg" to "one", "002.jpg" to "two", "notes.txt" to "note")
            val originalPath = archive.absolutePath
            assertTrue(extension, ComicArchive.canDeleteEntry(archive))
            assertTrue(extension, ComicArchive.deleteEntry(archive, "002.jpg"))
            assertEquals(originalPath, archive.absolutePath)
            assertEquals(extension, archive.extension)
            assertArrayEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), archive.readBytes().take(4).toByteArray())
            assertEquals(listOf("001.jpg"), ComicArchive.imageEntries(archive))
            java.util.zip.ZipFile(archive).use { zip ->
                assertEquals("one", zip.getInputStream(zip.getEntry("001.jpg")).bufferedReader().use { it.readText() })
                assertEquals("note", zip.getInputStream(zip.getEntry("notes.txt")).bufferedReader().use { it.readText() })
            }
        }
    }

    @Test
    fun canDeleteEntry_doesNotCreateBackupDirectory() = withTempDirectory { root ->
        val archive = File(root, "comic.cbz")
        writeZip(archive, "001.jpg" to "one")

        assertTrue(ComicArchive.canDeleteEntry(archive))
        assertFalse(File(root, ".ComicLabBackups").exists())
    }

    @Test
    fun deleteEntry_missingEntryKeepsOriginalWithoutPersistentBackup() = withTempDirectory { root ->
        val archive = File(root, "comic.cbz")
        writeZip(archive, "001.jpg" to "one", "notes.txt" to "note")
        val originalBytes = archive.readBytes()

        assertFalse(ComicArchive.deleteEntry(archive, "missing.jpg"))

        assertTrue(archive.isFile)
        assertArrayEquals(originalBytes, archive.readBytes())
        assertTrue(persistentBackups(root).isEmpty())
        assertFalse(ArchiveReplacementRecovery.hasPending(archive))
        assertTrue(siblingRollbackFiles(archive).isEmpty())
        assertTrue(siblingTemporaryFiles(archive).isEmpty())
    }

    @Test
    fun deleteEntry_backupDirectoryCreationFailureLeavesArchiveUntouched() = withTempDirectory { root ->
        val archive = File(root, "comic.cbz")
        writeZip(archive, "001.jpg" to "one")
        val originalBytes = archive.readBytes()
        val backupPath = File(root, ".ComicLabBackups")
        backupPath.writeText("not a directory")

        assertFalse(ComicArchive.deleteEntry(archive, "001.jpg"))

        assertTrue(archive.isFile)
        assertArrayEquals(originalBytes, archive.readBytes())
        assertEquals("not a directory", backupPath.readText())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("comic.cbz.delete_") })
    }

    @Test
    fun deleteEntry_rebuildFailureKeepsOriginalWithoutPersistentBackup() = withTempDirectory { root ->
        val archive = File(root, "damaged.cbz")
        archive.writeText("not a zip archive")
        val originalBytes = archive.readBytes()

        assertFalse(ComicArchive.deleteEntry(archive, "001.jpg"))

        assertTrue(archive.isFile)
        assertArrayEquals(originalBytes, archive.readBytes())
        assertTrue(persistentBackups(root).isEmpty())
        assertFalse(ArchiveReplacementRecovery.hasPending(archive))
        assertTrue(siblingRollbackFiles(archive).isEmpty())
        assertTrue(siblingTemporaryFiles(archive).isEmpty())
    }

    @Test
    fun deleteEntry_unreplaceableOriginalKeepsOriginalWithoutPersistentBackup() = withTempDirectory { root ->
        val archive = File(root, "comic.cbz")
        writeZip(archive, "001.jpg" to "one", "002.jpg" to "two")
        val originalBytes = archive.readBytes()
        val renameTo: (File, File) -> Boolean = { source, destination ->
            if (source == archive && isSiblingRollbackFile(archive, destination)) {
                false
            } else {
                source.renameTo(destination)
            }
        }

        assertFalse(deleteEntryWithRename(archive, "002.jpg", renameTo))

        assertTrue(archive.isFile)
        assertArrayEquals(originalBytes, archive.readBytes())
        assertTrue(persistentBackups(root).isEmpty())
        assertFalse(ArchiveReplacementRecovery.hasPending(archive))
        assertTrue(siblingRollbackFiles(archive).isEmpty())
        assertTrue(siblingTemporaryFiles(archive).isEmpty())
    }

    @Test
    fun deleteEntry_failedRollbackCanRecoverFromShortLivedRollbackFile() = withTempDirectory { root ->
        val archive = File(root, "comic.cbz")
        writeZip(archive, "001.jpg" to "one", "002.jpg" to "two")
        val originalBytes = archive.readBytes()
        val renameTo: (File, File) -> Boolean = { source, destination ->
            when {
                isSiblingReplacementFile(archive, source) && destination == archive -> false
                isSiblingRollbackFile(archive, source) && destination == archive -> false
                else -> source.renameTo(destination)
            }
        }

        assertFalse(deleteEntryWithRename(archive, "002.jpg", renameTo))

        assertFalse(archive.exists())
        assertArrayEquals(originalBytes, siblingRollbackFiles(archive).single().readBytes())
        assertTrue(ArchiveReplacementRecovery.hasPending(archive))
        assertTrue(persistentBackups(root).isEmpty())
        assertEquals(1, siblingRollbackFiles(archive).size)
        assertTrue(siblingTemporaryFiles(archive).isEmpty())

        assertTrue(ArchiveReplacementRecovery.recover(archive))
        assertArrayEquals(originalBytes, archive.readBytes())
        assertFalse(ArchiveReplacementRecovery.hasPending(archive))
        assertTrue(siblingRollbackFiles(archive).isEmpty())
    }

    @Test
    fun deleteEntry_failedRollbackRetainsEvidenceUntilRecoveryCanRetry() = withTempDirectory { root ->
        val archive = File(root, "comic.cbz")
        writeZip(archive, "001.jpg" to "one", "002.jpg" to "two")
        val originalBytes = archive.readBytes()
        val renameTo: (File, File) -> Boolean = { source, destination ->
            when {
                isSiblingReplacementFile(archive, source) && destination == archive -> false
                isSiblingRollbackFile(archive, source) && destination == archive -> false
                source.name.startsWith("${archive.name}.restore_") && destination == archive -> false
                else -> source.renameTo(destination)
            }
        }

        assertFalse(deleteEntryWithRename(archive, "002.jpg", renameTo))

        assertFalse(archive.exists())
        val rollbackFile = siblingRollbackFiles(archive).single()
        assertArrayEquals(originalBytes, rollbackFile.readBytes())
        assertTrue(ArchiveReplacementRecovery.hasPending(archive))
        assertTrue(persistentBackups(root).isEmpty())
        assertTrue(siblingTemporaryFiles(archive).isEmpty())

        assertFalse(ArchiveReplacementRecovery.recover(archive, renameTo))
        assertFalse(archive.exists())
        assertArrayEquals(originalBytes, rollbackFile.readBytes())
        assertTrue(ArchiveReplacementRecovery.hasPending(archive))

        assertTrue(ArchiveReplacementRecovery.recover(archive))
        assertArrayEquals(originalBytes, archive.readBytes())
        assertFalse(ArchiveReplacementRecovery.hasPending(archive))
    }

    @Test
    fun deleteEntry_repeatedDeletionDoesNotKeepPersistentFullArchiveBackups() = withTempDirectory { root ->
        val archive = File(root, "comic.cbz")
        writeZip(archive, "001.jpg" to "one", "002.jpg" to "two")

        assertTrue(ComicArchive.deleteEntry(archive, "002.jpg"))
        assertTrue(ComicArchive.deleteEntry(archive, "001.jpg"))

        assertTrue(ComicArchive.imageEntries(archive).isEmpty())
        assertTrue(persistentBackups(root).isEmpty())
        assertFalse(File(root, ".ComicLabBackups").exists())
        assertFalse(ArchiveReplacementRecovery.hasPending(archive))
        assertTrue(siblingRollbackFiles(archive).isEmpty())
        assertTrue(siblingTemporaryFiles(archive).isEmpty())
    }

    private fun writeZip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, text) ->
                output.putNextEntry(ZipEntry(name))
                output.write(text.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun persistentBackups(root: File): List<File> =
        File(root, ".ComicLabBackups").listFiles().orEmpty().filter { it.isFile && it.extension == "cbz" }

    private fun siblingRollbackFiles(archive: File): List<File> =
        archive.parentFile!!.listFiles().orEmpty().filter { isSiblingRollbackFile(archive, it) }

    private fun isSiblingRollbackFile(archive: File, file: File): Boolean =
        file.name.startsWith("${archive.name}.delete_") && file.extension == "bak"

    private fun isSiblingReplacementFile(archive: File, file: File): Boolean =
        file.name.startsWith("${archive.name}.delete_") && file.extension == "tmp"

    private fun siblingTemporaryFiles(archive: File): List<File> =
        archive.parentFile!!.listFiles().orEmpty().filter {
            isSiblingReplacementFile(archive, it) || it.name.startsWith("${archive.name}.restore_")
        }

    private fun deleteEntryWithRename(
        archive: File,
        entryName: String,
        renameTo: (File, File) -> Boolean
    ): Boolean = ArchiveEntryDeleter.deleteEntry(
        file = archive,
        entryName = entryName,
        renameTo = renameTo
    )

    private inline fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("comiclab-delete-characterization").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
