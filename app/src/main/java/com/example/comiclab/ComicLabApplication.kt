package com.example.comiclab

import android.app.Application
import android.util.Log
import com.example.comiclab.core.archive.cache.ArchiveEntryFileCache
import com.example.comiclab.core.diagnostics.CrashLogManager
import com.example.comiclab.core.nas.NasShareArchiveDownloader

class ComicLabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppSettings.applyThemeMode(this)
        CrashLogManager.install(this)
        runCatching { NasShareArchiveDownloader.cleanupInterruptedPartialFiles(cacheDir) }
            .onSuccess { removedFiles ->
                if (removedFiles > 0) {
                    Log.i("ComicLabNAS", "Removed $removedFiles interrupted NAS partial file(s)")
                }
            }
            .onFailure {
                Log.w("ComicLabNAS", "Could not clean interrupted NAS partial files", it)
            }
        Thread(
            { runCatching { ArchiveEntryFileCache.cleanupStaleSessions(cacheDir) } },
            "archive-cache-cleanup"
        ).apply {
            isDaemon = true
            start()
        }
    }
}
