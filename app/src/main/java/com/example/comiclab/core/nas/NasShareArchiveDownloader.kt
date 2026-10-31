package com.example.comiclab.core.nas

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.EnumSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal object NasShareArchiveDownloader {
    private const val CACHE_DIRECTORY_NAME = "nas-comics"
    private const val FREE_SPACE_RESERVE_BYTES = 32L * 1024L * 1024L
    private const val BUFFER_SIZE_BYTES = 128 * 1024
    private const val PARTIAL_FILE_STALE_AGE_MS = 60L * 60L * 1000L

    class InsufficientStorageException(
        val requiredBytes: Long,
        val availableBytes: Long
    ) : IOException("Insufficient cache space")

    class DownloadCancelledException : IOException("NAS download cancelled")

    data class CacheSummary(val fileCount: Int, val totalBytes: Long)

    data class CacheClearResult(
        val bytesFreed: Long,
        val bytesRemaining: Long,
        val filesRemaining: Int,
        val completed: Boolean
    )

    data class CachedArchive(val file: File, val sizeBytes: Long) {
        val name: String
            get() = file.name

        val cacheIdentity: String
            get() = file.parentFile?.name.orEmpty()
    }

    fun cacheDirectory(appCacheDirectory: File): File =
        File(appCacheDirectory, CACHE_DIRECTORY_NAME)

    fun cacheSummary(appCacheDirectory: File): CacheSummary {
        val root = checkedCacheRoot(appCacheDirectory)
        if (!root.exists()) return CacheSummary(0, 0L)

        var fileCount = 0
        var totalBytes = 0L
        root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part", ignoreCase = true) }
            .forEach { file ->
                fileCount++
                val fileBytes = file.length()
                totalBytes = if (fileBytes > Long.MAX_VALUE - totalBytes) {
                    Long.MAX_VALUE
                } else {
                    totalBytes + fileBytes
                }
            }
        return CacheSummary(fileCount, totalBytes)
    }

    fun cachedArchives(appCacheDirectory: File): List<CachedArchive> {
        val root = checkedCacheRoot(appCacheDirectory)
        if (!root.isDirectory) return emptyList()

        return root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part", ignoreCase = true) }
            .map { CachedArchive(it, it.length()) }
            .sortedWith(
                compareBy<CachedArchive> { it.name.lowercase(Locale.ROOT) }
                    .thenBy { it.name }
                    .thenBy { it.cacheIdentity }
            )
            .toList()
    }

    fun deleteCachedArchive(appCacheDirectory: File, archive: CachedArchive): Long {
        val canonicalRoot = checkedCacheRoot(appCacheDirectory)
        val file = archive.file.canonicalFile
        check(file.parentFile?.parentFile == canonicalRoot) {
            "NAS cache file resolves outside the cache directory"
        }
        require(!file.name.endsWith(".part", ignoreCase = true)) {
            "Cannot delete an incomplete NAS download as a cached archive"
        }
        if (!file.isFile) throw IOException("NAS cache file no longer exists")

        val bytesFreed = file.length()
        if (!file.delete()) throw IOException("Could not delete NAS cache file")
        file.parentFile?.takeIf { directory ->
            directory.isDirectory && directory.list()?.isEmpty() == true
        }?.delete()
        return bytesFreed
    }

    fun cleanupInterruptedPartialFiles(appCacheDirectory: File): Int {
        val root = checkedCacheRoot(appCacheDirectory)
        if (!root.isDirectory) return 0

        var removedFiles = 0
        var failedFiles = 0
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".part", ignoreCase = true) }
            .forEach { file ->
                if (file.delete() || !file.exists()) {
                    removedFiles++
                } else {
                    failedFiles++
                }
            }

        if (failedFiles > 0) {
            throw IOException("Could not remove $failedFiles interrupted NAS partial file(s)")
        }
        return removedFiles
    }

    fun clearCache(appCacheDirectory: File): CacheClearResult {
        val root = checkedCacheRoot(appCacheDirectory)
        val before = cacheSummary(appCacheDirectory)
        val deleted = !root.exists() || root.deleteRecursively()
        val after = cacheSummary(appCacheDirectory)
        return CacheClearResult(
            bytesFreed = (before.totalBytes - after.totalBytes).coerceAtLeast(0L),
            bytesRemaining = after.totalBytes,
            filesRemaining = after.fileCount,
            completed = deleted && after.fileCount == 0
        )
    }

    fun cacheFile(
        cacheRoot: File,
        profile: NasShareProfile,
        entry: NasDirectoryEntry
    ): File {
        require(!entry.isDirectory) { "Cannot cache a directory" }
        validateRemotePath(entry.relativePath)
        require(entry.name.isNotBlank() && entry.name != "." && entry.name != "..") {
            "Invalid NAS file name"
        }
        require(!entry.name.contains('/') && !entry.name.contains('\\') && '\u0000' !in entry.name) {
            "Invalid NAS file name"
        }

        val identity = listOf(
            profile.id,
            profile.host,
            profile.shareName,
            entry.relativePath,
            entry.sizeBytes.toString(),
            entry.modifiedAt.toString()
        ).joinToString("\u0000")
        val directoryName = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(File(cacheRoot, directoryName), entry.name)
    }

    fun checkAvailableSpace(cacheRoot: File, archiveSizeBytes: Long) {
        require(archiveSizeBytes > 0L) { "Invalid archive size" }
        if (!cacheRoot.isDirectory && !cacheRoot.mkdirs()) {
            throw IOException("Could not prepare NAS cache directory")
        }
        ensureAvailableSpace(cacheRoot, archiveSizeBytes)
    }

    fun download(
        profile: NasShareProfile,
        password: String,
        entry: NasDirectoryEntry,
        destination: File,
        cancelled: AtomicBoolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        require(!entry.isDirectory) { "Cannot download a directory" }
        validateRemotePath(entry.relativePath)
        val parent = destination.parentFile
            ?: throw IOException("NAS cache destination has no parent")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not prepare NAS cache directory")
        }
        if (destination.isFile && destination.length() == entry.sizeBytes && entry.sizeBytes > 0L) {
            return destination
        }

        val cacheRoot = parent.parentFile ?: parent
        cleanupPartialFiles(cacheRoot)
        val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.part")
        var completed = false
        try {
            checkNotCancelled(cancelled)
            NasShareDirectoryBrowser.withDiskShare(profile, password) { share ->
                val remoteFile = share.openFile(
                    entry.relativePath.replace('/', '\\'),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                )
                remoteFile.use { source ->
                    val totalBytes = source.length
                    if (totalBytes <= 0L) throw EOFException("NAS archive is empty")
                    ensureAvailableSpace(parent, totalBytes)
                    onProgress(0L, totalBytes)

                    var downloadedBytes = 0L
                    FileOutputStream(temporary).use { output ->
                        source.inputStream.use { input ->
                            val buffer = ByteArray(BUFFER_SIZE_BYTES)
                            while (true) {
                                checkNotCancelled(cancelled)
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                if (downloadedBytes > totalBytes - count) {
                                    throw IOException("NAS archive changed during download")
                                }

                                output.write(buffer, 0, count)
                                downloadedBytes += count
                                onProgress(downloadedBytes, totalBytes)
                            }
                        }
                        output.fd.sync()
                    }

                    checkNotCancelled(cancelled)
                    if (downloadedBytes != totalBytes) {
                        throw EOFException("NAS archive changed during download")
                    }
                    if (destination.exists() && !destination.delete()) {
                        throw IOException("Could not replace invalid NAS cache file")
                    }
                    if (!temporary.renameTo(destination)) {
                        throw IOException("Could not finalize NAS cache file")
                    }
                    onProgress(totalBytes, totalBytes)
                }
            }
            completed = true
            return destination
        } finally {
            if (!completed) temporary.delete()
        }
    }

    fun cleanupPartialFiles(cacheRoot: File) {
        if (!cacheRoot.isDirectory) return
        val staleBefore = System.currentTimeMillis() - PARTIAL_FILE_STALE_AGE_MS
        cacheRoot.walkTopDown()
            .filter {
                it.isFile && it.name.endsWith(".part", ignoreCase = true) &&
                    it.lastModified() < staleBefore
            }
            .forEach(File::delete)
    }

    private fun ensureAvailableSpace(directory: File, archiveSizeBytes: Long) {
        val requiredBytes = if (archiveSizeBytes > Long.MAX_VALUE - FREE_SPACE_RESERVE_BYTES) {
            Long.MAX_VALUE
        } else {
            archiveSizeBytes + FREE_SPACE_RESERVE_BYTES
        }
        val availableBytes = directory.usableSpace
        if (availableBytes < requiredBytes) {
            throw InsufficientStorageException(requiredBytes, availableBytes)
        }
    }

    private fun checkedCacheRoot(appCacheDirectory: File): File {
        val canonicalAppCacheDirectory = appCacheDirectory.canonicalFile
        val canonicalRoot = cacheDirectory(canonicalAppCacheDirectory).canonicalFile
        check(canonicalRoot.parentFile == canonicalAppCacheDirectory) {
            "NAS cache directory resolves outside the app cache"
        }
        return canonicalRoot
    }

    private fun validateRemotePath(path: String) {
        val segments = path.split('/', '\\').filter(String::isNotBlank)
        require(segments.isNotEmpty()) { "NAS file path is empty" }
        require(segments.none { it == "." || it == ".." || it.contains(':') }) {
            "Invalid path inside the SMB share"
        }
    }

    private fun checkNotCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw DownloadCancelledException()
        }
    }
}
