package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences
import com.example.comiclab.core.settings.ComicLabEbookProgress
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ebook.ReaderFileDetector
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

object EbookProgressStore {

    fun load(context: Context, file: File): EbookProgress? {
        val prefs = PrefsGateway.open(context, PREFS_NAME)
        val key = progressKey(file)
        if (!prefs.hasProgress(key)) return null

        return EbookProgress(
            chapterIndex = prefs.getInt(keyForChapter(key), 0),
            scrollFraction = prefs.getFloat(keyForFraction(key), 0f),
            coordinateVersion = prefs.getInt(keyForVersion(key), 1)
        ).normalized()
    }

    @Synchronized
    fun save(context: Context, file: File, progress: EbookProgress) {
        PrefsGateway.open(context, PREFS_NAME).edit()
            .putProgress(progressKey(file), file, progress.normalized())
            .apply()
    }

    @Synchronized
    fun clear(context: Context, file: File) {
        PrefsGateway.open(context, PREFS_NAME).edit().removeProgress(progressKey(file)).apply()
    }

    @Synchronized
    internal fun rememberLegacyPath(context: Context, file: File, fileSize: Long, modifiedAt: Long) {
        val prefs = PrefsGateway.open(context, PREFS_NAME)
        val currentKey = if (ReaderFileDetector.isEbook(file)) {
            progressKey(file)
        } else {
            if (file.isFile) return
            null
        }
        val key = currentKey?.takeIf { prefs.hasProgress(it) }
            ?: progressKey(fileIdentity(file, fileSize, modifiedAt))
        if (prefs.hasProgress(key) && !prefs.contains(keyForPath(key))) {
            prefs.edit().putString(keyForPath(key), file.absolutePath).apply()
        }
    }

    /** Capture identities while the source exists. Apply the returned update after a successful move. */
    internal fun prepareMove(context: Context, source: File): ((File) -> Unit)? {
        val directory = source.isDirectory
        if (!directory && !ReaderFileDetector.isEbook(source)) return null

        val prefs = PrefsGateway.open(context, PREFS_NAME)
        val snapshot = if (directory) prefs.all else emptyMap()
        if (directory && snapshot.isEmpty()) return null

        val root = if (directory) canonicalFile(source) else source
        val candidates = if (directory) progressFilesInDirectory(root, snapshot) else sequenceOf(source)
        val entries = candidates.filter(ReaderFileDetector::isEbook).mapNotNull { file ->
            val size = file.length()
            val key = progressKey(fileIdentity(file, size, file.lastModified()))
            if (directory && snapshot.readProgress(key) == null) return@mapNotNull null
            val relativePath = if (directory) file.relativeTo(root).path else ""
            MoveEntry(key, relativePath, size)
        }.toList()
        if (entries.isEmpty()) return null
        return { target -> completeMove(prefs, target, entries) }
    }

    private fun progressFilesInDirectory(root: File, snapshot: Map<String, *>): Sequence<File> {
        val hasLegacyProgress = snapshot.keys.any { storageKey ->
            if (!storageKey.startsWith(KEY_PREFIX) || !storageKey.endsWith(CHAPTER_SUFFIX)) return@any false
            val key = storageKey.removePrefix(KEY_PREFIX).removeSuffix(CHAPTER_SUFFIX)
            snapshot[keyForPath(key)] == null && snapshot[keyForFraction(key)] is Float
        }
        // An old hash without a stored path can only be matched against the original files.
        if (hasLegacyProgress) {
            return root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .filter { !Files.isSymbolicLink(it.toPath()) }
        }
        val rootPath = root.toPath()
        return snapshot.asSequence().mapNotNull { (storageKey, path) ->
            if (!isPathKey(storageKey) || path !is String) return@mapNotNull null
            canonicalFile(File(path)).takeIf { it.toPath().startsWith(rootPath) }
        }.distinct()
    }

    @Synchronized
    private fun completeMove(prefs: SharedPreferences, target: File, entries: List<MoveEntry>) {
        val snapshot = prefs.all
        val editor = prefs.edit()
        entries.forEach { entry ->
            val progress = snapshot.readProgress(entry.sourceKey) ?: return@forEach
            val file = if (entry.relativePath.isEmpty()) target else File(target, entry.relativePath)
            if (!ReaderFileDetector.isEbook(file) || file.length() != entry.size) return@forEach

            val key = progressKey(file)
            val existing = snapshot.readProgress(key)
            val winner = if (existing == null || progress.isFurtherThan(existing)) progress else existing
            editor.putProgress(key, file, winner)
            if (key != entry.sourceKey) editor.removeProgress(entry.sourceKey)
        }
        editor.apply()
    }

    private data class MoveEntry(val sourceKey: String, val relativePath: String, val size: Long)

    // Fractions from different coordinate versions cannot be ordered within the same chapter.
    private fun EbookProgress.isFurtherThan(other: EbookProgress): Boolean =
        chapterIndex > other.chapterIndex ||
            (chapterIndex == other.chapterIndex && coordinateVersion == other.coordinateVersion &&
                scrollFraction > other.scrollFraction)

    internal fun exportSavedProgress(context: Context, knownFiles: Collection<File>): List<ComicLabEbookProgress> {
        val snapshot = PrefsGateway.open(context, PREFS_NAME).all
        val filesByKey = linkedMapOf<String, File>()

        // Old records only have a hash. History and favorites can still identify those books.
        knownFiles.filter(ReaderFileDetector::isEbook).forEach { file ->
            filesByKey.putIfAbsent(progressKey(file), file)
        }
        snapshot.forEach { (storageKey, path) ->
            if (path !is String || !isPathKey(storageKey)) {
                return@forEach
            }

            val file = File(path)
            if (!ReaderFileDetector.isEbook(file)) return@forEach
            val key = progressKey(file)
            if (storageKey == keyForPath(key)) filesByKey.putIfAbsent(key, file)
        }

        return filesByKey.mapNotNull { (key, file) ->
            val progress = snapshot.readProgress(key) ?: return@mapNotNull null
            ComicLabEbookProgress(
                path = file.absolutePath,
                chapterIndex = progress.chapterIndex,
                scrollFraction = progress.scrollFraction,
                coordinateVersion = progress.coordinateVersion
            )
        }.sortedBy { it.path }
    }

    private fun Map<String, *>.readProgress(key: String): EbookProgress? {
        val chapter = this[keyForChapter(key)] as? Int ?: return null
        val fraction = (this[keyForFraction(key)] as? Float)?.takeIf { it.isFinite() } ?: return null
        return EbookProgress(chapter, fraction, this[keyForVersion(key)] as? Int ?: 1).normalized()
    }

    private fun SharedPreferences.Editor.putProgress(
        key: String,
        file: File,
        progress: EbookProgress
    ): SharedPreferences.Editor = putInt(keyForChapter(key), progress.chapterIndex)
        .putFloat(keyForFraction(key), progress.scrollFraction)
        .putInt(keyForVersion(key), progress.coordinateVersion)
        .putString(keyForPath(key), file.absolutePath)

    private fun SharedPreferences.Editor.removeProgress(key: String): SharedPreferences.Editor =
        remove(keyForChapter(key)).remove(keyForFraction(key)).remove(keyForVersion(key)).remove(keyForPath(key))

    fun fileIdentity(file: File): String = fileIdentity(file, file.length(), file.lastModified())

    private fun fileIdentity(file: File, fileSize: Long, modifiedAt: Long): String =
        "${canonicalFile(file).absolutePath}|$fileSize|$modifiedAt"

    private fun canonicalFile(file: File): File =
        runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }

    private fun progressKey(file: File): String = progressKey(fileIdentity(file))

    private fun progressKey(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun SharedPreferences.hasProgress(key: String): Boolean =
        contains(keyForChapter(key)) && contains(keyForFraction(key))

    private fun keyForChapter(key: String): String = "$KEY_PREFIX$key$CHAPTER_SUFFIX"

    private fun keyForFraction(key: String): String = "${KEY_PREFIX}${key}_fraction"

    private fun keyForVersion(key: String): String = "${KEY_PREFIX}${key}_coordinate_version"

    private fun keyForPath(key: String) = "$KEY_PREFIX$key$PATH_SUFFIX"

    private fun isPathKey(key: String): Boolean = key.startsWith(KEY_PREFIX) && key.endsWith(PATH_SUFFIX)

    private const val CHAPTER_SUFFIX = "_chapter"
    private const val PATH_SUFFIX = "_path"
    private const val PREFS_NAME = "ebook_progress_prefs"
    private const val KEY_PREFIX = "ebook_progress_"
}
