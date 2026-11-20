package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences
import com.example.comiclab.core.archive.ArchiveFormatDetector
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
    // Clear can invalidate requests while the writer is blocked in commit().
    private val stateLock = Any()
    private val pendingWrites = WeakHashMap<PreparedWrite, Unit>()
    private var activeWrite: PreparedWrite? = null
    private var nextEnqueueOrder = 0L

    // Readers use this snapshot without acquiring the lock held by commit().
    @Volatile
    private var pendingResumeProgress = emptyMap<String, PendingResumeProgress>()

    internal class PreparedWrite(
        private val preferences: SharedPreferences,
        internal var archiveKey: String,
        internal val position: Int,
        internal val offset: Int,
        internal val coveredPosition: Int?
    ) {
        internal var enqueueOrder = 0L
        internal var mergeWithExisting = false
            private set

        fun apply(): Boolean = finish(commit = false)

        fun commit(): Boolean = finish(commit = true)

        internal fun redirectTo(archiveKey: String) {
            this.archiveKey = archiveKey
            mergeWithExisting = true
        }

        internal fun mergeWithCurrent() {
            mergeWithExisting = true
        }

        private fun finish(commit: Boolean): Boolean = synchronized(writeLock) writer@{
            synchronized(stateLock) {
                if (this !in pendingWrites) return@writer false
                activeWrite = this
            }
            var saved = false
            try {
                saved = persist(commit)
            } finally {
                synchronized(stateLock) {
                    val cancelled = pendingWrites.remove(this) == null
                    try {
                        if (cancelled) {
                            // Hide and remove values applied by a commit that was already running.
                            saved = false
                            removeStoredProgress(preferences, archiveKey)
                        } else if (saved && enqueueOrder > 0 && !mergeWithExisting) {
                            pendingWrites.keys.removeAll {
                                it.archiveKey == archiveKey && it.enqueueOrder in 1 until enqueueOrder
                            }
                        }
                    } finally {
                        activeWrite = null
                        if (enqueueOrder > 0 || cancelled) publishPendingResumeProgress()
                    }
                }
            }
            saved
        }

        private fun persist(commit: Boolean): Boolean {
            val progress = progressValue()
            val existing = if (mergeWithExisting) preferences.readProgress(archiveKey) else null
            val keepExisting = existing?.takeUnless { progress.isFurtherThan(it) }
            if (keepExisting != null && !commit) return true

            val editor = preferences.edit().putProgress(archiveKey, keepExisting ?: progress)
            return if (commit) editor.commit() else {
                editor.apply()
                true
            }
        }
    }

    internal fun prepareSave(
        context: Context,
        file: File,
        position: Int,
        offset: Int,
        coveredPosition: Int? = null
    ): PreparedWrite {
        return prepareSave(preferences(context), file, position, offset, coveredPosition)
    }

    internal fun prepareSave(
        preferences: SharedPreferences,
        file: File,
        position: Int,
        offset: Int,
        coveredPosition: Int? = null
    ): PreparedWrite = synchronized(writeLock) {
        val archiveKey = readerArchiveKey(file)
        synchronized(stateLock) { registerWrite(preferences, archiveKey, position, offset, coveredPosition) }
    }

    internal fun enqueueSave(
        context: Context,
        archiveKey: String,
        position: Int,
        offset: Int,
        coveredPosition: Int? = null,
        enqueue: (PreparedWrite) -> Unit
    ) {
        val prefs = preferences(context)
        synchronized(writeLock) {
            val write = synchronized(stateLock) { registerWrite(prefs, archiveKey, position, offset, coveredPosition) }
            try {
                enqueue(write)
            } catch (exception: RuntimeException) {
                // A concurrent move must never observe a request the executor rejected.
                synchronized(stateLock) { pendingWrites.remove(write) }
                throw exception
            }
            synchronized(stateLock) {
                if (write in pendingWrites) {
                    write.enqueueOrder = ++nextEnqueueOrder
                    publishPendingResumeProgress()
                }
            }
        }
    }

    private fun publishPendingResumeProgress(clearedKey: String? = null) {
        val snapshot = mutableMapOf<String, PendingResumeProgress>()
        pendingWrites.keys.filter { it.enqueueOrder > 0 }.sortedBy { it.enqueueOrder }.forEach { write ->
            val previous = snapshot[write.archiveKey]?.value
            val value = write.progressValue()
            if (!write.mergeWithExisting || previous == null || value.isFurtherThan(previous)) {
                snapshot[write.archiveKey] = PendingResumeProgress(value, write.mergeWithExisting)
            }
        }
        activeWrite?.takeIf { it !in pendingWrites }?.let {
            snapshot.putIfAbsent(it.archiveKey, PendingResumeProgress(null, false))
        }
        if (clearedKey != null) snapshot[clearedKey] = PendingResumeProgress(null, false)
        pendingResumeProgress = snapshot
    }

    private fun registerWrite(
        preferences: SharedPreferences,
        archiveKey: String,
        position: Int,
        offset: Int,
        coveredPosition: Int?
    ): PreparedWrite = PreparedWrite(
        preferences, archiveKey, position, offset, coveredPosition?.takeIf { it >= position && it >= 0 }
    )
        .also { pendingWrites[it] = Unit }

    fun readPositionAndOffset(context: Context, file: File): Pair<Int, Int> =
        readCurrentProgress(context, file)?.let { it.position to it.offset } ?: (0 to 0)

    internal fun savedCoveredPosition(context: Context, file: File, position: Int): Int? =
        readCurrentProgress(context, file)?.takeIf { it.position == position }?.coveredPosition

    private fun readCurrentProgress(context: Context, file: File): ProgressValue? {
        val archiveKey = readerArchiveKey(file)
        val pending = pendingResumeProgress[archiveKey]
        if (pending != null && !pending.mergeWithSaved) return pending.value

        val saved = preferences(context).readProgress(archiveKey)
        val pendingValue = pending?.value ?: return saved
        return if (saved == null || pendingValue.isFurtherThan(saved)) pendingValue else saved
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
            offset = prefs.getInt(offsetKey, 0),
            coveredPosition = prefs.getInt(coveredPositionKey(archiveKey), -1).takeIf { it >= 0 }
        )
    }

    internal fun exportSavedProgress(context: Context): List<ComicLabMangaProgress> {
        val snapshot = preferences(context).all
        return snapshot.keys.asSequence()
            .mapNotNull(::archiveKeyOf)
            .distinct()
            .mapNotNull { key ->
                val position = snapshot[positionKey(key)] as? Int ?: return@mapNotNull null
                val offset = snapshot[offsetKey(key)] as? Int ?: return@mapNotNull null
                val archive = parseArchiveKey(key) ?: return@mapNotNull null
                val file = File(archive.path)
                if (!ArchiveFormatDetector.isSupportedArchive(file) || readerArchiveKey(file) != key) {
                    return@mapNotNull null
                }

                ComicLabMangaProgress(
                    path = file.absolutePath,
                    position = position.coerceAtLeast(0),
                    offset = offset,
                    coveredPosition = (snapshot[coveredPositionKey(key)] as? Int)?.takeIf { it >= 0 }
                )
            }
            .sortedBy { it.path }
            .toList()
    }

    fun restoreReadingProgress(context: Context, file: File, progress: ComicLabMangaProgress) {
        prepareSave(
            context = context,
            file = file,
            position = progress.position.coerceAtLeast(0),
            offset = progress.offset,
            coveredPosition = progress.coveredPosition
        ).apply()
    }

    internal fun restoreReadingProgressIfMissing(
        context: Context,
        file: File,
        progress: ComicLabMangaProgress
    ): Boolean = synchronized(writeLock) {
        val prefs = preferences(context)
        val key = readerArchiveKey(file)
        val write = synchronized(stateLock) {
            if (pendingResumeProgress[key]?.value != null ||
                (prefs.contains(positionKey(key)) && prefs.contains(offsetKey(key)))
            ) {
                null
            } else {
                registerWrite(prefs, key, progress.position.coerceAtLeast(0), progress.offset, progress.coveredPosition)
            }
        }
        write?.apply() == true
    }

    fun hasSavedReadingProgress(context: Context, file: File): Boolean {
        return readCurrentProgress(context, file)?.hasReadPages() == true
    }

    fun savedReadingPageCount(context: Context, file: File, totalCount: Int): Int {
        if (totalCount <= 0) {
            return 0
        }

        val progress = readCurrentProgress(context, file) ?: return 0
        if (!progress.hasReadPages()) return 0
        return (progress.coveredPosition ?: progress.position).coerceIn(0, totalCount - 1) + 1
    }

    fun clearSavedReadingProgress(context: Context, file: File) {
        val archiveKey = readerArchiveKey(file)
        val prefs = preferences(context)
        synchronized(stateLock) {
            pendingWrites.keys.removeAll { it.archiveKey == archiveKey }
            publishPendingResumeProgress(clearedKey = archiveKey)
            try {
                removeStoredProgress(prefs, archiveKey)
            } finally {
                publishPendingResumeProgress()
            }
        }
    }

    private fun removeStoredProgress(prefs: SharedPreferences, archiveKey: String) {
        prefs.edit().removeProgress(archiveKey).apply()
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
            synchronized(stateLock) { moveProgressLocked(context, oldPath, newPath) }
        }
    }

    private fun moveProgressLocked(context: Context, oldPath: String, newPath: String) {
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

            val progress = snapshot.readProgress(archiveKey) ?: return@forEach

            val targetKey = readerArchiveKey(target)
            val migration = migrations.getOrPut(targetKey) {
                ProgressMigration(targetKey)
            }
            migration.sourceArchiveKeys += archiveKey
            migration.candidates += progress
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
            var best = snapshot.readProgress(migration.targetArchiveKey)
            migration.candidates.forEach { candidate ->
                if (best == null || candidate.isFurtherThan(best!!)) {
                    best = candidate
                }
            }
            best?.let { progress ->
                editor.putProgress(migration.targetArchiveKey, progress)
            }
            migration.sourceArchiveKeys.forEach { archiveKey ->
                editor.removeProgress(archiveKey)
            }
        }
        // A preference listener may read progress as soon as apply updates memory.
        publishPendingResumeProgress()
        editor.apply()
    }

    private data class ProgressMigration(
        val targetArchiveKey: String,
        val sourceArchiveKeys: MutableList<String> = mutableListOf(),
        val candidates: MutableList<ProgressValue> = mutableListOf()
    )

    private data class ProgressValue(val position: Int, val offset: Int, val coveredPosition: Int?) {
        fun hasReadPages(): Boolean = coveredPosition != null || position > 0 || offset != 0

        fun isFurtherThan(other: ProgressValue): Boolean = position > other.position ||
            // Vertical reader offsets are child.top, so scrolling forward decreases them.
            (position == other.position && (offset < other.offset ||
                (offset == other.offset && (coveredPosition ?: -1) > (other.coveredPosition ?: -1))))
    }

    private data class PendingResumeProgress(val value: ProgressValue?, val mergeWithSaved: Boolean)

    private fun PreparedWrite.progressValue(): ProgressValue = ProgressValue(position, offset, coveredPosition)

    private fun SharedPreferences.readProgress(key: String): ProgressValue? {
        if (!contains(positionKey(key)) && !contains(offsetKey(key))) return null
        return ProgressValue(
            getInt(positionKey(key), 0),
            getInt(offsetKey(key), 0),
            getInt(coveredPositionKey(key), -1).takeIf { it >= 0 }
        )
    }

    private fun Map<String, *>.readProgress(key: String): ProgressValue? {
        val position = this[positionKey(key)] as? Int
        val offset = this[offsetKey(key)] as? Int
        if (position == null && offset == null) return null
        return ProgressValue(position ?: 0, offset ?: 0, this[coveredPositionKey(key)] as? Int)
    }

    private fun SharedPreferences.Editor.putProgress(key: String, progress: ProgressValue): SharedPreferences.Editor {
        putInt(positionKey(key), progress.position)
        putInt(offsetKey(key), progress.offset)
        val coveredPosition = progress.coveredPosition
        if (coveredPosition == null) remove(coveredPositionKey(key))
        else putInt(coveredPositionKey(key), coveredPosition)
        return this
    }

    private fun SharedPreferences.Editor.removeProgress(key: String): SharedPreferences.Editor =
        remove(positionKey(key)).remove(offsetKey(key)).remove(coveredPositionKey(key))

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

    internal fun readerArchiveKey(file: File): String =
        "$ARCHIVE_KEY_PREFIX${file.absolutePath}:${file.lastModified()}:${file.length()}"

    private fun positionKey(archiveKey: String): String = "$archiveKey:position"

    private fun offsetKey(archiveKey: String): String = "$archiveKey:offset"

    private fun coveredPositionKey(archiveKey: String): String = "$archiveKey:coveredPosition"

    private const val ARCHIVE_KEY_PREFIX = "reader:"
    private const val PREFS_NAME = "reader_prefs"
}
