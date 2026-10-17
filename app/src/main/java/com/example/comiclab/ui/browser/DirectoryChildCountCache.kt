package com.example.comiclab.ui.browser

/** Keeps recent directory child counts and drops entries when the directory timestamp changes. */
class DirectoryChildCountCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Entry(val lastModified: Long, val childCount: Int)
    data class Lookup(val childCount: Int?, val generation: Long)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var invalidationGeneration = 0L

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun lookup(directoryPath: String, lastModified: Long): Lookup {
        val entry = entries[directoryPath]
            ?: return Lookup(null, invalidationGeneration)
        if (entry.lastModified != lastModified) {
            entries.remove(directoryPath)
            invalidationGeneration++
            return Lookup(null, invalidationGeneration)
        }
        return Lookup(entry.childCount, invalidationGeneration)
    }

    fun get(directoryPath: String, lastModified: Long): Int? =
        lookup(directoryPath, lastModified).childCount

    @Synchronized
    fun put(directoryPath: String, lastModified: Long, childCount: Int) {
        require(childCount >= 0) { "childCount must not be negative" }
        putEntry(directoryPath, lastModified, childCount)
    }

    @Synchronized
    fun generation(): Long = invalidationGeneration

    @Synchronized
    fun putIfUnchangedSince(
        directoryPath: String,
        lastModified: Long,
        childCount: Int,
        expectedGeneration: Long
    ): Boolean {
        if (expectedGeneration != invalidationGeneration) {
            return false
        }
        put(directoryPath, lastModified, childCount)
        return true
    }

    @Synchronized
    fun invalidate(directoryPath: String) {
        entries.remove(directoryPath)
        invalidationGeneration++
    }

    private fun putEntry(directoryPath: String, lastModified: Long, childCount: Int) {
        entries[directoryPath] = Entry(lastModified, childCount)
        while (entries.size > maxEntries) {
            val leastRecentlyUsed = entries.entries.iterator()
            if (!leastRecentlyUsed.hasNext()) {
                break
            }
            leastRecentlyUsed.next()
            leastRecentlyUsed.remove()
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 512
    }
}
