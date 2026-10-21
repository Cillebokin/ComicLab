package com.example.comiclab.core.archive

import com.example.comiclab.ComicArchive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveReplacementRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private class ProcessExit : Error()

    private fun archive(): File = File(temporaryFolder.root, "comic.cbz").also {
        writeZip(it, "001.png", "002.png")
    }

    private fun writeZip(file: File, vararg names: String) {
        ZipOutputStream(file.outputStream()).use { output ->
            names.forEach { name ->
                output.putNextEntry(ZipEntry(name))
                output.write(name.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun interrupt(file: File, afterInstall: Boolean = false) {
        assertThrows(ProcessExit::class.java) {
            ArchiveEntryDeleter.deleteEntry(file, "002.png") { source, destination ->
                val renamed = source.renameTo(destination)
                if (renamed && if (afterInstall) {
                    source.extension == "tmp" && destination == file
                } else {
                    source == file && destination.extension == "bak"
                }) throw ProcessExit()
                renamed
            }
        }
    }

    private fun backups() = File(temporaryFolder.root, ".ComicLabBackups").listFiles().orEmpty()
        .filter { it.extension == "cbz" }

    @Test fun reopeningAfterOriginalWasRenamedRestoresOriginalBytesAndIdentity() {
        val file = archive()
        val bytes = file.readBytes()
        val modified = file.lastModified()
        interrupt(file)
        assertFalse(file.exists())
        assertEquals(listOf("001.png", "002.png"), ComicArchive.imageEntries(file))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
        assertArrayEquals(bytes, backups().single().readBytes())
        assertEquals(listOf("001.png", "002.png"), ComicArchive.imageEntries(file))
    }

    @Test fun reopeningAfterReplacementWasInstalledKeepsCommittedDeletion() {
        val file = archive()
        val original = file.readBytes()
        interrupt(file, afterInstall = true)
        assertEquals(listOf("001.png"), ComicArchive.imageEntries(file))
        assertArrayEquals(original, backups().single().readBytes())
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { ".delete_" in it.name })
    }

    @Test fun damagedRollbackFallsBackToVerifiedPersistentBackup() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file)
        temporaryFolder.root.listFiles()!!.single { it.extension == "bak" }.writeText("truncated")
        assertEquals(listOf("001.png", "002.png"), ComicArchive.imageEntries(file))
        assertArrayEquals(bytes, file.readBytes())
        assertArrayEquals(bytes, backups().single().readBytes())
    }

    @Test fun aNewFileAtOriginalPathIsNeverOverwrittenAndPreventsAnotherDelete() {
        val file = archive()
        interrupt(file)
        writeZip(file, "user-created.png")
        val userBytes = file.readBytes()
        assertEquals(listOf("user-created.png"), ComicArchive.imageEntries(file))
        assertFalse(ComicArchive.deleteEntry(file, "user-created.png"))
        assertArrayEquals(userBytes, file.readBytes())
        assertEquals(1, backups().size)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().any { it.extension == "bak" })
    }

    @Test fun unavailableBackupDirectoryDoesNotLoseRecoveryEvidence() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file)
        val directory = File(temporaryFolder.root, ".ComicLabBackups")
        val offline = File(temporaryFolder.root, "offline-backups")
        assertTrue(directory.renameTo(offline))
        assertTrue(ComicArchive.imageEntries(file).isEmpty())
        assertFalse(file.exists())
        assertTrue(offline.renameTo(directory))
        assertEquals(listOf("001.png", "002.png"), ComicArchive.imageEntries(file))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun interruptionBeforeFirstRenameCancelsOnlyRegisteredTemporaryFiles() {
        val file = archive()
        val bytes = file.readBytes()
        assertThrows(ProcessExit::class.java) {
            ArchiveEntryDeleter.deleteEntry(file, "002.png") { source, destination ->
                if (source == file && destination.extension == "bak") throw ProcessExit()
                source.renameTo(destination)
            }
        }
        val unrelated = File(temporaryFolder.root, "keep.tmp").apply { writeText("keep") }
        ArchiveReplacementRecovery.recoverDirectory(temporaryFolder.root)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("keep", unrelated.readText())
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
        assertArrayEquals(bytes, backups().single().readBytes())
    }

    @Test fun corruptCopiesAreNotInstalledAndRemainForManualRecovery() {
        val file = archive()
        interrupt(file)
        temporaryFolder.root.listFiles()!!.single { it.extension == "bak" }.writeText("bad rollback")
        backups().single().writeText("bad backup")
        assertFalse(ArchiveReplacementRecovery.recover(file))
        assertFalse(file.exists())
        assertTrue(ArchiveReplacementRecovery.hasPending(file))
        assertEquals("bad backup", backups().single().readText())
    }

    @Test fun malformedJournalAndTraversalPathsDoNotModifyOtherFiles() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file)
        val marker = File(File(temporaryFolder.root, ".ComicLabBackups"), file.name + ".replace.properties")
        val properties = java.util.Properties().apply { marker.inputStream().use(::load) }
        val untouched = temporaryFolder.newFile("untouched.cbz").apply { writeBytes(bytes) }
        properties.setProperty("rollback", "../untouched.cbz")
        marker.outputStream().use { properties.store(it, "invalid") }
        assertFalse(ArchiveReplacementRecovery.recover(file))
        assertArrayEquals(bytes, untouched.readBytes())
        assertFalse(file.exists())
        marker.writeText("corrupt")
        assertFalse(ArchiveReplacementRecovery.recover(file))
        assertTrue(marker.exists())
        assertArrayEquals(bytes, untouched.readBytes())
    }

    @Test fun lostPersistentBackupCanStillRecoverVerifiedRollback() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file)
        assertTrue(backups().single().delete())
        assertTrue(ArchiveReplacementRecovery.recover(file))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun successfulDeleteRetainsAllFullBackupsWithoutAutomaticRotation() {
        val file = archive()
        val original = file.readBytes()
        assertTrue(ComicArchive.deleteEntry(file, "002.png"))
        val second = file.readBytes()
        assertTrue(ComicArchive.deleteEntry(file, "001.png"))
        ArchiveReplacementRecovery.recoverDirectory(temporaryFolder.root)
        assertEquals(2, backups().size)
        assertTrue(backups().any { it.readBytes().contentEquals(original) })
        assertTrue(backups().any { it.readBytes().contentEquals(second) })
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
    }

    @Test fun readerSessionAndPreparedExtractorRecoverBeforeOpeningZip() {
        val file = archive()
        interrupt(file)
        ComicArchive.openReaderSession(file, temporaryFolder.root).use { }
        assertTrue(file.isFile)
        interrupt(file)
        ComicArchive.openPreparedImageExtractor(file, temporaryFolder.newFolder("prepared")).use { }
        assertEquals(listOf("001.png", "002.png"), ComicArchive.imageEntries(file))
    }

    @Test fun incompleteReplacementIsNeverInstalledInPlaceOfOriginal() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file)
        temporaryFolder.root.listFiles()!!.single { it.extension == "tmp" }.writeText("incomplete")
        assertTrue(ArchiveReplacementRecovery.recover(file))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun legacyUnrecordedRollbackIsLeftForManualRecovery() {
        val legacy = File(temporaryFolder.root, "comic.cbz.delete_123.bak")
        writeZip(legacy, "legacy.png")
        val bytes = legacy.readBytes()
        assertTrue(ArchiveReplacementRecovery.recoverDirectory(temporaryFolder.root))
        assertFalse(File(temporaryFolder.root, "comic.cbz").exists())
        assertArrayEquals(bytes, legacy.readBytes())
    }

    @Test fun movingRecoveryFileDoesNotReplaceAnExistingDestination() {
        val source = temporaryFolder.newFile("source").apply { writeText("old") }
        val target = temporaryFolder.newFile("target").apply { writeText("user") }
        assertFalse(ArchiveReplacementRecovery.moveWithoutReplacing(source, target))
        assertEquals("user", target.readText())
        assertEquals("old", source.readText())
    }

    @Test fun modificationBetweenSealAndRenameIsPreservedInsteadOfDeleted() {
        val file = archive()
        val original = file.readBytes()
        var userBytes = byteArrayOf()
        assertFalse(ArchiveEntryDeleter.deleteEntry(file, "002.png") { source, destination ->
            if (source == file && destination.extension == "bak") {
                writeZip(file, "user-change.png")
                userBytes = file.readBytes()
            }
            ArchiveReplacementRecovery.moveWithoutReplacing(source, destination)
        })
        assertTrue(userBytes.isNotEmpty())
        assertArrayEquals(userBytes, file.readBytes())
        assertArrayEquals(original, backups().single().readBytes())
        assertTrue(ArchiveReplacementRecovery.hasPending(file))
    }

    @Test fun interruptedRestoreCopyIsReusedAndCleanedWithoutLeakingAnotherFullCopy() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file)
        val staged = temporaryFolder.root.listFiles()!!.single { it.extension == "tmp" }
        val token = staged.name.removePrefix("${file.name}.delete_").removeSuffix(".tmp")
        val restore = File(temporaryFolder.root, "${file.name}.restore_$token.tmp").apply { writeText("partial") }
        temporaryFolder.root.listFiles()!!.single { it.extension == "bak" }.delete()
        assertTrue(ArchiveReplacementRecovery.recover(file))
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(restore.exists())
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { ".restore_" in it.name })
        assertArrayEquals(bytes, backups().single().readBytes())
    }
}
