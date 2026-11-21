package com.example.comiclab.core.transfer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest

internal object TransferFileMove {
    enum class Result {
        MOVED,
        TARGET_EXISTS,
        UNAVAILABLE,
        FAILED
    }

    enum class Publication {
        MOVED,
        COPIED,
        TARGET_EXISTS,
        INCOMPLETE,
        FAILED
    }

    internal data class CopiedFileSnapshot(
        val file: File,
        val length: Long,
        val lastModified: Long,
        val sha256: ByteArray
    )

    private val nativeAvailable = try {
        System.loadLibrary("comiclab_filetransfer")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun move(source: File, target: File): Result {
        if (!nativeAvailable) return Result.UNAVAILABLE
        return when (moveNoReplace(source.pathBytes(), target.pathBytes())) {
            0 -> Result.MOVED
            1 -> Result.TARGET_EXISTS
            2 -> Result.UNAVAILABLE
            else -> Result.FAILED
        }
    }

    fun publish(
        staging: File,
        target: File,
        move: ((File, File) -> Result)?,
        onPublishingCopy: () -> Unit
    ): Publication {
        if (move == null) {
            return publishMoved(staging, target)
        }
        return when (move(staging, target)) {
            Result.MOVED -> Publication.MOVED
            Result.TARGET_EXISTS -> Publication.TARGET_EXISTS
            Result.UNAVAILABLE -> {
                onPublishingCopy()
                publishFallback(staging, target)
            }
            Result.FAILED -> Publication.FAILED
        }
    }

    internal fun copyFileWithDigest(
        sourceFile: File,
        targetFile: File,
        interruptionMessage: String,
        onBytesCopied: (Long) -> Unit = {}
    ): CopiedFileSnapshot {
        val lastModifiedBefore = sourceFile.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        sourceFile.inputStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedException(interruptionMessage)
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    copiedBytes += count
                    onBytesCopied(count.toLong())
                }
                output.flush()
            }
        }
        return CopiedFileSnapshot(
            file = sourceFile,
            length = copiedBytes,
            lastModified = lastModifiedBefore,
            sha256 = digest.digest()
        )
    }

    internal fun matchesMetadataAndContent(snapshot: CopiedFileSnapshot): Boolean {
        if (!matchesMetadata(snapshot)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = runCatching {
            snapshot.file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (Thread.currentThread().isInterrupted) return false
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest()
        }.getOrNull() ?: return false
        return actual.contentEquals(snapshot.sha256)
    }

    internal fun matchesMetadata(snapshot: CopiedFileSnapshot): Boolean {
        val file = snapshot.file
        return !Files.isSymbolicLink(file.toPath()) &&
            file.isFile &&
            file.length() == snapshot.length &&
            file.lastModified() == snapshot.lastModified
    }

    private fun publishFallback(staging: File, target: File): Publication {
        if (Thread.currentThread().isInterrupted) return Publication.FAILED
        if (Files.isSymbolicLink(staging.toPath())) return Publication.FAILED
        if (!staging.isDirectory) {
            return publishMoved(staging, target)
        }
        var createdTarget = false
        return try {
            // Atomically reserve the target directory, then move staged children without re-copying bytes.
            Files.createDirectory(target.toPath())
            createdTarget = true
            val children = staging.listFiles() ?: throw IOException("Could not read transfer staging")
            for (child in children) {
                checkNotInterrupted()
                Files.move(child.toPath(), File(target, child.name).toPath())
            }
            Files.deleteIfExists(staging.toPath())
            Publication.MOVED
        } catch (_: FileAlreadyExistsException) {
            if (createdTarget) Publication.INCOMPLETE else Publication.TARGET_EXISTS
        } catch (_: IOException) {
            if (createdTarget) Publication.INCOMPLETE else Publication.FAILED
        } catch (_: InterruptedException) {
            if (createdTarget) Publication.INCOMPLETE else Publication.FAILED
        }
    }

    private fun publishMoved(staging: File, target: File): Publication = try {
        Files.move(staging.toPath(), target.toPath())
        Publication.MOVED
    } catch (_: FileAlreadyExistsException) {
        Publication.TARGET_EXISTS
    } catch (_: IOException) {
        Publication.FAILED
    }

    // JNI 的字符串接口使用 modified UTF-8。这里按文件系统字节传递路径，以支持补充字符。
    private fun File.pathBytes(): ByteArray = absolutePath.toByteArray(Charsets.UTF_8) + 0.toByte()

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private external fun moveNoReplace(source: ByteArray, target: ByteArray): Int
}
