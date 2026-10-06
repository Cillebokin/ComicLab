package com.example.comiclab.core.archive

import java.io.File
import java.util.Locale

internal object ArchiveBackupManager {
    private const val DELETE_BACKUP_DIR_NAME = ".ComicLabBackups"
    private const val DELETE_BACKUP_COPY_BUFFER_SIZE = 1024 * 1024
    private val defaultRenameTo: (File, File) -> Boolean = { source, destination ->
        source.renameTo(destination)
    }

    fun createDeleteBackup(
        file: File,
        renameTo: (File, File) -> Boolean = defaultRenameTo
    ): File? {
        val parent = file.parentFile ?: return null
        if (!file.isFile || file.length() <= 0L) {
            return null
        }

        val backupDir = File(parent, DELETE_BACKUP_DIR_NAME)
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            return null
        }

        val backupFile = uniqueDeleteBackupFile(backupDir, file)
        val tempFile = File(backupDir, "${backupFile.name}.part")
        tempFile.delete()

        val copied = runCatching {
            file.inputStream().buffered().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    input.copyTo(output, DELETE_BACKUP_COPY_BUFFER_SIZE)
                }
            }
            tempFile.isFile && tempFile.length() == file.length()
        }.getOrDefault(false)

        if (!copied) {
            tempFile.delete()
            return null
        }

        if (renameTo(tempFile, backupFile)) {
            return backupFile
        }

        tempFile.delete()
        return null
    }

    fun restoreOriginalFromDeleteBackupIfNeeded(
        originalFile: File,
        backupFile: File,
        renameTo: (File, File) -> Boolean = defaultRenameTo
    ) {
        if (originalFile.isFile && originalFile.length() > 0L) {
            return
        }

        val parent = originalFile.parentFile ?: return
        val restoreTempFile = File(parent, "${originalFile.name}.restore_${System.currentTimeMillis()}.tmp")
        restoreTempFile.delete()

        val copied = runCatching {
            backupFile.inputStream().buffered().use { input ->
                restoreTempFile.outputStream().buffered().use { output ->
                    input.copyTo(output, DELETE_BACKUP_COPY_BUFFER_SIZE)
                }
            }
            restoreTempFile.isFile && restoreTempFile.length() == backupFile.length()
        }.getOrDefault(false)

        if (!copied) {
            restoreTempFile.delete()
            return
        }

        if (originalFile.exists()) {
            originalFile.delete()
        }
        if (!renameTo(restoreTempFile, originalFile)) {
            restoreTempFile.delete()
        }
    }

    private fun uniqueDeleteBackupFile(backupDir: File, file: File): File {
        val baseName = file.nameWithoutExtension.ifBlank { "archive" }
        val extension = file.extension
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        val timestamp = System.currentTimeMillis()
        var candidate = File(backupDir, "${baseName}.delete_backup_$timestamp$extension")
        var index = 0
        while (candidate.exists()) {
            candidate = File(
                backupDir,
                String.format(
                    Locale.ROOT,
                    "%s.delete_backup_%d_%03d%s",
                    baseName,
                    timestamp,
                    index,
                    extension
                )
            )
            index++
        }
        return candidate
    }
}
