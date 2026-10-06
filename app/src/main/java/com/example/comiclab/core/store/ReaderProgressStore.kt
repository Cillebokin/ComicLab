package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences
import com.example.comiclab.ComicLabMangaProgress
import java.io.File

object ReaderProgressStore {

    internal class PreparedWrite(private val editor: SharedPreferences.Editor) {
        fun apply() {
            editor.apply()
        }

        fun commit(): Boolean = editor.commit()
    }

    internal fun prepareSave(
        context: Context,
        file: File,
        position: Int,
        offset: Int
    ): PreparedWrite {
        return prepareSave(preferences(context), file, position, offset)
    }

    internal fun prepareSave(
        preferences: SharedPreferences,
        file: File,
        position: Int,
        offset: Int
    ): PreparedWrite {
        val editor = preferences.edit()
        val archiveKey = readerArchiveKey(file)
        editor
            .putInt(positionKey(archiveKey), position)
            .putInt(offsetKey(archiveKey), offset)
        return PreparedWrite(editor)
    }

    fun readPositionAndOffset(context: Context, file: File): Pair<Int, Int> {
        val prefs = preferences(context)
        val archiveKey = readerArchiveKey(file)
        return prefs.getInt(positionKey(archiveKey), 0) to prefs.getInt(offsetKey(archiveKey), 0)
    }

    fun savedReadingProgress(context: Context, file: File): ComicLabMangaProgress? {
        val prefs = preferences(context)
        val archiveKey = readerArchiveKey(file)
        val positionKey = positionKey(archiveKey)
        val offsetKey = offsetKey(archiveKey)
        if (!prefs.contains(positionKey) || !prefs.contains(offsetKey)) {
            return null
        }

        return ComicLabMangaProgress(
            path = file.absolutePath,
            position = prefs.getInt(positionKey, 0).coerceAtLeast(0),
            offset = prefs.getInt(offsetKey, 0)
        )
    }

    fun restoreReadingProgress(context: Context, file: File, progress: ComicLabMangaProgress) {
        prepareSave(
            context = context,
            file = file,
            position = progress.position.coerceAtLeast(0),
            offset = progress.offset
        ).apply()
    }

    fun hasSavedReadingProgress(context: Context, file: File): Boolean {
        val (position, offset) = readPositionAndOffset(context, file)
        return position > 0 || offset != 0
    }

    fun savedReadingPageCount(context: Context, file: File, totalCount: Int): Int {
        if (totalCount <= 0) {
            return 0
        }

        val (savedPosition, offset) = readPositionAndOffset(context, file)
        val position = savedPosition.coerceIn(0, totalCount - 1)
        if (position <= 0 && offset == 0) {
            return 0
        }
        return (position + 1).coerceIn(0, totalCount)
    }

    fun clearSavedReadingProgress(context: Context, file: File) {
        val archiveKey = readerArchiveKey(file)
        preferences(context).edit()
            .remove(positionKey(archiveKey))
            .remove(offsetKey(archiveKey))
            .apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readerArchiveKey(file: File): String =
        "reader:${file.absolutePath}:${file.lastModified()}:${file.length()}"

    private fun positionKey(archiveKey: String): String = "$archiveKey:position"

    private fun offsetKey(archiveKey: String): String = "$archiveKey:offset"

    private const val PREFS_NAME = "reader_prefs"
}
