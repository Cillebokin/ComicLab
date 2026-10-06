package com.example.comiclab.core.archive

import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.PropID
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object ArchiveEntryDeleter {
    private val zipArchiveExtensions get() = ArchiveFormatDetector.zipArchiveExtensions
    private val sevenZipArchiveExtensions get() = ArchiveFormatDetector.sevenZipArchiveExtensions
    private val defaultRenameTo: (File, File) -> Boolean = { source, destination ->
        source.renameTo(destination)
    }

    fun canDeleteEntry(file: File): Boolean =
        ArchiveFormatDetector.isSupportedArchive(file) && !ArchiveFormatDetector.isPdf(file)

    fun deleteEntry(
        file: File,
        entryName: String,
        withSevenZipArchive: (File, (IInArchive) -> Unit) -> Unit,
        extractSevenZipEntryToStream: (IInArchive, Int, OutputStream) -> Boolean,
        renameTo: (File, File) -> Boolean = defaultRenameTo
    ): Boolean {
        if (!canDeleteEntry(file) || entryName.isBlank()) {
            return false
        }

        val backupFile = ArchiveBackupManager.createDeleteBackup(file, renameTo) ?: return false
        val deleted = when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> deleteZipEntry(file, entryName, renameTo)
            in sevenZipArchiveExtensions -> rebuildSevenZipReadableArchiveWithoutEntry(
                file = file,
                entryName = entryName,
                withSevenZipArchive = withSevenZipArchive,
                extractSevenZipEntryToStream = extractSevenZipEntryToStream,
                renameTo = renameTo
            )
            else -> false
        }

        if (!deleted) {
            ArchiveBackupManager.restoreOriginalFromDeleteBackupIfNeeded(file, backupFile, renameTo)
        }

        return deleted
    }

    private fun deleteZipEntry(
        file: File,
        entryName: String,
        renameTo: (File, File) -> Boolean
    ): Boolean {
        val parent = file.parentFile ?: return false
        val tempFile = File(parent, "${file.name}.delete_${System.currentTimeMillis()}.tmp")
        val backupFile = File(parent, "${file.name}.delete_${System.currentTimeMillis()}.bak")
        tempFile.delete()
        backupFile.delete()

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

        return replaceOriginalFileWithTemp(file, tempFile, backupFile, renameTo)
    }

    private fun rebuildSevenZipReadableArchiveWithoutEntry(
        file: File,
        entryName: String,
        withSevenZipArchive: (File, (IInArchive) -> Unit) -> Unit,
        extractSevenZipEntryToStream: (IInArchive, Int, OutputStream) -> Boolean,
        renameTo: (File, File) -> Boolean
    ): Boolean {
        val parent = file.parentFile ?: return false
        val timestamp = System.currentTimeMillis()
        val tempFile = File(parent, "${file.name}.delete_${timestamp}.tmp")
        val backupFile = File(parent, "${file.name}.delete_${timestamp}.bak")
        val workDir = File(parent, "${file.name}.delete_${timestamp}_work")
        tempFile.delete()
        backupFile.delete()
        workDir.deleteRecursively()

        val wroteReplacement = try {
            runCatching {
                var foundEntry = false
                withSevenZipArchive(file) { archive ->
                    ZipOutputStream(tempFile.outputStream().buffered()).use { zipOutput ->
                        for (index in 0 until archive.numberOfItems) {
                            val path = archive.getProperty(index, PropID.PATH) as? String ?: continue
                            if (path == entryName) {
                                foundEntry = true
                                continue
                            }

                            val isFolder = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
                            val lastModified = archive.getProperty(index, PropID.LAST_MODIFICATION_TIME) as? Date
                            val zipEntry = ZipEntry(path.toZipEntryPath(isFolder)).apply {
                                lastModified?.let { time = it.time }
                            }

                            if (!isFolder && ArchiveEntryLister.isImageEntryName(path)) {
                                val stored = writeStoredSevenZipEntry(
                                    archive = archive,
                                    entryIndex = index,
                                    entryPath = path,
                                    lastModified = lastModified,
                                    zipOutput = zipOutput,
                                    workDir = workDir,
                                    extractSevenZipEntryToStream = extractSevenZipEntryToStream
                                )
                                if (stored) {
                                    continue
                                }
                            }

                            zipOutput.putNextEntry(zipEntry)
                            if (!isFolder) {
                                val extracted = extractSevenZipEntryToStream(archive, index, zipOutput)
                                if (!extracted) {
                                    throw IOException("Failed to extract archive entry: $path")
                                }
                            }
                            zipOutput.closeEntry()
                        }
                    }
                }

                foundEntry && tempFile.isFile && tempFile.length() > 0L
            }.getOrDefault(false)
        } finally {
            workDir.deleteRecursively()
        }

        if (!wroteReplacement) {
            tempFile.delete()
            return false
        }

        return replaceOriginalFileWithTemp(file, tempFile, backupFile, renameTo)
    }

    private fun replaceOriginalFileWithTemp(
        originalFile: File,
        tempFile: File,
        backupFile: File,
        renameTo: (File, File) -> Boolean
    ): Boolean {
        if (!renameTo(originalFile, backupFile)) {
            tempFile.delete()
            return false
        }

        if (renameTo(tempFile, originalFile)) {
            backupFile.delete()
            return true
        }

        tempFile.delete()
        renameTo(backupFile, originalFile)
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

    private fun writeStoredSevenZipEntry(
        archive: IInArchive,
        entryIndex: Int,
        entryPath: String,
        lastModified: Date?,
        zipOutput: ZipOutputStream,
        workDir: File,
        extractSevenZipEntryToStream: (IInArchive, Int, OutputStream) -> Boolean
    ): Boolean {
        workDir.mkdirs()
        val extractedFile = File(workDir, String.format(Locale.ROOT, "%06d.entry", entryIndex))
        extractedFile.delete()

        val extracted = runCatching {
            extractedFile.outputStream().buffered().use { output ->
                extractSevenZipEntryToStream(archive, entryIndex, output)
            }
        }.getOrDefault(false)
        if (!extracted || !extractedFile.isFile) {
            extractedFile.delete()
            return false
        }

        val entrySize = extractedFile.length()
        val entry = ZipEntry(entryPath).apply {
            method = ZipEntry.STORED
            size = entrySize
            compressedSize = entrySize
            crc = crc32(extractedFile)
            lastModified?.let { time = it.time }
        }

        zipOutput.putNextEntry(entry)
        extractedFile.inputStream().buffered().use { input ->
            input.copyTo(zipOutput)
        }
        zipOutput.closeEntry()
        extractedFile.delete()
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

    private fun String.toZipEntryPath(isFolder: Boolean): String =
        if (isFolder && !endsWith("/")) "$this/" else this

    private fun crc32(file: File): Long {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }
}
