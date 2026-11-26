package com.example.comiclab.ui.browser

/** Keeps recent directory child counts and drops entries when the directory timestamp changes. */
class DirectoryChildCountCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Entry(val lastModified: Long, val childCount: Int)
    private data class Key(val directoryPath: String, val inPrivacySpace: Boolean)
    data class Lookup(val childCount: Int?, val generation: Long)

    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private var privacyMarks = emptySet<String>()
    private var invalidationGeneration = 0L

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun lookup(directoryPath: String, lastModified: Long, inPrivacySpace: Boolean = false): Lookup {
        val key = Key(directoryPath, inPrivacySpace)
        val entry = entries[key]
            ?: return Lookup(null, invalidationGeneration)
        if (entry.lastModified != lastModified) {
            entries.remove(key)
            invalidationGeneration++
            return Lookup(null, invalidationGeneration)
        }
        return Lookup(entry.childCount, invalidationGeneration)
    }

    fun get(directoryPath: String, lastModified: Long, inPrivacySpace: Boolean = false): Int? =
        lookup(directoryPath, lastModified, inPrivacySpace).childCount

    @Synchronized
    fun put(directoryPath: String, lastModified: Long, childCount: Int, inPrivacySpace: Boolean = false) {
        require(childCount >= 0) { "childCount must not be negative" }
        putEntry(Key(directoryPath, inPrivacySpace), lastModified, childCount)
    }

    @Synchronized
    fun generation(): Long = invalidationGeneration

    @Synchronized
    fun putIfUnchangedSince(
        directoryPath: String,
        lastModified: Long,
        childCount: Int,
        expectedGeneration: Long,
        inPrivacySpace: Boolean = false
    ): Boolean {
        if (expectedGeneration != invalidationGeneration) {
            return false
        }
        put(directoryPath, lastModified, childCount, inPrivacySpace)
        return true
    }

    @Synchronized
    fun invalidate(directoryPath: String) {
        entries.remove(Key(directoryPath, false))
        entries.remove(Key(directoryPath, true))
        invalidationGeneration++
    }

    @Synchronized
    fun updatePrivacyMarks(marks: Set<String>) {
        if (marks == privacyMarks) return
        privacyMarks = marks
        clear()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        invalidationGeneration++
    }

    private fun putEntry(key: Key, lastModified: Long, childCount: Int) {
        entries[key] = Entry(lastModified, childCount)
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
