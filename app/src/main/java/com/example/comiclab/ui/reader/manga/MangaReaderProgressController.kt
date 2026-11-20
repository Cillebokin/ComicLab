package com.example.comiclab.ui.reader.manga

import android.content.Context
import android.util.Log
import com.example.comiclab.core.diagnostics.CrashLogManager
import com.example.comiclab.core.store.ReaderProgressStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/** Persists manga reader progress on the Activity-owned writer queue. */
internal class MangaReaderProgressController(
    private val context: Context,
    private val executorProvider: () -> ExecutorService
) {
    private class SaveRequest(
        val archiveKey: String,
        val position: Int,
        val offset: Int,
        val forceCommit: Boolean,
        val coveredPosition: Int?
    ) {
        fun covers(request: SaveRequest): Boolean =
            archiveKey == request.archiveKey && position == request.position && offset == request.offset &&
                coveredPosition == request.coveredPosition && (forceCommit || !request.forceCommit)
    }

    // Only the latest accepted request can absorb duplicates. Its identity also scopes failures.
    private val latestSave = AtomicReference<SaveRequest?>()

    fun save(file: File, position: Int, offset: Int, forceCommit: Boolean, coveredPosition: Int? = null) {
        val executor = executorProvider()
        if (executor.isShutdown) return

        val archiveKey = ReaderProgressStore.readerArchiveKey(file)
        val request = SaveRequest(archiveKey, position, offset, forceCommit, coveredPosition)
        if (latestSave.get()?.covers(request) == true) return

        latestSave.set(request)
        try {
            ReaderProgressStore.enqueueSave(context, archiveKey, position, offset, coveredPosition) { write ->
                executor.execute {
                    val saved = try {
                        if (forceCommit) write.commit() else write.apply()
                    } catch (exception: RuntimeException) {
                        Log.w("ComicLabReader", "reader progress write failed", exception)
                        false
                    }
                    if (saved) {
                        CrashLogManager.recordReaderCheckpoint(
                            context = context,
                            file = file,
                            position = position,
                            offset = offset,
                            durable = forceCommit
                        )
                    } else {
                        latestSave.compareAndSet(request, null)
                    }
                }
            }
        } catch (exception: RuntimeException) {
            latestSave.compareAndSet(request, null)
            if (exception !is RejectedExecutionException) {
                Log.w("ComicLabReader", "reader progress enqueue failed", exception)
            }
        }
    }

    fun readPositionAndOffset(file: File): Pair<Int, Int> =
        ReaderProgressStore.readPositionAndOffset(context, file)

    fun clear(file: File) {
        latestSave.set(null)
        ReaderProgressStore.clearSavedReadingProgress(context, file)
    }

    fun shutdown() {
        executorProvider().shutdown()
    }
}
