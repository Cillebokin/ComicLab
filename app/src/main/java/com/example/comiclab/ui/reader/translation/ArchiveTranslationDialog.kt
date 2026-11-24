package com.example.comiclab.ui.reader.translation

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import com.example.comiclab.R
import com.example.comiclab.SettingsActivity
import com.example.comiclab.core.reader.ExportDocumentJournal
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.core.reader.translation.ArchiveTranslation
import com.example.comiclab.core.reader.translation.ArchiveTranslationPagesFailedException
import com.example.comiclab.core.reader.translation.NoReadableTranslationTextException
import com.example.comiclab.core.reader.translation.TranslationModels
import com.example.comiclab.core.reader.translation.TranslationPolicy
import com.example.comiclab.core.reader.translation.TranslationServiceException
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
    val pagesWithoutText: Int = 0,
    @StringRes val issueMessage: Int? = null,
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
        val pending = ExportDocumentJournal(app).reserve(destination, "${source.nameWithoutExtension}_translated.cbz")
        if (pending == null) {
            state.value = ArchiveTranslationExportState(failed = true, issueMessage = R.string.reader_export_tracking_failed)
            return
        }
        if (!EnhancementRuntime.busy.compareAndSet(false, true)) {
            EnhancementRuntime.io.execute { pending.abandon() }
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
                pending.openOutput().use { stream ->
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
                pending.complete()
                val total = result.translatedPages + result.keptOriginalPages
                state.postValue(ArchiveTranslationExportState(
                    percent = 100,
                    page = total,
                    total = total,
                    stage = "done",
                    translatedPages = result.translatedPages,
                    keptOriginalPages = result.keptOriginalPages,
                    pagesWithoutText = result.pagesWithoutText,
                    issueMessage = result.serviceStopped?.messageRes(),
                    done = true
                ))
            } catch (_: CancellationException) {
                state.postValue(ArchiveTranslationExportState(cancelled = true))
            } catch (failure: Exception) {
                android.util.Log.w("ArchiveTranslation", "Export failed (${failure.javaClass.simpleName})")
                val message = when (failure) {
                    is TranslationServiceException -> failure.reason.messageRes()
                    is NoReadableTranslationTextException -> R.string.archive_translation_no_text
                    is ArchiveTranslationPagesFailedException -> R.string.archive_translation_no_pages_completed
                    else -> null
                }
                state.postValue(ArchiveTranslationExportState(failed = true, issueMessage = message))
            } catch (failure: LinkageError) {
                android.util.Log.w("ArchiveTranslation", "Native translation runtime unavailable (${failure.javaClass.simpleName})")
                state.postValue(ArchiveTranslationExportState(failed = true))
            } catch (failure: OutOfMemoryError) {
                android.util.Log.w("ArchiveTranslation", "Translation ran out of memory")
                state.postValue(ArchiveTranslationExportState(failed = true))
            } finally {
                if (!success) pending.abandon()
                directory?.deleteRecursively()
                EnhancementRuntime.busy.set(false)
            }
        }
    }

    private fun checkCancelled() {
        TranslationModels.checkCancelled(cancelled)
    }

    override fun onCleared() {
        cancelled.set(true)
    }
}

@StringRes
private fun TranslationServiceException.Reason.messageRes(): Int = when (this) {
    TranslationServiceException.Reason.AUTHENTICATION -> R.string.archive_translation_auth_error
    TranslationServiceException.Reason.MODEL_OR_ENDPOINT -> R.string.archive_translation_model_error
    TranslationServiceException.Reason.UNAVAILABLE -> R.string.archive_translation_service_unavailable
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
                translatedPages = savedInstanceState.getInt("translatedPages"),
                keptOriginalPages = savedInstanceState.getInt("keptOriginalPages"),
                pagesWithoutText = savedInstanceState.getInt("pagesWithoutText"),
                issueMessage = savedInstanceState.getInt("issueMessage").takeIf { it != 0 }
                    ?: R.string.reader_export_interrupted.takeUnless { done },
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
                state.done -> {
                    val summary = if (state.keptOriginalPages > 0) getString(
                        R.string.archive_translation_partial, state.translatedPages, state.pagesWithoutText,
                        state.keptOriginalPages - state.pagesWithoutText
                    ) else getString(R.string.archive_translation_done, state.translatedPages)
                    status.text = state.issueMessage?.let { "$summary\n${getString(it)}" } ?: summary
                }
                state.failed -> status.setText(
                    if (state.busy) R.string.reader_enhance_busy
                    else state.issueMessage ?: R.string.archive_translation_failed
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
        "no_text" -> R.string.archive_translation_page_no_text
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
        model.state.value?.let { state ->
            outState.putBoolean("done", state.done)
            outState.putInt("translatedPages", state.translatedPages)
            outState.putInt("keptOriginalPages", state.keptOriginalPages)
            outState.putInt("pagesWithoutText", state.pagesWithoutText)
            state.issueMessage?.let { outState.putInt("issueMessage", it) }
        }
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
