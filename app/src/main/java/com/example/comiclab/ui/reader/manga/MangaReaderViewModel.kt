package com.example.comiclab.ui.reader.manga

import androidx.lifecycle.ViewModel
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import java.io.File

/** Keeps the prepared-cache owner across reader Activity configuration changes. */
internal class MangaReaderViewModel : ViewModel() {

    private var ownerLease: PreparedReaderCacheLeaseRegistry.Lease? = null
    private var cachePath: String? = null

    @Synchronized
    fun acquireReaderOwner(directory: File): Boolean {
        val requestedPath = PreparedReaderCacheLeaseRegistry.canonicalPathFor(directory)
            ?: return false
        if (cachePath == requestedPath && ownerLease?.isClosed == false) {
            return true
        }

        releaseOwnerLocked()
        val lease = PreparedReaderCacheLeaseRegistry.process.acquireReaderOwner(directory)
            ?: return false
        ownerLease = lease
        cachePath = requestedPath
        return true
    }

    @Synchronized
    fun acquireUsageLease(directory: File): PreparedReaderCacheLeaseRegistry.Lease? {
        val requestedPath = PreparedReaderCacheLeaseRegistry.canonicalPathFor(directory)
            ?: return null
        if (cachePath != requestedPath || ownerLease?.isClosed != false) {
            return null
        }
        return PreparedReaderCacheLeaseRegistry.process.acquireUsageLease(directory)
    }

    @Synchronized
    fun discardCache(directory: File) {
        val requestedPath = PreparedReaderCacheLeaseRegistry.canonicalPathFor(directory)
            ?: return
        if (cachePath == requestedPath) {
            releaseOwnerLocked()
        } else {
            PreparedReaderCacheLeaseRegistry.process.requestDelete(directory)
        }
    }

    override fun onCleared() {
        synchronized(this) {
            releaseOwnerLocked()
        }
        super.onCleared()
    }

    private fun releaseOwnerLocked() {
        val current = ownerLease
        ownerLease = null
        cachePath = null
        current?.requestDeleteAndClose()
    }
}
