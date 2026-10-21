package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences
import com.example.comiclab.core.settings.ComicLabMangaProgress
import java.io.File
import java.util.WeakHashMap

object ReaderProgressStore {

    internal fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences(context).registerOnSharedPreferenceChangeListener(listener)
    }

    internal fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private val writeLock = Any()
    private val pendingWrites = WeakHashMap<PreparedWrite, Unit>()

    internal class PreparedWrite(
        private val preferences: SharedPreferences,
        internal var archiveKey: String,
        internal val position: Int,
        internal val offset: Int
    ) {
        private var mergeWithExisting = false

        fun apply() {
            finish(commit = false)
        }

        fun commit(): Boolean = finish(commit = true)

        internal fun redirectTo(archiveKey: String) {
            this.archiveKey = archiveKey
            mergeWithExisting = true
        }

        internal fun mergeWithCurrent() {
            mergeWithExisting = true
        }

        private fun finish(commit: Boolean): Boolean = synchronized(writeLock) {
            val savedPositionKey = positionKey(archiveKey)
            val savedOffsetKey = offsetKey(archiveKey)
            var existingPosition = 0
            var existingOffset = 0
            val shouldWrite = if (mergeWithExisting) {
                val hasExisting = preferences.contains(savedPositionKey) || preferences.contains(savedOffsetKey)
                existingPosition = preferences.getInt(savedPositionKey, 0)
                existingOffset = preferences.getInt(savedOffsetKey, 0)
                !hasExisting || isFurtherProgress(position, offset, existingPosition, existingOffset)
            } else {
                true
            }
            pendingWrites.remove(this)
            if (!shouldWrite) {
                return@synchronized if (commit) {
                    preferences.edit()
                        .putInt(savedPositionKey, existingPosition)
                        .putInt(savedOffsetKey, existingOffset)
                        .commit()
                } else {
                    true
                }
            }

            val editor = preferences.edit()
                .putInt(savedPositionKey, position)
                .putInt(savedOffsetKey, offset)
            if (commit) editor.commit() else {
                editor.apply()
                true
            }
        }
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
        return synchronized(writeLock) {
            PreparedWrite(
                preferences = preferences,
                archiveKey = readerArchiveKey(file),
                position = position,
                offset = offset
            ).also { pendingWrites[it] = Unit }
        }
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
        synchronized(writeLock) {
            val prefs = preferences(context)
            // Read the SharedPreferences map once. getAll() copies the backing map,
            // so calling it for every stored key makes large libraries quadratic.
            val snapshot = prefs.all
            val migrations = linkedMapOf<String, ProgressMigration>()

            snapshot.keys.mapNotNull(::archiveKeyOf).distinct().forEach { archiveKey ->
                val source = parseArchiveKey(archiveKey) ?: return@forEach
                val movedPath = BookcaseFolderPaths.remapPath(source.path, oldPath, newPath)
                if (movedPath == source.path) return@forEach

                val target = File(movedPath)
                if (!target.isFile || target.length() != source.length) return@forEach

                val position = snapshot[positionKey(archiveKey)] as? Int
                val offset = snapshot[offsetKey(archiveKey)] as? Int
                if (position == null && offset == null) return@forEach

                val targetKey = readerArchiveKey(target)
                val migration = migrations.getOrPut(targetKey) {
                    ProgressMigration(targetKey)
                }
                migration.sourceArchiveKeys += archiveKey
                migration.candidates += ProgressValue(position ?: 0, offset ?: 0)
            }

            // A save can be queued on the reader executor before a browser cut. If
            // the cut wins the race, redirect that still-pending write to the new key.
            pendingWrites.keys.toList().forEach { write ->
                val source = parseArchiveKey(write.archiveKey) ?: return@forEach
                val movedPath = BookcaseFolderPaths.remapPath(source.path, oldPath, newPath)
                if (movedPath == source.path) return@forEach

                val target = File(movedPath)
                if (!target.isFile || target.length() != source.length) return@forEach

                val targetKey = readerArchiveKey(target)
                val migration = migrations.getOrPut(targetKey) {
                    ProgressMigration(targetKey)
                }
                migration.candidates += write.progressValue()
                write.redirectTo(targetKey)
            }

            // A pending write already prepared for the destination must merge with
            // the migrated source instead of overwriting it after the cut.
            pendingWrites.keys.toList().forEach { write ->
                if (write.archiveKey in migrations) {
                    write.mergeWithCurrent()
                }
            }

            if (migrations.isEmpty()) return

            val editor = prefs.edit()
            migrations.values.forEach { migration ->
                val positionKey = positionKey(migration.targetArchiveKey)
                val offsetKey = offsetKey(migration.targetArchiveKey)
                val hasTargetProgress =
                    snapshot.containsKey(positionKey) || snapshot.containsKey(offsetKey)
                var best = if (hasTargetProgress) {
                    ProgressValue(
                        position = snapshot[positionKey] as? Int ?: 0,
                        offset = snapshot[offsetKey] as? Int ?: 0
                    )
                } else {
                    null
                }
                migration.candidates.forEach { candidate ->
                    if (best == null || candidate.isFurtherThan(best!!)) {
                        best = candidate
                    }
                }
                best?.let { progress ->
                    editor.putInt(positionKey, progress.position)
                    editor.putInt(offsetKey, progress.offset)
                }
                migration.sourceArchiveKeys.forEach { archiveKey ->
                    editor.remove(positionKey(archiveKey))
                    editor.remove(offsetKey(archiveKey))
                }
            }
            editor.apply()
        }
    }

    private data class ProgressMigration(
        val targetArchiveKey: String,
        val sourceArchiveKeys: MutableList<String> = mutableListOf(),
        val candidates: MutableList<ProgressValue> = mutableListOf()
    )

    private data class ProgressValue(val position: Int, val offset: Int) {
        fun isFurtherThan(other: ProgressValue): Boolean =
            isFurtherProgress(position, offset, other.position, other.offset)
    }

    private fun isFurtherProgress(
        position: Int,
        offset: Int,
        otherPosition: Int,
        otherOffset: Int
    ): Boolean = position > otherPosition ||
        // Vertical reader offsets are child.top, so scrolling forward decreases them.
        (position == otherPosition && offset < otherOffset)

    private fun PreparedWrite.progressValue(): ProgressValue = ProgressValue(position, offset)

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
        PrefsGateway.open(context, PREFS_NAME)

    private fun readerArchiveKey(file: File): String =
        "$ARCHIVE_KEY_PREFIX${file.absolutePath}:${file.lastModified()}:${file.length()}"

    private fun positionKey(archiveKey: String): String = "$archiveKey:position"

    private fun offsetKey(archiveKey: String): String = "$archiveKey:offset"

    private const val ARCHIVE_KEY_PREFIX = "reader:"
    private const val PREFS_NAME = "reader_prefs"
}
