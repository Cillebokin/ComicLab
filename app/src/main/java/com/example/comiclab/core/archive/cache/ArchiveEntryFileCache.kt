package com.example.comiclab.core.archive.cache

import java.io.Closeable
import java.io.File
import java.util.UUID

/** Session-private, size-bounded cache for extracted archive entries. */
internal class ArchiveEntryFileCache(
    cacheRoot: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) : Closeable {

    private data class CachedEntry(
        val file: File,
        val length: Long,
        var lastAccess: Long
    )

    private val sessionDir = File(cacheRoot, "archive_entries_${UUID.randomUUID()}")
    private val entries = linkedMapOf<String, CachedEntry>()
    private var clock = 0L
    private var closed = false
    private var nextFileId = 0L

    init {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
    }

    @Synchronized
    fun <T> withEntry(
        entryName: String,
        extract: (File) -> Boolean,
        use: (File) -> T
    ): T? {
        if (closed || entryName.isBlank()) return null
        if (!sessionDir.isDirectory && !sessionDir.mkdirs()) return null

        val cached = entries[entryName]?.takeIf { it.file.isFile && it.file.length() == it.length }
        val file = if (cached != null) {
            cached.lastAccess = ++clock
            cached.file
        } else {
            entries.remove(entryName)?.file?.delete()
            val target = File(sessionDir, "entry_${nextFileId++}.cache")
            if (!extract(target) || !target.isFile || target.length() <= 0L) {
                target.delete()
                return null
            }

            val length = target.length()
            if (length <= maxBytes) {
                entries[entryName] = CachedEntry(target, length, ++clock)
                evictToBudget(protectedEntryName = entryName)
            }
            target
        }

        val retain = entries[entryName]?.file == file
        return try {
            use(file)
        } finally {
            if (!retain) file.delete()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        entries.clear()
        sessionDir.deleteRecursively()
    }

    private fun evictToBudget(protectedEntryName: String) {
        var retainedBytes = entries.values.sumOf { it.length }
        while (retainedBytes > maxBytes) {
            val victim = entries.entries
                .asSequence()
                .filter { it.key != protectedEntryName }
                .minByOrNull { it.value.lastAccess }
                ?: return
            entries.remove(victim.key)
            retainedBytes -= victim.value.length
            victim.value.file.delete()
        }
    }

    companion object {
        private const val DEFAULT_MAX_BYTES = 128L * 1024L * 1024L
        private const val DEFAULT_STALE_SESSION_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
        private val SESSION_PREFIXES = listOf("archive_entries_", "sevenzip_")

        /** Removes only known cache-session folders older than the retention period. */
        fun cleanupStaleSessions(
            cacheRoot: File,
            nowMillis: Long = System.currentTimeMillis(),
            maxAgeMillis: Long = DEFAULT_STALE_SESSION_MAX_AGE_MILLIS
        ): Int {
            require(maxAgeMillis >= 0L) { "maxAgeMillis must not be negative" }
            if (!cacheRoot.isDirectory) return 0

            val cutoff = if (maxAgeMillis > nowMillis) Long.MIN_VALUE else nowMillis - maxAgeMillis
            return cacheRoot.listFiles().orEmpty().count { candidate ->
                candidate.isDirectory &&
                    SESSION_PREFIXES.any(candidate.name::startsWith) &&
                    candidate.lastModified() < cutoff &&
                    candidate.deleteRecursively()
            }
        }
    }
}
