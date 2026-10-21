package com.example.comiclab.core.archive

import java.io.File
import java.io.FileOutputStream
import java.util.Locale

internal object ArchiveBackupManager {
    private const val DELETE_BACKUP_DIR_NAME = ".ComicLabBackups"
    private const val DELETE_BACKUP_COPY_BUFFER_SIZE = 1024 * 1024
    private val defaultRenameTo: (File, File) -> Boolean = ArchiveReplacementRecovery::moveWithoutReplacing

    fun backupDirectory(file: File): File = File(file.parentFile, DELETE_BACKUP_DIR_NAME)

    fun createDeleteBackup(
        file: File,
        renameTo: (File, File) -> Boolean = defaultRenameTo
    ): File? {
        val parent = file.parentFile ?: return null
        if (!file.isFile || file.length() <= 0L) {
            return null
        }

        val backupDir = backupDirectory(file)
        if (runCatching { file.canonicalFile != File(parent.canonicalFile, file.name) }.getOrDefault(true)) return null
        if (runCatching { backupDir.canonicalFile != File(parent.canonicalFile, DELETE_BACKUP_DIR_NAME) }.getOrDefault(true)) return null
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            return null
        }

        val backupFile = uniqueDeleteBackupFile(backupDir, file)
        val tempFile = File(backupDir, "${backupFile.name}.part")
        tempFile.delete()

        val copied = runCatching {
            file.inputStream().buffered().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, DELETE_BACKUP_COPY_BUFFER_SIZE)
                    output.fd.sync()
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
