package com.example.comiclab.ui.reader.conversion

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.example.comiclab.R
import com.example.comiclab.core.archive.ComicConversionTarget
import com.example.comiclab.core.archive.ComicFormatConverter
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.ui.common.createRounded
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
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
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

internal class ComicFormatConversionDialog : DialogFragment() {
    private val model by lazy { ViewModelProvider(this)[ComicFormatConversionViewModel::class.java] }
    private val sourceFile: File
        get() = File(requireArguments().getString(EXTRA_SOURCE).orEmpty())
    private val targetFormat: ComicConversionTarget?
        get() = requireArguments().getString(EXTRA_TARGET)?.let { value ->
            runCatching { ComicConversionTarget.valueOf(value) }.getOrNull()
        }

    private var pickerLaunched = false
    private var content: View? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val target = targetFormat
        if (result.resultCode != Activity.RESULT_OK || uri == null || target == null) {
            model.releaseBusySlot()
            dismiss()
        } else {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            model.start(sourceFile, uri, target)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
        pickerLaunched = savedInstanceState?.getBoolean(STATE_PICKER_LAUNCHED) ?: false
        val restoredTarget = savedInstanceState?.getString(STATE_TARGET)?.let(Uri::parse)
        val target = targetFormat
        if (!sourceFile.isFile || target == null) {
            model.state.value = ComicFormatConversionState(failed = true)
        } else if (!model.started && restoredTarget != null) {
            model.start(sourceFile, restoredTarget, target)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_comic_format_conversion, null)
        content = view
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.comic_conversion_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .createRounded()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
    }

    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        alert.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
            if (model.isRunning()) {
                model.cancelled.set(true)
                alert.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled = false
                content?.findViewById<TextView>(R.id.tvConversionStatus)
                    ?.setText(R.string.comic_conversion_cancelling)
            } else {
                model.releaseBusySlot()
                dismiss()
            }
        }

        model.state.removeObservers(this)
        model.state.observe(this) { state ->
            val view = content ?: return@observe
            val progress = view.findViewById<ProgressBar>(R.id.progressConversion)
            val status = view.findViewById<TextView>(R.id.tvConversionStatus)
            progress.isIndeterminate = state.percent < 0 && !state.failed
            progress.progress = state.percent.coerceAtLeast(0)
            progress.visibility = if (state.failed) View.GONE else View.VISIBLE
            val finished = state.done || state.failed || state.cancelled
            alert.getButton(DialogInterface.BUTTON_NEGATIVE)?.setText(
                if (finished) R.string.reader_enhance_close else android.R.string.cancel
            )
            if (finished) alert.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else alert.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            when {
                state.done -> status.setText(R.string.comic_conversion_done)
                state.failed -> status.setText(
                    if (state.busy) R.string.comic_conversion_busy
                    else R.string.comic_conversion_failed
                )
                state.cancelled -> dismiss()
                state.saving -> status.text = getString(R.string.comic_conversion_saving, state.percent)
                state.total > 0 -> status.text = getString(
                    R.string.comic_conversion_progress,
                    state.page,
                    state.total,
                    state.percent
                )
                else -> status.setText(R.string.comic_conversion_choose_destination)
            }
        }

        if (!model.started && !pickerLaunched && !model.state.value!!.failed) {
            if (model.acquireBusySlot()) launchDestinationPicker()
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        model.cancelled.set(true)
        model.releaseBusySlot()
        super.onCancel(dialog)
    }

    override fun onDestroyView() {
        model.state.removeObservers(this)
        content = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PICKER_LAUNCHED, pickerLaunched)
        model.target?.let { outState.putString(STATE_TARGET, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun launchDestinationPicker() {
        val target = targetFormat ?: return
        pickerLaunched = true
        runCatching {
            createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = target.mimeType
                putExtra(Intent.EXTRA_TITLE, "${sourceFile.nameWithoutExtension}.${target.extension}")
            })
        }.onFailure {
            model.releaseBusySlot()
            model.state.value = ComicFormatConversionState(failed = true)
        }
    }

    private fun ComicFormatConversionViewModel.isRunning(): Boolean =
        started && state.value?.let { !it.done && !it.failed && !it.cancelled } == true

    companion object {
        private const val EXTRA_SOURCE = "comic_conversion_source"
        private const val EXTRA_TARGET = "comic_conversion_target"
        private const val STATE_PICKER_LAUNCHED = "comic_conversion_picker_launched"
        private const val STATE_TARGET = "comic_conversion_target_uri"
        private const val DIALOG_TAG = "comic-format-conversion"

        fun show(activity: FragmentActivity, source: File, target: ComicConversionTarget) {
            if (activity.supportFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return
            ComicFormatConversionDialog().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_SOURCE, source.absolutePath)
                    putString(EXTRA_TARGET, target.name)
                }
            }.show(activity.supportFragmentManager, DIALOG_TAG)
        }
    }
}
