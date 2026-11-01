package com.example.comiclab.ui.reader.translation

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
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.example.comiclab.R
import com.example.comiclab.SettingsActivity
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.core.reader.translation.ArchiveTranslation
import com.example.comiclab.core.reader.translation.TranslationModels
import com.example.comiclab.core.reader.translation.TranslationPolicy
import com.example.comiclab.core.reader.translation.TranslationSettingsStore
import com.example.comiclab.ui.common.createRounded
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class ArchiveTranslationExportState(
    val percent: Int = -1,
    val page: Int = 0,
    val total: Int = 0,
    val stage: String = "",
    val translatedPages: Int = 0,
    val keptOriginalPages: Int = 0,
    val done: Boolean = false,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val cancelled: Boolean = false
)

internal class ArchiveTranslationExportViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableLiveData(ArchiveTranslationExportState())
    val cancelled = AtomicBoolean()
    var target: Uri? = null
        private set
    var started = false
        private set
    private val app = application

    fun start(source: File, destination: Uri, language: String) {
        if (started) return
        started = true
        target = destination
        if (!EnhancementRuntime.busy.compareAndSet(false, true)) {
            removeOutput(destination)
            state.value = ArchiveTranslationExportState(failed = true, busy = true)
            return
        }
        cancelled.set(false)
        state.value = ArchiveTranslationExportState(percent = 0, stage = "models")
        EnhancementRuntime.worker.execute {
            var directory: File? = null
            var success = false
            try {
                checkCancelled()
                val settings = TranslationSettingsStore.load(app)
                directory = File(app.cacheDir, "archive-translation/${UUID.randomUUID()}")
                    .apply { check(mkdirs() || isDirectory) }
                val result = ArchiveTranslation.translate(
                    context = app,
                    source = source,
                    directory = directory!!,
                    cancelled = cancelled,
                    language = language,
                    settings = settings
                ) { page, total, pagePercent, stage, translated, keptOriginal ->
                    val percent = if (page <= 0 || total <= 0) 0 else
                        (((page - 1L) * 100 + pagePercent.coerceAtLeast(0)) * 95 / (total * 100L)).toInt()
                    state.postValue(ArchiveTranslationExportState(
                        percent = percent,
                        page = page,
                        total = total,
                        stage = stage,
                        translatedPages = translated,
                        keptOriginalPages = keptOriginal
                    ))
                }
                checkCancelled()
                val output = app.contentResolver.openOutputStream(destination, "wt")
                    ?: error("Destination is not writable")
                output.use { stream ->
                    result.file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        var last = -1
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                            written += count
                            val percent = 95 + (written * 4 / result.file.length().coerceAtLeast(1)).toInt()
                            if (percent != last) {
                                state.postValue(ArchiveTranslationExportState(
                                    percent = percent.coerceAtMost(99), page = result.translatedPages + result.keptOriginalPages,
                                    total = result.translatedPages + result.keptOriginalPages, stage = "save",
                                    translatedPages = result.translatedPages,
                                    keptOriginalPages = result.keptOriginalPages
                                ))
                                last = percent
                            }
                        }
                        stream.flush()
                    }
                }
                checkCancelled()
                success = true
                val total = result.translatedPages + result.keptOriginalPages
                state.postValue(ArchiveTranslationExportState(
                    percent = 100,
                    page = total,
                    total = total,
                    stage = "done",
                    translatedPages = result.translatedPages,
                    keptOriginalPages = result.keptOriginalPages,
                    done = true
                ))
            } catch (_: CancellationException) {
                state.postValue(ArchiveTranslationExportState(cancelled = true))
            } catch (failure: Exception) {
                android.util.Log.w("ArchiveTranslation", "Export failed (${failure.javaClass.simpleName})")
                state.postValue(ArchiveTranslationExportState(failed = true))
            } catch (failure: LinkageError) {
                android.util.Log.w("ArchiveTranslation", "Native translation runtime unavailable (${failure.javaClass.simpleName})")
                state.postValue(ArchiveTranslationExportState(failed = true))
            } catch (failure: OutOfMemoryError) {
                android.util.Log.w("ArchiveTranslation", "Translation ran out of memory")
                state.postValue(ArchiveTranslationExportState(failed = true))
            } finally {
                if (!success) removeOutput(destination)
                directory?.deleteRecursively()
                EnhancementRuntime.busy.set(false)
            }
        }
    }

    private fun checkCancelled() {
        TranslationModels.checkCancelled(cancelled)
    }

    private fun removeOutput(uri: Uri) {
        runCatching {
            if (!DocumentsContract.isDocumentUri(app, uri) ||
                !DocumentsContract.deleteDocument(app.contentResolver, uri)) {
                app.contentResolver.delete(uri, null, null)
            }
        }.onFailure {
            android.util.Log.w("ArchiveTranslation", "Could not remove incomplete output (${it.javaClass.simpleName})")
        }
    }

    override fun onCleared() {
        cancelled.set(true)
    }
}

internal class ArchiveTranslationDialog : DialogFragment() {
    private val model by lazy { ViewModelProvider(this)[ArchiveTranslationExportViewModel::class.java] }
    private val source get() = File(requireArguments().getString("source")!!)
    private var pickerLaunched = false
    private var content: View? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            dismiss()
        } else {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val languages = resources.getStringArray(R.array.translation_languages)
            val languageIndex = runCatching {
                TranslationSettingsStore.load(requireContext()).targetLanguageIndex
            }.getOrDefault(0).coerceIn(0, languages.lastIndex)
            val language = languages[languageIndex]
            model.start(source, uri, language)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerLaunched = savedInstanceState?.getBoolean("pickerLaunched") ?: false
        if (!model.started && savedInstanceState?.getBoolean("started") == true) {
            val done = savedInstanceState.getBoolean("done")
            model.state.value = ArchiveTranslationExportState(
                percent = if (done) 100 else -1,
                done = done,
                failed = !done
            )
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_archive_enhancement, null)
        content = view
        view.findViewById<ProgressBar>(R.id.progressExport).visibility = View.GONE
        view.findViewById<TextView>(R.id.tvExportStatus).setText(R.string.archive_translation_notice)
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.archive_translation_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.cancelled.set(true) }
            .setPositiveButton(R.string.archive_translation_choose_destination, null)
            .createRounded()
            .apply { setCanceledOnTouchOutside(false) }
    }

    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        val chooseDestination = alert.getButton(DialogInterface.BUTTON_POSITIVE)
        chooseDestination.setOnClickListener {
            if (pickerLaunched || model.started) return@setOnClickListener
            pickerLaunched = true
            chooseDestination.isEnabled = false
            chooseDestination.visibility = View.GONE
            content?.findViewById<TextView>(R.id.tvExportStatus)
                ?.setText(R.string.archive_translation_waiting_for_picker)
            createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = MimeTypeMap.getSingleton().getMimeTypeFromExtension("cbz")
                    ?: "application/vnd.comicbook+zip"
                putExtra(Intent.EXTRA_TITLE, "${source.nameWithoutExtension}_translated.cbz")
            })
        }
        chooseDestination.visibility = if (model.started || pickerLaunched) View.GONE else View.VISIBLE

        model.state.removeObservers(this)
        model.state.observe(this) { state ->
            val view = content ?: return@observe
            val progress = view.findViewById<ProgressBar>(R.id.progressExport)
            val status = view.findViewById<TextView>(R.id.tvExportStatus)
            val started = model.started
            progress.isIndeterminate = started && (state.stage.substringBefore('|') == "models" || state.percent < 0)
            progress.visibility = if (started && !state.failed) View.VISIBLE else View.GONE
            progress.progress = state.percent.coerceAtLeast(0)
            val finished = state.done || state.failed || state.cancelled
            alert.getButton(DialogInterface.BUTTON_POSITIVE).visibility =
                if (!started && !pickerLaunched) View.VISIBLE else View.GONE
            alert.getButton(DialogInterface.BUTTON_NEGATIVE).setText(
                if (finished) R.string.translation_close else android.R.string.cancel
            )
            if (started && !finished) alert.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else alert.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            when {
                state.done -> status.text = getString(
                    R.string.archive_translation_done, state.translatedPages, state.keptOriginalPages
                )
                state.failed -> status.setText(
                    if (state.busy) R.string.reader_enhance_busy else R.string.archive_translation_failed
                )
                state.cancelled -> dismiss()
                !started -> if (pickerLaunched) status.setText(R.string.archive_translation_waiting_for_picker)
                    else status.setText(R.string.archive_translation_notice)
                state.stage.substringBefore('|') == "models" -> status.text = getString(
                    R.string.archive_translation_models_progress
                ) + state.stage.substringAfter('|', "").let { if (it.isBlank()) "" else "\n$it" }
                state.stage == "save" -> status.text = getString(R.string.archive_translation_saving, state.percent)
                state.total > 0 -> {
                    val stage = stageLabel(state.stage.substringBefore('|'))
                    val detail = state.stage.substringAfter('|', "")
                    status.text = getString(
                        R.string.archive_translation_book_progress,
                        state.page.coerceIn(1, state.total), state.total,
                        if (detail.isBlank()) stage else "$stage · $detail",
                        state.percent
                    )
                }
                else -> status.setText(R.string.archive_translation_notice)
            }
        }
    }

    private fun stageLabel(stage: String): String = getString(when (stage) {
        "extract" -> R.string.translation_stage_extract
        "detect" -> R.string.translation_stage_detect
        "ocr" -> R.string.translation_stage_ocr
        "translate" -> R.string.translation_stage_translate
        "inpaint" -> R.string.translation_stage_inpaint
        "render" -> R.string.translation_stage_render
        "kept" -> R.string.archive_translation_page_kept
        "write" -> R.string.archive_translation_page_written
        else -> R.string.translation_stage_render
    })

    override fun onCancel(dialog: DialogInterface) {
        model.cancelled.set(true)
        super.onCancel(dialog)
    }

    override fun onDestroyView() {
        model.state.removeObservers(this)
        content = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pickerLaunched", pickerLaunched)
        outState.putBoolean("started", model.started)
        outState.putBoolean("done", model.state.value?.done == true)
        outState.putBoolean("failed", model.state.value?.failed == true)
        model.target?.let { outState.putString("target", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    companion object {
        fun show(activity: FragmentActivity, source: File) {
            if (activity.supportFragmentManager.findFragmentByTag("archive-translation") != null) return
            val settings = runCatching { TranslationSettingsStore.load(activity) }.getOrNull()
            val configured = settings != null && settings.address.isNotBlank() &&
                settings.model.isNotBlank() && settings.key.isNotBlank() &&
                runCatching { TranslationPolicy.endpoint(settings.address) }.isSuccess
            if (!configured) {
                Toast.makeText(activity, R.string.translation_config_required, Toast.LENGTH_LONG).show()
                activity.startActivity(Intent(activity, SettingsActivity::class.java).putExtra(
                    SettingsActivity.EXTRA_SETTINGS_SECTION,
                    SettingsActivity.SETTINGS_SECTION_AUTO_TRANSLATION
                ))
                return
            }
            ArchiveTranslationDialog().apply {
                arguments = Bundle().apply { putString("source", source.absolutePath) }
            }.show(activity.supportFragmentManager, "archive-translation")
        }
    }
}
