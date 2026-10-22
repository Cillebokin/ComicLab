package com.example.comiclab.core.reader.enhance

import android.content.Context
import com.example.comiclab.ComicArchive
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal object ArchiveSuperResolution {
    fun enhance(context: Context, source: File, directory: File, cancelled: AtomicBoolean,
        preferGpu: Boolean = true, scale: Int = 2, onProgress: (page: Int, total: Int, pagePercent: Int) -> Unit): File {
        require(source.isFile && !source.extension.equals("pdf", true))
        fun checkCancelled() { if (cancelled.get()) throw CancellationException() }
        checkCancelled()
        val entries = ComicArchive.imageEntries(source)
        require(entries.isNotEmpty()) { "Archive contains no readable pages" }
        val inputs = File(directory, "inputs").apply { check(mkdirs()) }
        val page = File(directory, "page").apply { check(mkdirs()) }
        val output = File(directory, "enhanced.cbz")
        try {
            ComicArchive.openPreparedImageExtractor(source, inputs).use { extractor ->
                PageSuperResolution(context, reuseModel = true, scale = scale).use { engine ->
                    EnhancedComicWriter.write(output, entries, cancelled, render = { index, entry ->
                        checkCancelled()
                        onProgress(index + 1, entries.size, 0)
                        val original = extractor.extract(index, entry) ?: error("Could not extract page ${index + 1}")
                        try {
                            checkCancelled()
                            engine.enhance(original, page, cancelled, preferGpu) { percent, _ ->
                                onProgress(index + 1, entries.size, percent)
                            }
                        } finally { ComicArchive.discardPreparedImageFile(original) }
                    }, onPage = { done, total -> onProgress(done, total, 100) })
                }
            }
            checkCancelled()
            return output
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            inputs.deleteRecursively()
            page.deleteRecursively()
        }
    }
}
