package com.example.comiclab

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.comiclab.core.archive.cache.ArchiveEntryFileCache
import com.example.comiclab.core.diagnostics.CrashLogManager
import com.example.comiclab.core.nas.NasShareArchiveDownloader
import com.example.comiclab.core.nas.NasPublicDownloadPartJournal
import com.example.comiclab.ui.privacy.PrivacySessionManager

class ComicLabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerPrivacyLockTriggers()
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
        val publicStorageRoot = runCatching { Environment.getExternalStorageDirectory() }
        Thread(
            {
                runCatching {
                    NasPublicDownloadPartJournal.openIfWritable(this, publicStorageRoot.getOrThrow())?.recover()
                }
                    .onFailure { Log.w("ComicLabNAS", "Could not recover public NAS staging files", it) }
                runCatching { ArchiveEntryFileCache.cleanupStaleSessions(cacheDir) }
            },
            "archive-cache-cleanup"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun registerPrivacyLockTriggers() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                PrivacySessionManager.lock()
            }
        })
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) PrivacySessionManager.lock()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED
        )
    }
}
