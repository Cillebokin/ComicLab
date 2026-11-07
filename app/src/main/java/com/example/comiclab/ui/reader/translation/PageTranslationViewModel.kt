package com.example.comiclab.ui.reader.translation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.core.reader.translation.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class TranslationUiState(val stage: String = "ready", val busy: Boolean = false,
    val result: File? = null, val error: String? = null)

internal class PageTranslationViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableLiveData(TranslationUiState())
    val cancelled = AtomicBoolean()
    val directory = File(application.cacheDir, "page-translation/${UUID.randomUUID()}")
    @Volatile var analysis: TranslationAnalysis? = null
        private set
    private var running = false
    private var disposed = false
    private var previews = 0
    private var cleanupScheduled = false
    private var pendingPublication = false
    private var latestResult: File? = null
    private val retiredResults = mutableSetOf<File>()
    private val fileUsers = mutableMapOf<File, Int>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val app = application

    @Synchronized fun retainFile(file: File) { fileUsers[file] = (fileUsers[file] ?: 0) + 1 }
    @Synchronized fun releaseFile(file: File) {
        val count = (fileUsers[file] ?: error("Unbalanced translation file release")) - 1
        if (count == 0) fileUsers.remove(file) else fileUsers[file] = count
        pruneResults()
    }
    private fun pruneResults() {
        retiredResults.removeAll { it !in fileUsers && (!it.exists() || it.delete()) }
    }
    private fun publish(value: TranslationUiState) {
        synchronized(this) { pendingPublication = true }
        main.post {
            synchronized(this) {
                if (latestResult != value.result) latestResult?.let { retiredResults.add(it) }
                latestResult = value.result
                if (!disposed) state.value = value
                pendingPublication = false
                pruneResults(); cleanup()
            }
        }
    }

    @Synchronized fun acquirePreview() { previews++ }
    @Synchronized fun releasePreview() { previews--; cleanup() }

    @Synchronized fun start(archive: File, entry: String, language: String, style: TranslationStyle) {
        if (running || pendingPublication || disposed) return
        if (!EnhancementRuntime.busy.compareAndSet(false, true)) { state.value = (state.value ?: TranslationUiState()).copy(error = "busy"); return }
        running = true; cancelled.set(false)
        state.value = TranslationUiState(stage = "models", busy = true, result = state.value?.result)
        val previous = state.value?.result
        EnhancementRuntime.worker.execute {
            try {
                check(directory.mkdirs() || directory.isDirectory)
                val pipeline = SinglePageTranslation(app, cancelled) { stage -> state.postValue(TranslationUiState(stage, true, previous)) }
                val current = analysis ?: pipeline.process(archive, entry, directory, TranslationSettingsStore.load(app), language).also { analysis = it }
                val output = pipeline.render(current, directory, style)
                publish(TranslationUiState("done", result = output))
            } catch (_: CancellationException) {
                publish(TranslationUiState("cancelled", result = previous))
            } catch (error: Exception) {
                // 不记录堆栈或远程正文，避免配置、原文、译文进入崩溃日志
                publish(TranslationUiState("failed", result = previous, error = safeError(error)))
            } catch (_: LinkageError) {
                publish(TranslationUiState("failed", result = previous, error = "native"))
            } catch (_: OutOfMemoryError) {
                publish(TranslationUiState("failed", result = previous, error = "memory"))
            } finally {
                EnhancementRuntime.busy.set(false)
                synchronized(this) { running = false; cleanup() }
            }
        }
    }

    fun cancel() { cancelled.set(true) }
    override fun onCleared() { synchronized(this) { disposed = true; cancelled.set(true); cleanup() } }
    private fun cleanup() {
        if (disposed && !running && !pendingPublication && previews == 0 && fileUsers.isEmpty() && !cleanupScheduled) {
            cleanupScheduled = true
            analysis = null
            EnhancementRuntime.io.execute { directory.deleteRecursively() }
        }
    }
    private fun safeError(error: Exception): String = when {
        error.message?.contains("Automatic translation is disabled") == true -> "translation_disabled"
        error.message?.contains("Model package is not installed") == true ||
            error.message?.contains("Model package is incomplete or damaged") == true -> "models_missing"
        error.message?.startsWith("AI HTTP ") == true -> error.message!!
        error.message?.contains("does not fit") == true -> "overflow"
        error.message?.contains("No text") == true || error.message?.contains("No readable") == true -> "no_text"
        error.message?.contains("storage", true) == true -> "storage"
        error.message?.contains("limit", true) == true || error.message?.contains("budget", true) == true || error.message?.contains("supported", true) == true -> "limit"
        else -> "processing"
    }
}
