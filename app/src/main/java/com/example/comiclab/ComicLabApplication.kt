package com.example.comiclab

import android.app.Application
import com.example.comiclab.core.archive.cache.ArchiveEntryFileCache
import com.example.comiclab.core.diagnostics.CrashLogManager

class ComicLabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppSettings.applyThemeMode(this)
        CrashLogManager.install(this)
        Thread(
            { runCatching { ArchiveEntryFileCache.cleanupStaleSessions(cacheDir) } },
            "archive-cache-cleanup"
        ).apply {
            isDaemon = true
            start()
        }
    }
}
