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

    // Serializes persistence and progress migration. A slow commit must not block registration.
    private val writeLock = Any()
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
        internal val coveredPosition: Int?,
        internal val totalCount: Int? = null
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
            val resolved = (keepExisting ?: progress).let { winner ->
                if (winner.totalCount == null && existing?.totalCount != null) {
                    winner.copy(totalCount = existing.totalCount)
                } else {
                    winner
                }
            }
            if (keepExisting != null && resolved.totalCount == existing.totalCount && !commit) return true

            val editor = preferences.edit().putProgress(archiveKey, resolved)
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
        coveredPosition: Int? = null,
        totalCount: Int? = null
    ): PreparedWrite {
        return prepareSave(preferences(context), file, position, offset, coveredPosition, totalCount)
    }

    internal fun prepareSave(
        preferences: SharedPreferences,
        file: File,
        position: Int,
        offset: Int,
        coveredPosition: Int? = null,
        totalCount: Int? = null
    ): PreparedWrite {
        val archiveKey = readerArchiveKey(file)
        return synchronized(stateLock) {
            registerWrite(preferences, archiveKey, position, offset, coveredPosition, totalCount)
        }
    }

    // enqueue only hands the write off to the background executor.
    internal fun enqueueSave(
        context: Context,
        archiveKey: String,
        position: Int,
        offset: Int,
        coveredPosition: Int? = null,
        totalCount: Int? = null,
        enqueue: (PreparedWrite) -> Unit
    ) {
        val prefs = preferences(context)
        synchronized(stateLock) {
            val write = registerWrite(prefs, archiveKey, position, offset, coveredPosition, totalCount)
            try {
                enqueue(write)
            } catch (exception: RuntimeException) {
                // A concurrent move must never observe a request the executor rejected.
                pendingWrites.remove(write)
                throw exception
            }
            if (write in pendingWrites) {
                write.enqueueOrder = ++nextEnqueueOrder
                publishPendingResumeProgress()
            }
        }
    }

    private fun publishPendingResumeProgress(clearedKey: String? = null) {
        val snapshot = mutableMapOf<String, PendingResumeProgress>()
        pendingWrites.keys.filter { it.enqueueOrder > 0 }.sortedBy { it.enqueueOrder }.forEach { write ->
            val previous = snapshot[write.archiveKey]?.value
            val value = write.progressValue()
            if (!write.mergeWithExisting || previous == null || value.isFurtherThan(previous)) {
                val resolved = if (value.totalCount == null && previous?.totalCount != null) {
                    value.copy(totalCount = previous.totalCount)
                } else {
                    value
                }
                snapshot[write.archiveKey] = PendingResumeProgress(resolved, write.mergeWithExisting)
            } else if (previous.totalCount == null && value.totalCount != null) {
                snapshot[write.archiveKey] = PendingResumeProgress(
                    previous.copy(totalCount = value.totalCount),
                    write.mergeWithExisting
                )
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
        coveredPosition: Int?,
        totalCount: Int? = null
    ): PreparedWrite = PreparedWrite(
        preferences,
        archiveKey,
        position,
        offset,
        coveredPosition?.takeIf { it >= position && it >= 0 },
        totalCount?.takeIf { it > 0 }
    )
        .also { pendingWrites[it] = Unit }

    fun readPositionAndOffset(context: Context, file: File): Pair<Int, Int> =
        readCurrentProgress(context, file)?.let { it.position to it.offset } ?: (0 to 0)

    internal fun savedCoveredPosition(context: Context, file: File, position: Int): Int? =
        readCurrentProgress(context, file)?.takeIf { it.position == position }?.coveredPosition

    private fun readCurrentProgress(context: Context, file: File): ProgressValue? {
        val archiveKey = readerArchiveKey(file)
        return readCurrentProgress(archiveKey) { preferences(context).readProgress(archiveKey) }
    }

    private inline fun readCurrentProgress(
        archiveKey: String,
        pendingSnapshot: Map<String, PendingResumeProgress> = pendingResumeProgress,
        readSaved: () -> ProgressValue?
    ): ProgressValue? {
        val pending = pendingSnapshot[archiveKey]
        if (pending != null && !pending.mergeWithSaved) {
            val pendingValue = pending.value ?: return null
            if (pendingValue.totalCount != null) return pendingValue
            val savedTotalCount = readSaved()?.totalCount
            return if (savedTotalCount != null) pendingValue.copy(totalCount = savedTotalCount) else pendingValue
        }

        val saved = readSaved()
        val pendingValue = pending?.value ?: return saved
        val winner = if (saved == null || pendingValue.isFurtherThan(saved)) pendingValue else saved
        val totalCount = winner.totalCount ?: saved?.totalCount ?: pendingValue.totalCount
        return if (winner.totalCount == totalCount) winner else winner.copy(totalCount = totalCount)
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
            coveredPosition = prefs.getInt(coveredPositionKey(archiveKey), -1).takeIf { it >= 0 },
            totalCount = prefs.getInt(totalCountKey(archiveKey), 0).takeIf { it > 0 }
        )
    }

    internal fun exportSavedProgress(context: Context): List<ComicLabMangaProgress> {
        val prefs = preferences(context)
        val (snapshot, pendingSnapshot) = synchronized(stateLock) {
            prefs.all to pendingResumeProgress
        }
        val archiveKeys = linkedSetOf<String>()
        snapshot.keys.mapNotNullTo(archiveKeys, ::archiveKeyOf)
        archiveKeys.addAll(pendingSnapshot.keys)
        return archiveKeys.asSequence()
            .mapNotNull { key ->
                val resolved = readCurrentProgress(key, pendingSnapshot) {
                    val position = snapshot[positionKey(key)] as? Int ?: return@readCurrentProgress null
                    val offset = snapshot[offsetKey(key)] as? Int ?: return@readCurrentProgress null
                    ProgressValue(
                        position = position,
                        offset = offset,
                        coveredPosition = (snapshot[coveredPositionKey(key)] as? Int)?.takeIf { it >= 0 },
                        totalCount = (snapshot[totalCountKey(key)] as? Int)?.takeIf { it > 0 }
                    )
                } ?: return@mapNotNull null

                val archive = parseArchiveKey(key) ?: return@mapNotNull null
                val file = File(archive.path)
                if (!ArchiveFormatDetector.isSupportedArchive(file) || readerArchiveKey(file) != key) {
                    return@mapNotNull null
                }

                ComicLabMangaProgress(
                    path = file.absolutePath,
                    position = resolved.position.coerceAtLeast(0),
                    offset = resolved.offset,
                    coveredPosition = resolved.coveredPosition,
                    totalCount = resolved.totalCount
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
            coveredPosition = progress.coveredPosition,
            totalCount = progress.totalCount
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
                registerWrite(
                    prefs,
                    key,
                    progress.position.coerceAtLeast(0),
                    progress.offset,
                    progress.coveredPosition,
                    progress.totalCount
                )
            }
        }
        write?.apply() == true
    }

    fun hasSavedReadingProgress(context: Context, file: File): Boolean {
        return readCurrentProgress(context, file)?.hasReadPages() == true
    }

    fun savedTotalPageCount(context: Context, file: File): Int? {
        return readCurrentProgress(context, file)?.totalCount
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

    internal fun removeDirectory(context: Context, directory: File) {
        val rawPath = directory.absolutePath
        val canonicalPath = runCatching { directory.canonicalPath }.getOrElse { rawPath }
        val matches: (String) -> Boolean = { archiveKey ->
            parseArchiveKey(archiveKey)?.let { archive ->
                BookcaseFolderPaths.isPathInDirectory(archive.path, rawPath) ||
                    BookcaseFolderPaths.isPathInDirectory(archive.path, canonicalPath)
            } == true
        }
        val prefs = preferences(context)
        synchronized(writeLock) {
            synchronized(stateLock) {
                val keysToRemove = prefs.all.keys.mapNotNull(::archiveKeyOf).distinct().filter(matches)
                val pendingRemoved = pendingWrites.keys.removeAll { matches(it.archiveKey) }
                if (pendingRemoved) publishPendingResumeProgress()
                if (keysToRemove.isNotEmpty()) {
                    val editor = prefs.edit()
                    keysToRemove.forEach { editor.removeProgress(it) }
                    editor.apply()
                }
            }
        }
    }

    internal fun updateAfterPageDeletion(
        context: Context,
        file: File,
        originalKey: String,
        remainingPositions: List<Int>
    ) {
        val prefs = preferences(context)
        val newKey = readerArchiveKey(file)
        synchronized(writeLock) {
            synchronized(stateLock) {
                val snapshot = prefs.all
                val original = readCurrentProgress(originalKey) { snapshot.readProgress(originalKey) }
                val existing = readCurrentProgress(newKey) { snapshot.readProgress(newKey) }

                // Keep the latest queued position and prevent writes using the old page indices.
                pendingWrites.keys.removeAll {
                    it.archiveKey == originalKey || (remainingPositions.isEmpty() && it.archiveKey == newKey)
                }
                val editor = prefs.edit().removeProgress(originalKey)
                if (remainingPositions.isEmpty()) {
                    editor.removeProgress(newKey)
                } else if (original != null && (newKey == originalKey || existing == null)) {
                    editor.putProgress(newKey, original.afterPageDeletion(remainingPositions))
                }
                publishPendingResumeProgress()
                editor.apply()
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
                best = when {
                    best == null -> candidate
                    candidate.isFurtherThan(best!!) -> candidate.copy(
                        totalCount = candidate.totalCount ?: best!!.totalCount
                    )
                    best!!.totalCount == null && candidate.totalCount != null ->
                        best!!.copy(totalCount = candidate.totalCount)
                    else -> best
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

    private data class ProgressValue(
        val position: Int,
        val offset: Int,
        val coveredPosition: Int?,
        val totalCount: Int? = null
    ) {
        fun hasReadPages(): Boolean = coveredPosition != null || position > 0 || offset != 0

        fun afterPageDeletion(remainingPositions: List<Int>): ProgressValue {
            val retainedIndex = remainingPositions.indexOf(position)
            val newPosition = if (retainedIndex >= 0) {
                retainedIndex
            } else {
                remainingPositions.indexOfFirst { it > position }.takeIf { it >= 0 }
                    ?: remainingPositions.lastIndex
            }
            val newOffset = if (retainedIndex >= 0) offset else 0
            val newCoveredPosition = coveredPosition?.let { covered ->
                remainingPositions.indexOfLast { it <= covered }.coerceAtLeast(newPosition)
            } ?: newPosition.takeIf { hasReadPages() }

            return ProgressValue(
                newPosition,
                newOffset,
                newCoveredPosition,
                remainingPositions.size.takeIf { it > 0 }
            )
        }

        fun isFurtherThan(other: ProgressValue): Boolean = position > other.position ||
            // Vertical reader offsets are child.top, so scrolling forward decreases them.
            (position == other.position && (offset < other.offset ||
                (offset == other.offset && (coveredPosition ?: -1) > (other.coveredPosition ?: -1))))
    }

    private data class PendingResumeProgress(val value: ProgressValue?, val mergeWithSaved: Boolean)

    private fun PreparedWrite.progressValue(): ProgressValue =
        ProgressValue(position, offset, coveredPosition, totalCount)

    private fun SharedPreferences.readProgress(key: String): ProgressValue? {
        if (!contains(positionKey(key)) && !contains(offsetKey(key))) return null
        return ProgressValue(
            getInt(positionKey(key), 0),
            getInt(offsetKey(key), 0),
            getInt(coveredPositionKey(key), -1).takeIf { it >= 0 },
            getInt(totalCountKey(key), 0).takeIf { it > 0 }
        )
    }

    private fun Map<String, *>.readProgress(key: String): ProgressValue? {
        val position = this[positionKey(key)] as? Int
        val offset = this[offsetKey(key)] as? Int
        if (position == null && offset == null) return null
        return ProgressValue(
            position ?: 0,
            offset ?: 0,
            this[coveredPositionKey(key)] as? Int,
            (this[totalCountKey(key)] as? Int)?.takeIf { it > 0 }
        )
    }

    private fun SharedPreferences.Editor.putProgress(key: String, progress: ProgressValue): SharedPreferences.Editor {
        putInt(positionKey(key), progress.position)
        putInt(offsetKey(key), progress.offset)
        val coveredPosition = progress.coveredPosition
        if (coveredPosition == null) remove(coveredPositionKey(key))
        else putInt(coveredPositionKey(key), coveredPosition)
        progress.totalCount?.takeIf { it > 0 }?.let { putInt(totalCountKey(key), it) }
        return this
    }

    private fun SharedPreferences.Editor.removeProgress(key: String): SharedPreferences.Editor =
        remove(positionKey(key))
            .remove(offsetKey(key))
            .remove(coveredPositionKey(key))
            .remove(totalCountKey(key))

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

    private fun totalCountKey(archiveKey: String): String = "$archiveKey:totalCount"

    private const val ARCHIVE_KEY_PREFIX = "reader:"
    private const val PREFS_NAME = "reader_prefs"
}
