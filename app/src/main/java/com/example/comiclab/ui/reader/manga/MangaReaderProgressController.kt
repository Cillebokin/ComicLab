package com.example.comiclab.ui.reader.manga

import android.content.Context
import com.example.comiclab.core.diagnostics.CrashLogManager
import com.example.comiclab.core.store.ReaderProgressStore
import java.io.File
import java.util.concurrent.ExecutorService

/** Persists manga reader progress on the Activity-owned writer queue. */
internal class MangaReaderProgressController(
    private val context: Context,
    private val executorProvider: () -> ExecutorService
) {
    fun save(file: File, position: Int, offset: Int, forceCommit: Boolean) {
        val preparedWrite = ReaderProgressStore.prepareSave(
            context = context,
            file = file,
            position = position,
            offset = offset
        )
        val executor = executorProvider()
        if (executor.isShutdown) {
            return
        }
        executor.execute {
            if (forceCommit) {
                preparedWrite.commit()
            } else {
                preparedWrite.apply()
            }
            CrashLogManager.recordReaderCheckpoint(
                context = context,
                file = file,
                position = position,
                offset = offset,
                durable = forceCommit
            )
        }
    }

    fun readPositionAndOffset(file: File): Pair<Int, Int> =
        ReaderProgressStore.readPositionAndOffset(context, file)

    fun clear(file: File) {
        ReaderProgressStore.clearSavedReadingProgress(context, file)
    }

    fun shutdown() {
        executorProvider().shutdown()
    }
}
