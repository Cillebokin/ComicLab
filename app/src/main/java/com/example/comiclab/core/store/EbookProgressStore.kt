package com.example.comiclab.core.store

import com.example.comiclab.ebook.EbookProgress
import android.content.Context
import java.io.File
import java.security.MessageDigest

object EbookProgressStore {

    fun load(context: Context, file: File): EbookProgress? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = progressKey(file)
        if (!prefs.contains(keyForChapter(key)) || !prefs.contains(keyForFraction(key))) {
            return null
        }

        return EbookProgress(
            chapterIndex = prefs.getInt(keyForChapter(key), 0),
            scrollFraction = prefs.getFloat(keyForFraction(key), 0f)
        ).normalized()
    }

    fun save(context: Context, file: File, progress: EbookProgress) {
        val normalized = progress.normalized()
        val key = progressKey(file)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(keyForChapter(key), normalized.chapterIndex)
            .putFloat(keyForFraction(key), normalized.scrollFraction)
            .apply()
    }

    fun clear(context: Context, file: File) {
        val key = progressKey(file)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(keyForChapter(key))
            .remove(keyForFraction(key))
            .apply()
    }

    fun fileIdentity(file: File): String {
        val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        return "$canonicalPath|${file.length()}|${file.lastModified()}"
    }

    private fun progressKey(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fileIdentity(file).toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun keyForChapter(key: String): String = "${KEY_PREFIX}${key}_chapter"

    private fun keyForFraction(key: String): String = "${KEY_PREFIX}${key}_fraction"

    private const val PREFS_NAME = "ebook_progress_prefs"
    private const val KEY_PREFIX = "ebook_progress_"
}
