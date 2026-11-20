package com.example.comiclab.core.transfer

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

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
            // 普通复制保留现有暂存发布流程。
            return try {
                Files.move(staging.toPath(), target.toPath())
                Publication.MOVED
            } catch (_: FileAlreadyExistsException) {
                Publication.TARGET_EXISTS
            }
        }
        return when (move(staging, target)) {
            Result.MOVED -> Publication.MOVED
            Result.TARGET_EXISTS -> Publication.TARGET_EXISTS
            Result.UNAVAILABLE -> {
                onPublishingCopy()
                publishByCopying(staging, target)
            }
            Result.FAILED -> Publication.FAILED
        }
    }

    private fun publishByCopying(staging: File, target: File): Publication {
        var createdTarget = false
        return try {
            copyToNewPath(staging, target) { createdTarget = true }
            Publication.COPIED
        } catch (_: FileAlreadyExistsException) {
            if (createdTarget) Publication.INCOMPLETE else Publication.TARGET_EXISTS
        } catch (_: IOException) {
            if (createdTarget) Publication.INCOMPLETE else Publication.FAILED
        } catch (_: InterruptedException) {
            if (createdTarget) Publication.INCOMPLETE else Publication.FAILED
        }
    }

    private fun copyToNewPath(source: File, target: File, onCreated: () -> Unit = {}) {
        checkNotInterrupted()
        if (Files.isSymbolicLink(source.toPath())) {
            throw IOException("Unexpected symbolic link in transfer staging")
        }
        if (source.isDirectory) {
            Files.createDirectory(target.toPath())
            onCreated()
            val children = source.listFiles() ?: throw IOException("Could not read transfer staging")
            for (child in children) {
                copyToNewPath(child, File(target, child.name))
            }
        } else {
            Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .use { output ->
                    onCreated()
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkNotInterrupted()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
        }
    }

    // JNI 的字符串接口使用 modified UTF-8。这里按文件系统字节传递路径，以支持补充字符。
    private fun File.pathBytes(): ByteArray = absolutePath.toByteArray(Charsets.UTF_8) + 0.toByte()

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private external fun moveNoReplace(source: ByteArray, target: ByteArray): Int
}
