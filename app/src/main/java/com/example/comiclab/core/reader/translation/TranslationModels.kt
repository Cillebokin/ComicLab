package com.example.comiclab.core.reader.translation

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import org.json.JSONObject

internal class TranslationModelPackageImportException(
    val reason: Reason,
    cause: Throwable? = null
) : IOException(cause) {
    enum class Reason { INVALID_PACKAGE, INSUFFICIENT_STORAGE, IMPORT_FAILED }
}

internal object TranslationModels {
    private const val MODEL_DIRECTORY = "translation-models"
    private const val STAGING_DIRECTORY = "translation-models.importing"
    private const val BACKUP_DIRECTORY = "translation-models.previous"
    private const val STORAGE_RESERVE_BYTES = 16L * 1024 * 1024

    private data class ModelSpec(val name: String, val size: Long, val sha256: String)

    /** 翻译入口只使用用户导入的本地模型，不再尝试联网下载。 */
    fun ensure(context: Context, cancelled: AtomicBoolean, progress: (String) -> Unit): File {
        check(TranslationSettingsStore.isAutomaticTranslationEnabled(context)) {
            "Automatic translation is disabled"
        }
        return verifyInstalled(context, cancelled, progress)
    }

    fun verifyInstalled(context: Context, cancelled: AtomicBoolean, progress: (String) -> Unit): File {
        val root = resolveModelDirectory(context)
        val specs = modelSpecs(context)
        check(root.isDirectory) { "Model package is not installed" }
        specs.forEachIndexed { index, spec ->
            checkCancelled(cancelled)
            progress("${index + 1}/${specs.size} ${spec.name}")
            check(validFile(File(root, spec.name), spec.size, spec.sha256, cancelled)) {
                "Model package is incomplete or damaged"
            }
        }
        return root
    }

    /** 仅做轻量文件存在性检查，供设置页与翻译入口显隐使用。 */
    fun hasCompletePackage(context: Context): Boolean = runCatching {
        val root = File(context.noBackupFilesDir, MODEL_DIRECTORY)
        modelSpecs(context).all { spec ->
            File(root, spec.name).let { it.isFile && it.length() == spec.size }
        }
    }.getOrDefault(false)

    fun isAvailable(context: Context): Boolean =
        TranslationSettingsStore.isAutomaticTranslationEnabled(context) && hasCompletePackage(context)

    fun importPackage(
        context: Context,
        input: InputStream,
        cancelled: AtomicBoolean,
        progress: (String) -> Unit
    ): File {
        val specs = modelSpecs(context)
        val expected = specs.associateBy(ModelSpec::name)
        require(expected.size == specs.size) { "Duplicate model names in manifest" }

        val root = File(context.noBackupFilesDir, MODEL_DIRECTORY)
        val parent = root.parentFile ?: throw IOException("Model directory has no parent")
        val backup = File(parent, BACKUP_DIRECTORY)
        recoverDirectorySwap(root, backup)

        val staging = File(parent, STAGING_DIRECTORY)
        if (staging.exists() && !staging.deleteRecursively()) {
            throw TranslationModelPackageImportException(
                TranslationModelPackageImportException.Reason.IMPORT_FAILED
            )
        }
        val requiredSpace = specs.sumOf(ModelSpec::size) + STORAGE_RESERVE_BYTES
        if (parent.usableSpace < requiredSpace) {
            throw TranslationModelPackageImportException(
                TranslationModelPackageImportException.Reason.INSUFFICIENT_STORAGE
            )
        }

        var installed = false
        try {
            if (!staging.mkdirs() && !staging.isDirectory) throw IOException("Cannot create staging directory")
            val seen = linkedSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    checkCancelled(cancelled)
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in expected) invalidPackage()
                    val spec = expected.getValue(entry.name)
                    if (!seen.add(spec.name)) invalidPackage()

                    val target = File(staging, spec.name)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    var lastPercent = -1
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            checkCancelled(cancelled)
                            val count = zip.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > spec.size) invalidPackage()
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val percent = (received * 100 / spec.size).toInt()
                            if (percent != lastPercent) {
                                progress("${seen.size}/${specs.size} ${spec.name} $percent%")
                                lastPercent = percent
                            }
                        }
                    }
                    zip.closeEntry()
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (received != spec.size || hash != spec.sha256) invalidPackage()
                }
            }
            if (seen != expected.keys) invalidPackage()
            checkCancelled(cancelled)
            installStagingDirectory(staging, root, backup)
            installed = true
            return root
        } catch (cancelledError: CancellationException) {
            throw cancelledError
        } catch (error: TranslationModelPackageImportException) {
            throw error
        } catch (error: Exception) {
            throw TranslationModelPackageImportException(
                TranslationModelPackageImportException.Reason.IMPORT_FAILED,
                error
            )
        } finally {
            if (!installed && staging.exists()) staging.deleteRecursively()
        }
    }

    private fun installStagingDirectory(staging: File, root: File, backup: File) {
        if (backup.exists() && !backup.deleteRecursively()) throw IOException("Cannot clear old model backup")
        val hadInstalledDirectory = root.exists()
        if (hadInstalledDirectory && !root.renameTo(backup)) throw IOException("Cannot stage old models")
        try {
            if (!staging.renameTo(root)) throw IOException("Cannot install model package")
        } catch (error: Exception) {
            if (hadInstalledDirectory && backup.exists() && !root.exists()) backup.renameTo(root)
            throw error
        }
        if (backup.exists()) backup.deleteRecursively()
    }

    private fun recoverDirectorySwap(root: File, backup: File) {
        if (!root.exists() && backup.exists()) {
            check(backup.renameTo(root)) { "Cannot recover the previous model package" }
        } else if (root.exists() && backup.exists()) {
            backup.deleteRecursively()
        }
    }

    private fun resolveModelDirectory(context: Context): File {
        val root = File(context.noBackupFilesDir, MODEL_DIRECTORY)
        recoverDirectorySwap(root, File(context.noBackupFilesDir, BACKUP_DIRECTORY))
        return root
    }

    private fun invalidPackage(): Nothing = throw TranslationModelPackageImportException(
        TranslationModelPackageImportException.Reason.INVALID_PACKAGE
    )

    private fun modelSpecs(context: Context): List<ModelSpec> {
        val manifest = JSONObject(
            context.assets.open("translation/models.json").bufferedReader().use { it.readText() }
        ).getJSONArray("models")
        return (0 until manifest.length()).map { index ->
            val entry = manifest.getJSONObject(index)
            val name = entry.getString("name")
            require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
            ModelSpec(name, entry.getLong("size"), entry.getString("sha256"))
        }
    }

    private fun validFile(file: File, size: Long, hash: String, cancelled: AtomicBoolean): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(cancelled)
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == hash
    }

    internal fun promoteCompletePart(part: File, target: File, size: Long, hash: String, cancelled: AtomicBoolean): Boolean {
        if (!part.exists() || part.length() < size) return false
        if (validFile(part, size, hash, cancelled)) {
            publish(part, target)
            return true
        }
        check(part.delete()) { "Cannot reset damaged model download" }
        return false
    }

    private fun publish(part: File, target: File) {
        java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
    }
}
