package com.example.comiclab.core.update

internal object ReleaseVersionComparator {

    enum class Result {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        LOCAL_NEWER,
        UNCOMPARABLE
    }

    fun compare(localVersion: String, releaseTag: String): Result {
        val localParts = parseVersion(localVersion) ?: return Result.UNCOMPARABLE
        val releaseParts = parseVersion(releaseTag) ?: return Result.UNCOMPARABLE
        val partCount = maxOf(localParts.size, releaseParts.size)

        for (index in 0 until partCount) {
            val localPart = localParts.getOrElse(index) { 0 }
            val releasePart = releaseParts.getOrElse(index) { 0 }
            if (releasePart > localPart) {
                return Result.UPDATE_AVAILABLE
            }
            if (releasePart < localPart) {
                return Result.LOCAL_NEWER
            }
        }

        return Result.UP_TO_DATE
    }

    private fun parseVersion(rawVersion: String): List<Int>? {
        var version = rawVersion.trim()
        if (version.endsWith(BASE_RELEASE_SUFFIX, ignoreCase = true)) {
            version = version.dropLast(BASE_RELEASE_SUFFIX.length)
        }
        if (version.startsWith("v", ignoreCase = true)) {
            version = version.drop(1)
        }
        if (!VERSION_PATTERN.matches(version)) {
            return null
        }

        return version.split('.').map { part ->
            part.toIntOrNull() ?: return null
        }
    }

    private const val BASE_RELEASE_SUFFIX = "_Base"
    private val VERSION_PATTERN = Regex("^[0-9]+(?:\\.[0-9]+)*$")
}
