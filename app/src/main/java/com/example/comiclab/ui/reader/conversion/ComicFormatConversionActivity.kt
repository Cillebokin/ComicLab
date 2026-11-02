package com.example.comiclab.ui.reader.conversion

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.example.comiclab.R
import com.example.comiclab.core.archive.ComicFormatConverter
import com.example.comiclab.core.archive.ComicConversionTarget
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.ui.common.SystemBars
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class ComicFormatConversionState(
    val percent: Int = -1,
    val page: Int = 0,
    val total: Int = 0,
    val saving: Boolean = false,
    val done: Boolean = false,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val cancelled: Boolean = false
)

internal class ComicFormatConversionViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableLiveData(ComicFormatConversionState())
    val cancelled = AtomicBoolean()
    var target: Uri? = null
        private set
    var started = false
        private set
    private var ownsBusySlot = false
    private val app = application

    fun acquireBusySlot(): Boolean {
        if (ownsBusySlot) return true
        ownsBusySlot = EnhancementRuntime.busy.compareAndSet(false, true)
        if (!ownsBusySlot) {
            state.value = ComicFormatConversionState(failed = true, busy = true)
        }
        return ownsBusySlot
    }

    fun releaseBusySlot() {
        if (!started && ownsBusySlot) {
            ownsBusySlot = false
            EnhancementRuntime.busy.set(false)
        }
    }

    fun start(source: File, destination: Uri, targetFormat: ComicConversionTarget) {
        if (started) return
        if (!acquireBusySlot()) return
        started = true
        target = destination
        cancelled.set(false)
        state.value = ComicFormatConversionState(percent = 0)

        EnhancementRuntime.io.execute {
            var workDirectory: File? = null
            var success = false
            try {
                checkCancelled()
                workDirectory = File(app.cacheDir, "comic-format-conversion/${UUID.randomUUID()}")
                    .apply { check(mkdirs() || isDirectory) }
                val output = File(workDirectory, "converted.${targetFormat.extension}")
                ComicFormatConverter.convert(source, output, targetFormat, cancelled) { page, total ->
                    val percent = (page * 90 / total.coerceAtLeast(1)).coerceAtMost(90)
                    state.postValue(ComicFormatConversionState(percent, page, total))
                }
                checkCancelled()
                state.postValue(ComicFormatConversionState(percent = 95, saving = true))
                val destinationStream = app.contentResolver.openOutputStream(destination, "wt")
                    ?: error("Destination is not writable")
                destinationStream.use { stream ->
                    output.inputStream().buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        var lastPercent = 95
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                            written += count
                            val percent = (95 + written * 4 / output.length().coerceAtLeast(1L))
                                .toInt().coerceAtMost(99)
                            if (percent != lastPercent) {
                                state.postValue(ComicFormatConversionState(percent = percent, saving = true))
                                lastPercent = percent
                            }
                        }
                        stream.flush()
                    }
                }
                checkCancelled()
                success = true
                state.postValue(ComicFormatConversionState(percent = 100, done = true))
            } catch (_: CancellationException) {
                state.postValue(ComicFormatConversionState(cancelled = true))
            } catch (failure: OutOfMemoryError) {
                android.util.Log.e("ComicFormatConversion", "Conversion ran out of memory", failure)
                state.postValue(ComicFormatConversionState(failed = true))
            } catch (failure: LinkageError) {
                android.util.Log.e("ComicFormatConversion", "Conversion runtime is unavailable", failure)
                state.postValue(ComicFormatConversionState(failed = true))
            } catch (failure: Exception) {
                android.util.Log.e("ComicFormatConversion", "Conversion failed", failure)
                state.postValue(ComicFormatConversionState(failed = true))
            } finally {
                if (!success) removeOutput(destination)
                workDirectory?.deleteRecursively()
                ownsBusySlot = false
                EnhancementRuntime.busy.set(false)
            }
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException()
    }

    private fun removeOutput(uri: Uri) {
        runCatching {
            val removedByDocumentProvider = runCatching {
                DocumentsContract.isDocumentUri(app, uri) &&
                    DocumentsContract.deleteDocument(app.contentResolver, uri)
            }.getOrDefault(false)
            if (!removedByDocumentProvider) {
                app.contentResolver.delete(uri, null, null)
            }
        }.onFailure {
            android.util.Log.w("ComicFormatConversion", "Could not remove incomplete output", it)
        }
    }

    override fun onCleared() {
        if (started) cancelled.set(true) else releaseBusySlot()
    }
}

internal class ComicFormatConversionActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[ComicFormatConversionViewModel::class.java] }
    private var pickerLaunched = false

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val source = sourceFile()
        val targetFormat = targetFormat()
        if (result.resultCode != Activity.RESULT_OK || uri == null || source == null || targetFormat == null) {
            model.releaseBusySlot()
            finish()
        } else {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            model.start(source, uri, targetFormat)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enhancement_export)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById(R.id.enhancementExportRoot),
            statusBarColorResId = R.color.black
        )
        val source = sourceFile()
        val targetFormat = targetFormat()
        if (source == null || targetFormat == null || !source.isFile) {
            finish()
            return
        }

        val title = findViewById<TextView>(R.id.tvExportTitle)
        val status = findViewById<TextView>(R.id.tvExportStatus)
        val progress = findViewById<ProgressBar>(R.id.progressExport)
        val close = findViewById<Button>(R.id.btnExportCancel)
        title.setText(R.string.comic_conversion_title)
        close.setText(android.R.string.cancel)
        close.setOnClickListener {
            if (model.isRunning()) {
                model.cancelled.set(true)
                close.isEnabled = false
                status.setText(R.string.comic_conversion_cancelling)
            } else {
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (model.isRunning()) {
                    model.cancelled.set(true)
                    close.isEnabled = false
                    status.setText(R.string.comic_conversion_cancelling)
                } else {
                    finish()
                }
            }
        })

        model.state.observe(this) { state ->
            progress.visibility = if (state.failed) View.GONE else View.VISIBLE
            progress.isIndeterminate = state.percent < 0 && !state.failed
            progress.progress = state.percent.coerceAtLeast(0)
            if (model.isRunning()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            when {
                state.done -> {
                    status.setText(R.string.comic_conversion_done)
                    Toast.makeText(this, R.string.comic_conversion_done, Toast.LENGTH_SHORT).show()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    finish()
                }
                state.failed -> {
                    status.setText(
                        if (state.busy) R.string.comic_conversion_busy
                        else R.string.comic_conversion_failed
                    )
                    close.setText(R.string.reader_enhance_close)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                state.cancelled -> {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    finish()
                }
                state.saving -> status.text = getString(R.string.comic_conversion_saving, state.percent)
                state.total > 0 -> status.text = getString(
                    R.string.comic_conversion_progress, state.page, state.total, state.percent
                )
                else -> status.setText(R.string.comic_conversion_choose_destination)
            }
        }

        pickerLaunched = savedInstanceState?.getBoolean(STATE_PICKER_LAUNCHED) ?: false
        if (!model.started) {
            val restoredTarget = savedInstanceState?.getString(STATE_TARGET)?.let(Uri::parse)
            if (restoredTarget != null) {
                model.start(source, restoredTarget, targetFormat)
            } else if (!pickerLaunched && model.acquireBusySlot()) {
                launchDestinationPicker(source, targetFormat)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PICKER_LAUNCHED, pickerLaunched)
        model.target?.let { outState.putString(STATE_TARGET, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun launchDestinationPicker(source: File, targetFormat: ComicConversionTarget) {
        pickerLaunched = true
        createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = targetFormat.mimeType
            putExtra(Intent.EXTRA_TITLE, "${source.nameWithoutExtension}.${targetFormat.extension}")
        })
    }

    private fun sourceFile(): File? =
        intent.getStringExtra(EXTRA_SOURCE)?.takeIf(String::isNotBlank)?.let(::File)

    private fun targetFormat(): ComicConversionTarget? =
        intent.getStringExtra(EXTRA_TARGET)?.let { value ->
            runCatching { ComicConversionTarget.valueOf(value) }.getOrNull()
        }

    private fun ComicFormatConversionViewModel.isRunning(): Boolean =
        started && state.value?.let { !it.done && !it.failed && !it.cancelled } == true

    companion object {
        private const val EXTRA_SOURCE = "comic_conversion_source"
        private const val EXTRA_TARGET = "comic_conversion_target"
        private const val STATE_PICKER_LAUNCHED = "comic_conversion_picker_launched"
        private const val STATE_TARGET = "comic_conversion_target_uri"

        fun launch(activity: Activity, source: File, target: ComicConversionTarget) {
            activity.startActivity(
                Intent(activity, ComicFormatConversionActivity::class.java)
                    .putExtra(EXTRA_SOURCE, source.absolutePath)
                    .putExtra(EXTRA_TARGET, target.name)
            )
        }
    }
}
