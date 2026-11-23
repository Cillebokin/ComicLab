package com.example.comiclab.core.nas

import android.content.Context
import com.example.comiclab.core.archive.ArchiveFormatDetector
import com.example.comiclab.core.store.PrefsGateway
import com.example.comiclab.core.store.ReaderProgressStore
import java.io.File
import java.io.IOException
import org.json.JSONObject

internal data class NasArchiveSource(
    val shareId: String,
    val host: String,
    val shareName: String,
    val relativePath: String
) {
    fun matches(profile: NasShareProfile): Boolean =
        profile.id == shareId && profile.host == host && profile.shareName == shareName
}

/** Keeps the source of a versioned cache file outside the evictable cache. */
internal object NasCachedArchiveStore {
    private const val PREFS_NAME = "nas_cached_archive_sources"
    private val versionDirectory = Regex("[0-9a-f]{64}")

    fun isCacheFile(context: Context, file: File): Boolean {
        val directory = file.absoluteFile.parentFile ?: return false
        return directory.parentFile == NasShareArchiveDownloader.cacheDirectory(context.cacheDir).absoluteFile &&
            versionDirectory.matches(directory.name) &&
            ArchiveFormatDetector.isSupportedArchiveExtension(file.extension)
    }

    fun isMissingCacheFile(context: Context, file: File): Boolean =
        !file.exists() && isCacheFile(context, file)

    fun source(context: Context, file: File): NasArchiveSource? {
        if (!isCacheFile(context, file)) return null
        return runCatching {
            val value = PrefsGateway.open(context, PREFS_NAME).getString(file.absolutePath, null)
                ?: return null
            val json = JSONObject(value)
            NasArchiveSource(
                json.getString("shareId"),
                json.getString("host"),
                json.getString("shareName"),
                json.getString("relativePath")
            )
        }.getOrNull()
    }

    fun prepareForReading(
        context: Context,
        file: File,
        profile: NasShareProfile,
        entry: NasDirectoryEntry,
        downloaded: Boolean
    ) {
        check(isCacheFile(context, file) && file.isFile)
        // The cache directory identifies the remote version. Reuse the existing progress
        // timestamp for that version when available, including copies made by older builds.
        val modifiedAt = ReaderProgressStore.latestArchiveProgressTimestamp(context, file)
            ?: if (downloaded) entry.modifiedAt.coerceAtLeast(1L) else file.lastModified()
        if (file.lastModified() != modifiedAt &&
            (!file.setLastModified(modifiedAt) || file.lastModified() != modifiedAt)
        ) {
            throw IOException("Could not restore the NAS cache timestamp")
        }
        if (source(context, file) == NasArchiveSource(profile.id, profile.host, profile.shareName, entry.relativePath)) {
            return
        }
        val json = JSONObject()
            .put("shareId", profile.id)
            .put("host", profile.host)
            .put("shareName", profile.shareName)
            .put("relativePath", entry.relativePath)
        PrefsGateway.open(context, PREFS_NAME).edit().putString(file.absolutePath, json.toString()).apply()
    }
}
