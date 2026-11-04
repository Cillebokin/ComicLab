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

    private fun rollbackFiles(file: File) = temporaryFolder.root.listFiles().orEmpty()
        .filter { it.name.startsWith("${file.name}.delete_") && it.extension == "bak" }

    private fun replacementFiles(file: File) = temporaryFolder.root.listFiles().orEmpty()
        .filter { it.name.startsWith("${file.name}.delete_") && it.extension == "tmp" }

    private fun createLegacyPending(file: File, token: String): Pair<File, File> {
        val originalBytes = file.readBytes()
        val backupDirectory = File(temporaryFolder.root, ".ComicLabBackups").apply {
            check(mkdirs() || isDirectory)
        }
        val backup = File(
            backupDirectory,
            "${file.nameWithoutExtension}.delete_backup_$token.${file.extension}"
        ).apply { writeBytes(originalBytes) }
        val temporary = File(temporaryFolder.root, "${file.name}.delete_$token.tmp")
        val rollback = File(temporaryFolder.root, "${file.name}.delete_$token.bak")
        val restore = File(temporaryFolder.root, "${file.name}.restore_$token.tmp")
        val marker = File(backupDirectory, file.name + ".replace.properties")
        val originalHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(originalBytes)
            .joinToString("") { "%02x".format(it) }
        val properties = java.util.Properties().apply {
            setProperty("version", "1")
            setProperty("original", file.name)
            setProperty("backup", backup.name)
            setProperty("temporary", temporary.name)
            setProperty("rollback", rollback.name)
            setProperty("originalHash", originalHash)
            setProperty("modifiedAt", file.lastModified().toString())
        }
        marker.outputStream().use { properties.store(it, "legacy archive replacement") }
        check(file.delete())
        return backup to restore
    }

    @Test fun reopeningAfterOriginalWasRenamedRestoresOriginalBytesAndIdentity() {
        val file = archive()
        val bytes = file.readBytes()
        val modified = file.lastModified()
        interrupt(file)
        assertFalse(file.exists())
        assertEquals(listOf("001.png", "002.png"), ComicArchive.imageEntries(file))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
        assertTrue(backups().isEmpty())
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
        assertTrue(rollbackFiles(file).isEmpty())
        assertTrue(replacementFiles(file).isEmpty())
        assertEquals(listOf("001.png", "002.png"), ComicArchive.imageEntries(file))
    }

    @Test fun reopeningAfterReplacementWasInstalledKeepsCommittedDeletion() {
        val file = archive()
        val original = file.readBytes()
        interrupt(file, afterInstall = true)
        assertEquals(listOf("001.png"), ComicArchive.imageEntries(file))
        assertFalse(original.contentEquals(file.readBytes()))
        assertTrue(backups().isEmpty())
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
        assertTrue(rollbackFiles(file).isEmpty())
        assertTrue(replacementFiles(file).isEmpty())
    }

    @Test fun damagedCurrentRollbackRemainsForManualRecoveryWithoutOverwriting() {
        val file = archive()
        interrupt(file)
        val rollback = rollbackFiles(file).single().apply { writeText("truncated") }

        assertTrue(ComicArchive.imageEntries(file).isEmpty())
        assertFalse(file.exists())
        assertFalse(ArchiveReplacementRecovery.recover(file))
        assertTrue(ArchiveReplacementRecovery.hasPending(file))
        assertEquals("truncated", rollback.readText())
        assertTrue(backups().isEmpty())
    }

    @Test fun aNewFileAtOriginalPathIsNeverOverwrittenAndPreventsAnotherDelete() {
        val file = archive()
        val original = file.readBytes()
        interrupt(file)
        val rollback = rollbackFiles(file).single()
        writeZip(file, "user-created.png")
        val userBytes = file.readBytes()
        assertEquals(listOf("user-created.png"), ComicArchive.imageEntries(file))
        assertFalse(ComicArchive.deleteEntry(file, "user-created.png"))
        assertArrayEquals(userBytes, file.readBytes())
        assertArrayEquals(original, rollback.readBytes())
        assertTrue(ArchiveReplacementRecovery.hasPending(file))
        assertTrue(backups().isEmpty())
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
        assertTrue(backups().isEmpty())
        assertFalse(File(temporaryFolder.root, ".ComicLabBackups").exists())
        assertTrue(rollbackFiles(file).isEmpty())
        assertTrue(replacementFiles(file).isEmpty())
    }

    @Test fun corruptLegacyBackupIsNotInstalledAndRemainsForManualRecovery() {
        val file = archive()
        val (legacyBackup, _) = createLegacyPending(file, "corrupt")
        legacyBackup.writeText("bad backup")

        assertFalse(ArchiveReplacementRecovery.recover(file))
        assertFalse(file.exists())
        assertTrue(ArchiveReplacementRecovery.hasPending(file))
        assertEquals("bad backup", legacyBackup.readText())
        assertEquals(listOf(legacyBackup), backups())
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

    @Test fun validRollbackRecoversWithoutPersistentFullArchiveBackup() {
        val file = archive()
        val bytes = file.readBytes()
        interrupt(file)
        assertArrayEquals(bytes, rollbackFiles(file).single().readBytes())
        assertTrue(backups().isEmpty())

        assertTrue(ArchiveReplacementRecovery.recover(file))
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(rollbackFiles(file).isEmpty())
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
    }

    @Test fun successfulDeletesLeaveNoPersistentFullArchiveBackups() {
        val file = archive()
        assertTrue(ComicArchive.deleteEntry(file, "002.png"))
        assertTrue(ComicArchive.deleteEntry(file, "001.png"))
        ArchiveReplacementRecovery.recoverDirectory(temporaryFolder.root)
        assertTrue(ComicArchive.imageEntries(file).isEmpty())
        assertTrue(backups().isEmpty())
        assertFalse(File(temporaryFolder.root, ".ComicLabBackups").exists())
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
        assertTrue(rollbackFiles(file).isEmpty())
        assertTrue(replacementFiles(file).isEmpty())
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
        assertFalse(original.contentEquals(userBytes))
        assertArrayEquals(userBytes, file.readBytes())
        assertTrue(backups().isEmpty())
        assertTrue(ArchiveReplacementRecovery.hasPending(file))
        assertTrue(rollbackFiles(file).isEmpty())
        assertTrue(replacementFiles(file).isNotEmpty())
    }

    @Test fun legacyRestoreReplacesPartialStageAndPreservesLegacyBackup() {
        val file = archive()
        val modified = file.lastModified()
        val (legacyBackup, restore) = createLegacyPending(file, "restore")
        val bytes = legacyBackup.readBytes()
        restore.writeText("partial")

        var restoreMoveAttempted = false
        val recovered = ArchiveReplacementRecovery.recover(file) { source, destination ->
            if (source == restore && destination == file) restoreMoveAttempted = true
            ArchiveReplacementRecovery.moveWithoutReplacing(source, destination)
        }
        assertTrue("Legacy backup should restore the original archive", recovered)
        assertTrue("Recovery should install the staged legacy restore", restoreMoveAttempted)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
        assertFalse(restore.exists())
        assertFalse(temporaryFolder.root.listFiles().orEmpty().any { ".restore_" in it.name })
        assertArrayEquals(bytes, backups().single().readBytes())
        assertFalse(ArchiveReplacementRecovery.hasPending(file))
        assertTrue(rollbackFiles(file).isEmpty())
        assertTrue(replacementFiles(file).isEmpty())
    }
}
