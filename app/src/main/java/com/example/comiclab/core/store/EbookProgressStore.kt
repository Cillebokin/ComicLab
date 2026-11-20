package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences
import com.example.comiclab.core.settings.ComicLabEbookProgress
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ebook.ReaderFileDetector
import java.io.File
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

    fun save(context: Context, file: File, progress: EbookProgress) {
        val normalized = progress.normalized()
        val key = progressKey(file)
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .putInt(keyForChapter(key), normalized.chapterIndex)
            .putFloat(keyForFraction(key), normalized.scrollFraction)
            .putInt(keyForVersion(key), normalized.coordinateVersion)
            .putString(keyForPath(key), file.absolutePath)
            .apply()
    }

    fun clear(context: Context, file: File) {
        val key = progressKey(file)
        PrefsGateway.open(context, PREFS_NAME)
            .edit()
            .remove(keyForChapter(key))
            .remove(keyForFraction(key))
            .remove(keyForVersion(key))
            .remove(keyForPath(key))
            .apply()
    }

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

    internal fun exportSavedProgress(context: Context, knownFiles: Collection<File>): List<ComicLabEbookProgress> {
        val snapshot = PrefsGateway.open(context, PREFS_NAME).all
        val filesByKey = linkedMapOf<String, File>()

        // Old records only have a hash. History and favorites can still identify those books.
        knownFiles.filter(ReaderFileDetector::isEbook).forEach { file ->
            filesByKey.putIfAbsent(progressKey(file), file)
        }
        snapshot.forEach { (storageKey, path) ->
            if (path !is String || !storageKey.startsWith(KEY_PREFIX) || !storageKey.endsWith(PATH_SUFFIX)) {
                return@forEach
            }

            val file = File(path)
            if (!ReaderFileDetector.isEbook(file)) return@forEach
            val key = progressKey(file)
            if (storageKey == keyForPath(key)) filesByKey.putIfAbsent(key, file)
        }

        return filesByKey.mapNotNull { (key, file) ->
            val chapter = snapshot[keyForChapter(key)] as? Int ?: return@mapNotNull null
            val fraction = (snapshot[keyForFraction(key)] as? Float)?.takeIf { it.isFinite() }
                ?: return@mapNotNull null
            val version = snapshot[keyForVersion(key)] as? Int ?: 1
            val progress = EbookProgress(chapter, fraction, version).normalized()
            ComicLabEbookProgress(
                path = file.absolutePath,
                chapterIndex = progress.chapterIndex,
                scrollFraction = progress.scrollFraction,
                coordinateVersion = progress.coordinateVersion
            )
        }.sortedBy { it.path }
    }

    fun fileIdentity(file: File): String = fileIdentity(file, file.length(), file.lastModified())

    private fun fileIdentity(file: File, fileSize: Long, modifiedAt: Long): String {
        val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return "$canonicalPath|$fileSize|$modifiedAt"
    }

    private fun progressKey(file: File): String = progressKey(fileIdentity(file))

    private fun progressKey(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun SharedPreferences.hasProgress(key: String): Boolean =
        contains(keyForChapter(key)) && contains(keyForFraction(key))

    private fun keyForChapter(key: String): String = "${KEY_PREFIX}${key}_chapter"

    private fun keyForFraction(key: String): String = "${KEY_PREFIX}${key}_fraction"

    private fun keyForVersion(key: String): String = "${KEY_PREFIX}${key}_coordinate_version"

    private fun keyForPath(key: String) = "$KEY_PREFIX$key$PATH_SUFFIX"

    private const val PATH_SUFFIX = "_path"
    private const val PREFS_NAME = "ebook_progress_prefs"
    private const val KEY_PREFIX = "ebook_progress_"
}
