package com.example.comiclab.core.archive

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object ArchiveEntryDeleter {
    private val zipArchiveExtensions get() = ArchiveFormatDetector.zipArchiveExtensions
    private val defaultRenameTo: (File, File) -> Boolean = ArchiveReplacementRecovery::moveWithoutReplacing

    fun canDeleteEntry(file: File): Boolean =
        // 删除仅原格式重写 ZIP；其他格式不得用 ZIP 内容覆盖原路径。
        file.isFile && file.extension.lowercase(Locale.ROOT) in zipArchiveExtensions

    fun deleteEntry(
        file: File,
        entryName: String,
        renameTo: (File, File) -> Boolean = defaultRenameTo
    ): Boolean {
        synchronized(ArchiveReplacementRecovery) {
            if (!ArchiveReplacementRecovery.recover(file, renameTo)) return false
            if (!canDeleteEntry(file) || entryName.isBlank()) {
                return false
            }

            val deleted = deleteZipEntry(file, entryName, renameTo)
            ArchiveReplacementRecovery.recover(file, renameTo)
            return deleted
        }
    }

    private fun deleteZipEntry(
        file: File,
        entryName: String,
        renameTo: (File, File) -> Boolean
    ): Boolean {
        val parent = file.parentFile ?: return false
        val token = UUID.randomUUID()
        val tempFile = File(parent, "${file.name}.delete_$token.tmp")
        val rollbackFile = File(parent, "${file.name}.delete_$token.bak")
        val pending = runCatching {
            ArchiveReplacementRecovery.begin(file, tempFile, rollbackFile)
        }.getOrNull() ?: return false

        val wroteReplacement = runCatching {
            var foundEntry = false
            ZipFile(file).use { zipFile ->
                ZipOutputStream(tempFile.outputStream().buffered()).use { zipOutput ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val sourceEntry = entries.nextElement()
                        if (sourceEntry.name == entryName) {
                            foundEntry = true
                            continue
                        }

                        if (!sourceEntry.isDirectory && ArchiveEntryLister.isImageEntryName(sourceEntry.name)) {
                            val stored = writeStoredZipEntry(zipFile, sourceEntry, zipOutput)
                            if (stored) {
                                continue
                            }
                        }

                        val targetEntry = sourceEntry.copyForZipOutput()
                        zipOutput.putNextEntry(targetEntry)
                        if (!sourceEntry.isDirectory) {
                            zipFile.getInputStream(sourceEntry).use { input ->
                                input.copyTo(zipOutput)
                            }
                        }
                        zipOutput.closeEntry()
                    }
                }
            }

            foundEntry && tempFile.isFile && tempFile.length() > 0L
        }.getOrDefault(false)

        if (!wroteReplacement) {
            tempFile.delete()
            return false
        }

        if (runCatching {
                FileOutputStream(tempFile, true).use { it.fd.sync() }
                ArchiveReplacementRecovery.seal(pending)
            }.isFailure) return false

        return replaceOriginalFileWithTemp(file, tempFile, rollbackFile, pending, renameTo)
    }

    private fun replaceOriginalFileWithTemp(
        originalFile: File,
        tempFile: File,
        rollbackFile: File,
        pending: ArchiveReplacementRecovery.Pending,
        renameTo: (File, File) -> Boolean
    ): Boolean {
        if (!ArchiveReplacementRecovery.matchesOriginal(pending, originalFile)) return false
        if (!renameTo(originalFile, rollbackFile)) {
            tempFile.delete()
            return false
        }

        if (!ArchiveReplacementRecovery.matchesOriginal(pending, rollbackFile)) {
            // 刚移出的内容已变化，先原样放回；不能覆盖这份新内容。
            renameTo(rollbackFile, originalFile)
            return false
        }

        if (renameTo(tempFile, originalFile)) {
            return true
        }

        tempFile.delete()
        renameTo(rollbackFile, originalFile)
        return false
    }

    private fun writeStoredZipEntry(
        zipFile: ZipFile,
        sourceEntry: ZipEntry,
        zipOutput: ZipOutputStream
    ): Boolean {
        if (sourceEntry.size < 0L || sourceEntry.crc < 0L) {
            return false
        }

        val targetEntry = sourceEntry.copyForZipOutput().apply {
            method = ZipEntry.STORED
            size = sourceEntry.size
            compressedSize = sourceEntry.size
            crc = sourceEntry.crc
        }

        zipOutput.putNextEntry(targetEntry)
        zipFile.getInputStream(sourceEntry).use { input ->
            input.copyTo(zipOutput)
        }
        zipOutput.closeEntry()
        return true
    }

    private fun ZipEntry.copyForZipOutput(): ZipEntry {
        return ZipEntry(name).also { target ->
            target.comment = comment
            target.extra = extra
            if (time >= 0L) {
                target.time = time
            }

            if (method == ZipEntry.STORED && size >= 0L && crc >= 0L) {
                target.method = ZipEntry.STORED
                target.size = size
                target.compressedSize = compressedSize.takeIf { it >= 0L } ?: size
                target.crc = crc
            } else {
                target.method = ZipEntry.DEFLATED
            }
        }
    }
}
