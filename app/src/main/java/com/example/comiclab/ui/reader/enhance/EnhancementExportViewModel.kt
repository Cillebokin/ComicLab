package com.example.comiclab.ui.reader.enhance

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.example.comiclab.core.reader.enhance.ArchiveSuperResolution
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class EnhancementExportState(val percent: Int = -1, val page: Int = 0,
    val total: Int = 0, val pagePercent: Int = 0, val done: Boolean = false,
    val failed: Boolean = false, val busy: Boolean = false, val cancelled: Boolean = false)

internal class EnhancementExportViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableLiveData(EnhancementExportState())
    val cancelled = AtomicBoolean()
    var target: Uri? = null
        private set
    var started = false
        private set
    private var ownedImage: File? = null
    private val app = application

    fun ownImage(file: File) { ownedImage = file }

    fun start(source: File, destination: Uri, book: Boolean, scale: Int = 2) {
        if (started) return
        started = true
        target = destination
        if (book && !EnhancementRuntime.busy.compareAndSet(false, true)) {
            removeOutput(destination)
            state.value = EnhancementExportState(failed = true, busy = true)
            return
        }
        state.value = EnhancementExportState(percent = 0)
        val executor = if (book) EnhancementRuntime.worker else EnhancementRuntime.io
        executor.execute {
            var directory: File? = null
            var success = false
            try {
                checkCancelled()
                val result = if (book) {
                    directory = File(app.cacheDir, "archive-enhancement/${UUID.randomUUID()}").apply { check(mkdirs()) }
                    var last = EnhancementExportState()
                    ArchiveSuperResolution.enhance(app, source, directory!!, cancelled, scale = scale) { page, total, pagePercent ->
                        val update = EnhancementExportState(
                            percent = (((page - 1L) * 100 + pagePercent) * 95 / (total * 100L)).toInt(),
                            page = page, total = total, pagePercent = pagePercent)
                        if (update != last) { state.postValue(update); last = update }
                    }
                } else source
                checkCancelled()
                app.contentResolver.openOutputStream(destination, "wt").use { output ->
                    checkNotNull(output) { "Destination is not writable" }
                    result.inputStream().buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        var last = -1
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            val percent = if (book) 95 + (written * 4 / result.length().coerceAtLeast(1)).toInt()
                                else (written * 99 / result.length().coerceAtLeast(1)).toInt()
                            if (percent != last) { state.postValue(EnhancementExportState(percent)); last = percent }
                        }
                        output.flush()
                    }
                }
                checkCancelled()
                success = true
                state.postValue(EnhancementExportState(percent = 100, done = true))
            } catch (_: CancellationException) {
                state.postValue(EnhancementExportState(cancelled = true))
            } catch (failure: Exception) {
                android.util.Log.e("EnhancementExport", "Export failed", failure)
                state.postValue(EnhancementExportState(failed = true))
            } catch (failure: LinkageError) {
                android.util.Log.e("EnhancementExport", "Native runtime unavailable", failure)
                state.postValue(EnhancementExportState(failed = true))
            } catch (failure: OutOfMemoryError) {
                android.util.Log.e("EnhancementExport", "Export memory exhausted", failure)
                state.postValue(EnhancementExportState(failed = true))
            } finally {
                if (!success) removeOutput(destination)
                directory?.deleteRecursively()
                ownedImage?.delete()
                if (book) EnhancementRuntime.busy.set(false)
            }
        }
    }

    private fun checkCancelled() { if (cancelled.get()) throw CancellationException() }

    private fun removeOutput(uri: Uri) {
        runCatching {
            if (!DocumentsContract.isDocumentUri(app, uri) || !DocumentsContract.deleteDocument(app.contentResolver, uri)) {
                app.contentResolver.delete(uri, null, null)
            }
        }.onFailure { android.util.Log.w("EnhancementExport", "Could not remove incomplete document", it) }
    }

    override fun onCleared() {
        cancelled.set(true)
        if (!started) ownedImage?.delete()
    }
}
