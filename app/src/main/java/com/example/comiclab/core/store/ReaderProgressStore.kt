package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences
import com.example.comiclab.BookcaseFolderPaths
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

    /**
     * 剪切或重命名目录后迁移目录下文件的阅读进度（决策 D2）。
     * 进度键包含 mtime 与长度，迁移时按新路径下的文件重新计算键。
     * 新文件不存在或长度不一致时保留旧键，不删除数据。
     */
    fun moveDirectory(context: Context, oldDirectory: File, newDirectory: File) {
        moveProgress(context, oldDirectory.absolutePath, newDirectory.absolutePath)
    }

    /**
     * 重命名文件后迁移阅读进度。与目录迁移使用同一套匹配规则。
     */
    fun moveFile(context: Context, oldFile: File, newFile: File) {
        moveProgress(context, oldFile.absolutePath, newFile.absolutePath)
    }

    private fun moveProgress(context: Context, oldPath: String, newPath: String) {
        val prefs = preferences(context)
        val archiveKeys = prefs.all.keys.mapNotNull(::archiveKeyOf).distinct()
        val editor = prefs.edit()
        var changed = false
        archiveKeys.forEach { archiveKey ->
            val source = parseArchiveKey(archiveKey) ?: return@forEach
            val movedPath = BookcaseFolderPaths.remapPath(source.path, oldPath, newPath)
            if (movedPath == source.path) return@forEach

            val target = File(movedPath)
            if (!target.isFile || target.length() != source.length) return@forEach

            val position = prefs.all[positionKey(archiveKey)] as? Int
            val offset = prefs.all[offsetKey(archiveKey)] as? Int
            if (position == null && offset == null) return@forEach

            val targetKey = readerArchiveKey(target)
            val existingPosition = prefs.all[positionKey(targetKey)] as? Int ?: -1
            val existingOffset = prefs.all[offsetKey(targetKey)] as? Int ?: 0
            // 目标路径已有进度时保留较新者（决策 D3）。进度不含时间戳，
            // 以阅读位置更靠后者作为较新依据。
            val keepExisting = existingPosition >= 0 &&
                (existingPosition > (position ?: 0) ||
                    (existingPosition == (position ?: 0) && existingOffset >= (offset ?: 0)))

            editor.remove(positionKey(archiveKey)).remove(offsetKey(archiveKey))
            if (!keepExisting) {
                position?.let { editor.putInt(positionKey(targetKey), it) }
                offset?.let { editor.putInt(offsetKey(targetKey), it) }
            }
            changed = true
        }
        if (changed) {
            editor.apply()
        }
    }

    private data class ArchiveKeyParts(val path: String, val length: Long)

    private fun archiveKeyOf(storageKey: String): String? {
        val suffix = when {
            storageKey.endsWith(":position") -> ":position"
            storageKey.endsWith(":offset") -> ":offset"
            else -> return null
        }
        return storageKey.removeSuffix(suffix).takeIf { it.startsWith(ARCHIVE_KEY_PREFIX) }
    }

    private fun parseArchiveKey(archiveKey: String): ArchiveKeyParts? {
        val body = archiveKey.removePrefix(ARCHIVE_KEY_PREFIX)
        val length = body.substringAfterLast(':').toLongOrNull() ?: return null
        val withoutLength = body.substringBeforeLast(':')
        if (withoutLength.substringAfterLast(':').toLongOrNull() == null) return null
        val path = withoutLength.substringBeforeLast(':')
        if (path.isBlank()) return null
        return ArchiveKeyParts(path = path, length = length)
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readerArchiveKey(file: File): String =
        "$ARCHIVE_KEY_PREFIX${file.absolutePath}:${file.lastModified()}:${file.length()}"

    private fun positionKey(archiveKey: String): String = "$archiveKey:position"

    private fun offsetKey(archiveKey: String): String = "$archiveKey:offset"

    private const val ARCHIVE_KEY_PREFIX = "reader:"
    private const val PREFS_NAME = "reader_prefs"
}
