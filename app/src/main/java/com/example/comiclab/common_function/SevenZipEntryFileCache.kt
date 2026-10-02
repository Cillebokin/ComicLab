package com.example.comiclab.common_function

import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * A session-private, size-bounded cache for files streamed out of 7z/RAR archives.
 * The extracted file is only exposed while [withEntry] holds the cache lock, so
 * eviction and close cannot remove a file while a decoder is using it.
 */
internal class SevenZipEntryFileCache(
    cacheRoot: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) : Closeable {

    private data class CachedEntry(
        val file: File,
        val length: Long,
        var lastAccess: Long
    )

    private val sessionDir = File(cacheRoot, "sevenzip_${UUID.randomUUID()}")
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

    private companion object {
        const val DEFAULT_MAX_BYTES = 128L * 1024L * 1024L
    }
}
