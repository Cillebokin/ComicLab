package com.example.comiclab.core.archive

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** 通过短期回滚文件和持久日志恢复压缩包替换；兼容读取旧版完整备份日志。 */
internal object ArchiveReplacementRecovery {
    private const val SUFFIX = ".replace.properties"
    private const val LEGACY_VERSION = "1"
    private const val VERSION = "2"

    internal data class Pending(
        val original: File,
        val backup: File?,
        val temporary: File,
        val rollback: File,
        val originalHash: String,
        val modifiedAt: Long,
        val replacementHash: String = ""
    ) {
        val journal: File get() = journalFile(original)
        val restore: File get() = File(original.parentFile,
            "${original.name}.restore_${temporary.name.removePrefix("${original.name}.delete_").removeSuffix(".tmp")}.tmp")
    }

    private fun journalFile(file: File) = File(File(file.parentFile, ".ComicLabBackups"), file.name + SUFFIX)

    fun hasPending(file: File): Boolean = journalFile(file).isFile

    internal fun moveWithoutReplacing(source: File, destination: File): Boolean = runCatching {
        Files.move(source.toPath(), destination.toPath())
        true
    }.getOrDefault(false)

    @Synchronized
    fun begin(file: File, temporary: File, rollback: File): Pending {
        check(!journalFile(file).exists()) { "Archive recovery is still pending" }
        check(file.extension.lowercase(java.util.Locale.ROOT) in ArchiveFormatDetector.zipArchiveExtensions)
        val parent = requireNotNull(file.parentFile).canonicalFile
        check(file.canonicalFile == File(parent, file.name))
        check(temporary.canonicalFile == File(parent, temporary.name))
        check(rollback.canonicalFile == File(parent, rollback.name))
        val journalDirectory = requireNotNull(journalFile(file).parentFile)
        check(journalDirectory.canonicalFile == File(parent, ".ComicLabBackups"))
        if (!journalDirectory.exists() && !journalDirectory.mkdirs()) {
            error("Could not create archive recovery directory")
        }
        check(journalDirectory.isDirectory)
        return Pending(file, null, temporary, rollback, digest(file), file.lastModified()).also(::write)
    }

    @Synchronized
    fun seal(pending: Pending) {
        check(digest(pending.original) == pending.originalHash) { "Archive changed during replacement" }
        write(pending.copy(replacementHash = digest(pending.temporary)))
    }

    internal fun matchesOriginal(pending: Pending, file: File): Boolean = matches(file, pending.originalHash)

    @Synchronized
    fun recoverDirectory(directory: File): Boolean {
        val backupDirectory = File(directory, ".ComicLabBackups")
        if (!backupDirectory.exists()) return true
        return runCatching {
            if (!backupDirectory.isDirectory || backupDirectory.canonicalFile != File(directory.canonicalFile, ".ComicLabBackups")) return false
            val markers = backupDirectory.listFiles() ?: return false
            var complete = true
            markers.filter { it.name.endsWith(SUFFIX) }.forEach { marker ->
                if (!recover(File(directory, marker.name.removeSuffix(SUFFIX)))) complete = false
            }
            complete
        }.getOrDefault(false)
    }

    /** 未知或冲突状态一律保留，不覆盖现有文件，不开始下一次删除。 */
    @Synchronized
    fun recover(file: File, renameTo: (File, File) -> Boolean = ::moveWithoutReplacing): Boolean {
        val journal = journalFile(file)
        if (!journal.exists()) return true
        return runCatching {
            val pending = read(file)
            if (file.exists()) {
                val current = digest(file)
                if (current != pending.originalHash && current != pending.replacementHash) return false
            } else {
                val rollbackValid = matches(pending.rollback, pending.originalHash)
                if (!rollbackValid || !renameTo(pending.rollback, file)) {
                    val legacyBackup = pending.backup ?: return false
                    if (!matches(legacyBackup, pending.originalHash)) return false
                    val restore = pending.restore
                    try {
                        FileOutputStream(restore).use { output ->
                            legacyBackup.inputStream().use { it.copyTo(output) }
                            output.fd.sync()
                        }
                        if (!matches(restore, pending.originalHash) ||
                            !restore.setLastModified(pending.modifiedAt) || file.exists() ||
                            !renameTo(restore, file)) return false
                    } finally {
                        restore.delete()
                    }
                }
            }
            finish(pending)
        }.getOrDefault(false)
    }

    private fun finish(pending: Pending): Boolean {
        // 回滚文件若已被改动，留给人工核对，不将它作为普通临时文件删除。
        if (pending.rollback.exists() && !matches(pending.rollback, pending.originalHash)) return false
        if (pending.temporary.exists() && !pending.temporary.delete()) return false
        if (pending.rollback.exists() && !pending.rollback.delete()) return false
        if (pending.restore.exists() && !pending.restore.delete()) return false
        val part = File(pending.journal.path + ".part")
        check(part.canonicalFile == File(requireNotNull(pending.journal.parentFile).canonicalFile, part.name))
        if (part.exists() && !part.delete()) return false
        if (!pending.journal.delete()) return false
        // 仅删除空的恢复目录；旧版完整备份或其他文件存在时一律保留。
        pending.journal.parentFile?.delete()
        return true
    }

    private fun write(pending: Pending) {
        check(pending.journal.canonicalFile == File(requireNotNull(pending.journal.parentFile).canonicalFile, pending.journal.name))
        val properties = Properties().apply {
            setProperty("version", VERSION)
            setProperty("original", pending.original.name)
            pending.backup?.let { setProperty("backup", it.name) }
            setProperty("temporary", pending.temporary.name)
            setProperty("rollback", pending.rollback.name)
            setProperty("originalHash", pending.originalHash)
            setProperty("modifiedAt", pending.modifiedAt.toString())
            setProperty("replacementHash", pending.replacementHash)
        }
        val part = File(pending.journal.path + ".part")
        check(part.canonicalFile == File(requireNotNull(pending.journal.parentFile).canonicalFile, part.name))
        FileOutputStream(part).use { output ->
            properties.store(output, "ComicLab archive replacement")
            output.fd.sync()
        }
        try {
            Files.move(part.toPath(), pending.journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), pending.journal.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun read(file: File): Pending {
        check(file.extension.lowercase(java.util.Locale.ROOT) in ArchiveFormatDetector.zipArchiveExtensions)
        val marker = journalFile(file)
        val parent = requireNotNull(file.parentFile).canonicalFile
        val backupDirectory = requireNotNull(marker.parentFile)
        check(backupDirectory.canonicalFile == File(parent, ".ComicLabBackups"))
        check(marker.canonicalFile == File(backupDirectory.canonicalFile, marker.name))
        check(file.canonicalFile == File(parent, file.name))
        check(marker.length() <= 16 * 1024)
        val properties = Properties().apply { marker.inputStream().use(::load) }
        val version = requireNotNull(properties.getProperty("version"))
        check(version == LEGACY_VERSION || version == VERSION)
        check(properties.getProperty("original") == file.name)
        fun child(key: String, directory: File, prefix: String, suffix: String): File {
            val name = requireNotNull(properties.getProperty(key))
            check(name == File(name).name && name.startsWith(prefix) && name.endsWith(suffix))
            return File(directory, name).also { check(it.canonicalFile == File(directory.canonicalFile, name)) }
        }
        val hash = requireNotNull(properties.getProperty("originalHash"))
        val replacement = properties.getProperty("replacementHash", "")
        check(hash.matches(Regex("[0-9a-f]{64}")))
        check(replacement.isEmpty() || replacement.matches(Regex("[0-9a-f]{64}")))
        val backup = if (version == LEGACY_VERSION) {
            child("backup", backupDirectory, "${file.nameWithoutExtension}.delete_backup_", ".${file.extension}")
        } else {
            null
        }
        val temporary = child("temporary", parent, "${file.name}.delete_", ".tmp")
        val rollback = child("rollback", parent, "${file.name}.delete_", ".bak")
        return Pending(file, backup, temporary, rollback, hash, properties.getProperty("modifiedAt").toLong(), replacement).also {
            check(it.restore.canonicalFile == File(parent, it.restore.name))
        }
    }

    private fun matches(file: File, hash: String): Boolean =
        file.isFile && runCatching { digest(file) == hash }.getOrDefault(false)

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
