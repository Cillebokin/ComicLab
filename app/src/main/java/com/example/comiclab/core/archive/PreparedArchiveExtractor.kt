package com.example.comiclab.core.archive

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.comiclab.core.archive.ArchiveImageDecoder.decodeFileBitmap
import com.example.comiclab.core.archive.ArchiveImageDecoder.decodeRegionForWidth
import com.example.comiclab.core.archive.ArchiveImageDecoder.calculateInSampleSizeForWidth
import com.example.comiclab.core.archive.ArchiveImageDecoder.calculateInSampleSizeForHeight
import com.example.comiclab.core.archive.ArchiveImageDecoder.scaleToWidthIfLarger
import com.example.comiclab.core.archive.ArchiveImageDecoder.scaleToFitBoundsIfLarger
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipFile

/** Prepared archive files, manifest mapping and extraction. */
internal object PreparedArchiveExtractor {
    private const val PREPARED_READER_MANIFEST_FILE = "reader_manifest.json"
    private const val PREPARED_IMAGE_INTEGRITY_SUFFIX = ".sha256"
    private const val PREPARED_IMAGE_INTEGRITY_VERSION = "1"
    private val zipArchiveExtensions get() = ArchiveFormatDetector.zipArchiveExtensions
    private val sevenZipArchiveExtensions get() = ArchiveFormatDetector.sevenZipArchiveExtensions
    private val imageExtensions get() = ArchiveEntryLister.imageExtensions

    data class PreparedImageEntry(val index: Int, val entryName: String)

    fun extractImageEntryToFile(file: File, entryName: String, targetFile: File): Boolean {
        ArchiveReplacementRecovery.recover(file)
        if (!file.isFile || entryName.isBlank() || !entryName.isImageEntryName() || isPdf(file)) {
            return false
        }

        return runCatching {
            when (file.extension.lowercase(Locale.ROOT)) {
                in zipArchiveExtensions -> extractZipEntryToFile(file, entryName, targetFile)
                in sevenZipArchiveExtensions -> withSevenZipArchive(file) { archive ->
                    val entryIndex = (0 until archive.numberOfItems).firstOrNull { index ->
                        !archive.isFolder(index) && archive.entryPath(index) == entryName
                    } ?: return@withSevenZipArchive false
                    extractSevenZipEntryToFile(archive, entryIndex, targetFile)
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    fun openPreparedReaderSession(directory: File, deleteOnClose: Boolean): ArchiveReaderSession {
        return PreparedReaderSession(directory, deleteOnClose)
    }

    fun openPreparedImageExtractor(file: File, outputDir: File): PreparedImageExtractor {
        return PreparedImageExtractor(file, outputDir)
    }

    fun preparedImageEntries(directory: File): List<String> {
        readPreparedReaderManifest(directory)?.let { return it }
        return preparedImageFiles(directory).map { it.name }
    }

    fun writePreparedReaderManifest(directory: File, entryNames: List<String>) {
        writePreparedReaderManifest(directory, entryNames) { output, contents ->
            output.write(contents.toByteArray(Charsets.UTF_8))
        }
    }

    internal fun writePreparedReaderManifest(
        directory: File,
        entryNames: List<String>,
        writeContents: (OutputStream, String) -> Unit
    ) {
        directory.mkdirs()
        val array = JSONArray()
        entryNames.forEach { array.put(it) }
        val manifestFile = File(directory, PREPARED_READER_MANIFEST_FILE)
        val tempFile = File.createTempFile("$PREPARED_READER_MANIFEST_FILE.", ".tmp", directory)
        try {
            FileOutputStream(tempFile).use { output ->
                writeContents(output, array.toString())
                output.fd.sync()
            }
            Files.move(
                tempFile.toPath(),
                manifestFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            tempFile.delete()
        }
    }

    fun preparedImageFile(directory: File, index: Int, entryName: String): File {
        return File(directory, preparedImageFileName(index, entryName))
    }

    fun imageFileBounds(file: File): ArchiveReaderSessionBounds? {
        return decodeFileBounds(file)
    }

    fun validatePreparedImageFile(file: File): Boolean {
        clearInterruptedWriteFiles(file)
        val valid = runCatching {
            if (!file.isFile || file.length() <= 0L) {
                return@runCatching false
            }

            val fields = integrityFile(file).readText(Charsets.US_ASCII).split(':')
            fields.size == 3 &&
                fields[0] == PREPARED_IMAGE_INTEGRITY_VERSION &&
                fields[1].toLongOrNull() == file.length() &&
                fields[2].equals(sha256(file), ignoreCase = true)
        }.getOrDefault(false)

        if (!valid) {
            discardPreparedImageFile(file)
        }
        return valid
    }

    fun discardPreparedImageFile(file: File) {
        file.delete()
        integrityFile(file).delete()
        clearInterruptedWriteFiles(file)
    }

    fun extractPreparedImagesToDirectory(
        file: File,
        entries: List<PreparedImageEntry>,
        outputDir: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> {
        if (entries.isEmpty()) {
            onProgress(0, 0)
            return emptyList()
        }

        outputDir.mkdirs()
        onProgress(0, entries.size)
        val extractedFiles = mutableListOf<File>()
        openPreparedImageExtractor(file, outputDir).use { extractor ->
            entries.forEachIndexed { progressIndex, entry ->
                if (!shouldContinue()) {
                    return extractedFiles
                }

                extractor.extract(entry.index, entry.entryName)?.let { extractedFiles.add(it) }
                onProgress(progressIndex + 1, entries.size)
            }
        }
        return extractedFiles
    }

    fun extractImagesToDirectory(
        file: File,
        entryNames: List<String>,
        outputDir: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> {
        val entries = entryNames
        if (entries.isEmpty()) {
            onProgress(0, 0)
            return emptyList()
        }

        outputDir.deleteRecursively()
        outputDir.mkdirs()
        writePreparedReaderManifest(outputDir, entries)
        onProgress(0, entries.size)

        val extractedFiles = when (file.extension.lowercase(Locale.ROOT)) {
            in zipArchiveExtensions -> extractZipImagesToDirectory(
                file,
                entries,
                outputDir,
                shouldContinue,
                onProgress
            )
            in sevenZipArchiveExtensions -> extractSevenZipImagesToDirectory(
                file,
                entries,
                outputDir,
                shouldContinue,
                onProgress
            )
            else -> emptyList()
        }

        if (shouldContinue() && extractedFiles.size != entries.size) {
            outputDir.deleteRecursively()
            return emptyList()
        }

        return extractedFiles.sortedBy { it.name }
    }

    class PreparedImageExtractor internal constructor(
        private val file: File,
        private val outputDir: File
    ) : Closeable {
        init {
            ArchiveReplacementRecovery.recover(file)
        }

        private val extension = file.extension.lowercase(Locale.ROOT)
        private val zipFile = if (extension in zipArchiveExtensions) ZipFile(file) else null
        private val randomAccessFile =
            if (extension in sevenZipArchiveExtensions) RandomAccessFile(file, "r") else null
        private val sevenZipArchive =
            randomAccessFile?.let { SevenZip.openInArchive(null, RandomAccessFileInStream(it)) }
        private val sevenZipEntryIndexes by lazy {
            sevenZipArchive?.let { archive ->
                (0 until archive.numberOfItems)
                    .asSequence()
                    .filter { !archive.isFolder(it) }
                    .mapNotNull { index -> archive.entryPath(index)?.let { path -> path to index } }
                    .toMap()
            } ?: emptyMap()
        }

        fun extract(index: Int, entryName: String): File? {
            val writerLease = PreparedReaderCacheLeaseRegistry.process
                .acquireWriterLease(outputDir)
                ?: return null
            return try {
                writerLease.withWriteLock {
                    synchronized(this) {
                        val targetFile = preparedImageFile(outputDir, index, entryName)
                        if (validatePreparedImageFile(targetFile)) {
                            return@synchronized targetFile
                        }

                        when (extension) {
                            in zipArchiveExtensions -> extractZipEntry(entryName, targetFile)
                            in sevenZipArchiveExtensions -> extractSevenZipEntry(entryName, targetFile)
                            else -> null
                        }
                    }
                }
            } finally {
                writerLease.close()
            }
        }

        override fun close() {
            zipFile?.close()
            sevenZipArchive?.close()
            randomAccessFile?.close()
        }

        private fun extractZipEntry(entryName: String, targetFile: File): File? {
            val zip = zipFile ?: return null
            val entry = zip.getEntry(entryName) ?: return null
            val copied = runCatching {
                writeAtomic(targetFile, writeIntegrity = true) { output ->
                    zip.getInputStream(entry).use { input ->
                        input.copyTo(output)
                    }
                }
            }.getOrDefault(false)
            return targetFile.takeIf { copied && it.isFile && it.length() > 0L }
        }

        private fun extractSevenZipEntry(entryName: String, targetFile: File): File? {
            val archive = sevenZipArchive ?: return null
            val entryIndex = sevenZipEntryIndexes[entryName] ?: return null
            val copied = extractSevenZipEntryToFile(archive, entryIndex, targetFile, writeIntegrity = true)
            return targetFile.takeIf { copied && it.isFile && it.length() > 0L }
        }
    }

    private class PreparedReaderSession(
        private val directory: File,
        private val deleteOnClose: Boolean
    ) : ArchiveReaderSession {

        private val filesByEntryName = readPreparedReaderManifest(directory)
            ?.mapIndexed { index, entryName -> entryName to preparedImageFile(directory, index, entryName) }
            ?.toMap()
            ?: preparedImageFiles(directory).associateBy { it.name }

        override fun readBounds(entryName: String): ArchiveReaderSessionBounds? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return decodeFileBounds(imageFile)
        }

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return decodeFileBitmap(
                file = imageFile,
                preferredConfig = Bitmap.Config.RGB_565,
                sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
            )?.scaleToWidthIfLarger(targetWidth)
        }

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return decodeFileBitmap(
                file = imageFile,
                preferredConfig = Bitmap.Config.ARGB_8888,
                sampleSize = { width, _ -> calculateInSampleSizeForWidth(width, targetWidth) }
            )?.scaleToWidthIfLarger(targetWidth)
        }

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return decodeFileBitmap(
                file = imageFile,
                preferredConfig = Bitmap.Config.ARGB_8888,
                sampleSize = { width, height ->
                    maxOf(
                        calculateInSampleSizeForWidth(width, targetWidth),
                        calculateInSampleSizeForHeight(height, targetHeight)
                    )
                }
            )?.scaleToFitBoundsIfLarger(targetWidth, targetHeight)
        }

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? {
            val imageFile = filesByEntryName[entryName] ?: return null
            return imageFile.inputStream().use { stream ->
                decodeRegionForWidth(stream, sourceRect, targetWidth)
            }
        }

        override fun close() {
            if (deleteOnClose) {
                directory.deleteRecursively()
            }
        }
    }

    private fun extractZipEntryToFile(file: File, entryName: String, targetFile: File): Boolean {
        ZipFile(file).use { zipFile ->
            val entry = zipFile.getEntry(entryName) ?: return false
            val copied = writeAtomic(targetFile) { output ->
                zipFile.getInputStream(entry).use { input ->
                    input.copyTo(output)
                }
            }
            if (!copied) {
                targetFile.delete()
            }
            return copied && targetFile.isFile && targetFile.length() > 0L
        }
    }

    private fun extractZipImagesToDirectory(
        file: File,
        entryNames: List<String>,
        outputDir: File,
        shouldContinue: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> {
        val extractedFiles = mutableListOf<File>()
        ZipFile(file).use { zipFile ->
            entryNames.forEachIndexed { index, entryName ->
                if (!shouldContinue()) {
                    return extractedFiles
                }

                val targetFile = preparedImageFile(outputDir, index, entryName)
                val entry = zipFile.getEntry(entryName)
                if (entry != null) {
                    val copied = runCatching {
                        writeAtomic(targetFile, writeIntegrity = true) { output ->
                            zipFile.getInputStream(entry).use { input ->
                                input.copyTo(output)
                            }
                        }
                    }.getOrDefault(false)
                    if (copied && targetFile.isFile && targetFile.length() > 0L) {
                        extractedFiles.add(targetFile)
                    } else {
                        discardPreparedImageFile(targetFile)
                    }
                }
                onProgress(index + 1, entryNames.size)
            }
        }
        return extractedFiles
    }

    private fun extractSevenZipImagesToDirectory(
        file: File,
        entryNames: List<String>,
        outputDir: File,
        shouldContinue: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<File> {
        return withSevenZipArchive(file) { archive ->
            val entryIndexesByPath = (0 until archive.numberOfItems)
                .asSequence()
                .filter { !archive.isFolder(it) }
                .mapNotNull { index -> archive.entryPath(index)?.let { path -> path to index } }
                .toMap()
            val extractedFiles = mutableListOf<File>()

            entryNames.forEachIndexed { index, entryName ->
                if (!shouldContinue()) {
                    return@withSevenZipArchive extractedFiles
                }

                val targetFile = preparedImageFile(outputDir, index, entryName)
                val entryIndex = entryIndexesByPath[entryName]
                if (entryIndex != null && extractSevenZipEntryToFile(
                        archive,
                        entryIndex,
                        targetFile,
                        writeIntegrity = true
                    )
                ) {
                    extractedFiles.add(targetFile)
                } else {
                    discardPreparedImageFile(targetFile)
                }
                onProgress(index + 1, entryNames.size)
            }

            extractedFiles
        }
    }

    internal fun extractSevenZipEntryToFile(
        archive: IInArchive,
        entryIndex: Int,
        targetFile: File,
        writeIntegrity: Boolean = false
    ): Boolean {
        var extractResult = ExtractOperationResult.OK
        val wroteFile = writeAtomic(targetFile, writeIntegrity) { output ->
            archive.extract(intArrayOf(entryIndex), false, object : IArchiveExtractCallback {
                override fun setTotal(total: Long) = Unit

                override fun setCompleted(completeValue: Long) = Unit

                override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
                    if (index != entryIndex || extractAskMode != ExtractAskMode.EXTRACT) {
                        return null
                    }

                    return object : ISequentialOutStream {
                        override fun write(data: ByteArray): Int {
                            output.write(data)
                            return data.size
                        }
                    }
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    extractResult = extractOperationResult
                }
            })
        }

        val success = wroteFile &&
            extractResult == ExtractOperationResult.OK &&
            targetFile.isFile &&
            targetFile.length() > 0L
        if (!success) {
            if (writeIntegrity) {
                discardPreparedImageFile(targetFile)
            } else {
                targetFile.delete()
            }
        }
        return success
    }

    private fun readPreparedReaderManifest(directory: File): List<String>? {
        val manifestFile = File(directory, PREPARED_READER_MANIFEST_FILE)
        if (!manifestFile.isFile) {
            return null
        }

        return runCatching {
            val array = JSONArray(manifestFile.readText())
            (0 until array.length()).map { index -> array.getString(index) }
        }.getOrNull()
    }

    private fun preparedImageFiles(directory: File): List<File> {
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.isImageEntryName() }
            ?.sortedBy { it.name }
            ?.toList()
            ?: emptyList()
    }

    private fun preparedImageFileName(index: Int, entryName: String): String {
        val extension = entryName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in imageExtensions }
            ?: "img"
        val hash = Integer.toHexString(entryName.hashCode())
        return String.format(Locale.ROOT, "%06d_%s.%s", index, hash, extension)
    }

    private fun writeAtomic(
        targetFile: File,
        writeIntegrity: Boolean = false,
        writeBlock: (OutputStream) -> Unit
    ): Boolean {
        val parent = targetFile.parentFile ?: File(".")
        if (!parent.isDirectory && !parent.mkdirs()) {
            return false
        }
        val tempFile = File(parent, "${targetFile.name}.part")
        val metadataFile = integrityFile(targetFile).takeIf { writeIntegrity }
        val metadataTempFile = metadataFile?.let { File(parent, "${it.name}.part") }
        clearInterruptedWriteFiles(targetFile)
        val digest = if (writeIntegrity) MessageDigest.getInstance("SHA-256") else null
        val wrote = runCatching {
            FileOutputStream(tempFile).use { output ->
                val writeOutput = digest?.let { DigestOutputStream(output, it) } ?: output
                writeBlock(writeOutput)
                writeOutput.flush()
                output.fd.sync()
            }
            tempFile.isFile && tempFile.length() > 0L
        }.getOrDefault(false)

        if (!wrote) {
            tempFile.delete()
            metadataTempFile?.delete()
            return false
        }

        if (digest == null || metadataFile == null || metadataTempFile == null) {
            if (!replaceAtomically(tempFile, targetFile)) {
                tempFile.delete()
                return false
            }
            return true
        }

        val metadata = "$PREPARED_IMAGE_INTEGRITY_VERSION:${tempFile.length()}:${digest.digest().toHexString()}"
        val metadataWrote = runCatching {
            FileOutputStream(metadataTempFile).use { output ->
                output.write(metadata.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            metadataTempFile.isFile && metadataTempFile.length() > 0L
        }.getOrDefault(false)
        if (!metadataWrote) {
            tempFile.delete()
            metadataTempFile.delete()
            return false
        }

        if (!replaceAtomically(tempFile, targetFile)) {
            tempFile.delete()
            metadataTempFile.delete()
            return false
        }
        if (!replaceAtomically(metadataTempFile, metadataFile)) {
            targetFile.delete()
            metadataFile.delete()
            metadataTempFile.delete()
            return false
        }
        return true
    }

    private fun integrityFile(file: File): File {
        val parent = file.parentFile ?: File(".")
        return File(parent, "${file.name}$PREPARED_IMAGE_INTEGRITY_SUFFIX")
    }

    private fun clearInterruptedWriteFiles(file: File) {
        val parent = file.parentFile ?: File(".")
        File(parent, "${file.name}.part").delete()
        File(parent, "${integrityFile(file).name}.part").delete()
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHexString()
    }.getOrNull()

    private fun ByteArray.toHexString(): String {
        val bytes = this
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 0x0F])
            }
        }
    }

    private fun replaceAtomically(source: File, destination: File): Boolean = runCatching {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        true
    }.getOrDefault(false)

    private fun isPdf(file: File): Boolean = ArchiveFormatDetector.isPdf(file)

    private fun String.isImageEntryName(): Boolean = ArchiveEntryLister.isImageEntryName(this)

    private fun IInArchive.isFolder(index: Int): Boolean =
        getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false

    private fun IInArchive.entryPath(index: Int): String? =
        getProperty(index, PropID.PATH) as? String

    private fun decodeFileBounds(file: File): ArchiveReaderSessionBounds? =
        ArchiveImageDecoder.decodeFileBounds(file)?.let { ArchiveReaderSessionBounds(it.width, it.height) }

    private fun <T> withSevenZipArchive(file: File, block: (IInArchive) -> T): T =
        ArchiveReaderSessionFactory.withSevenZipArchive(file, block)
}
