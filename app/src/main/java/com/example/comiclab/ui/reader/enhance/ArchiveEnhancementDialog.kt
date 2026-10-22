package com.example.comiclab.ui.reader.enhance

import android.app.Activity
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.example.comiclab.AppSettings
import com.example.comiclab.R
import com.example.comiclab.ui.common.createRounded
import java.io.File

/** 任务随对话框存活，配置变更复用模型；关闭或离开宿主后取消并清理未完成文件 */
internal class ArchiveEnhancementDialog : DialogFragment() {
    private val model by lazy { ViewModelProvider(this)[EnhancementExportViewModel::class.java] }
    private val source get() = File(requireArguments().getString("source")!!)
    private val scale get() = requireArguments().getInt("scale", 2)
    private var pickerLaunched = false
    private var content: View? = null
    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) dismiss()
        else {
            runCatching { requireContext().contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            model.start(source, uri, book = true, scale = scale)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerLaunched = savedInstanceState?.getBoolean("pickerLaunched") ?: false
        val target = savedInstanceState?.getString("target")?.let(Uri::parse)
        // 配置变更保留ViewModel继续任务；进程丢失后不复用目标，以免重写或删除已发布文件
        if (!model.started && (target != null || savedInstanceState?.getBoolean("done") == true ||
                savedInstanceState?.getBoolean("failed") == true)) {
            val done = savedInstanceState?.getBoolean("done") == true
            model.state.value = EnhancementExportState(percent = if (done) 100 else -1,
                done = done, failed = !done)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_archive_enhancement, null)
        content = view
        return AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.enhancement_book_title, scale))
            .setView(view)
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.cancelled.set(true) }
            .createRounded().apply { setCanceledOnTouchOutside(false) }
    }

    override fun onStart() {
        super.onStart()
        model.state.removeObservers(this)
        model.state.observe(this) { state ->
            val view = content ?: return@observe
            val progress = view.findViewById<ProgressBar>(R.id.progressExport)
            val status = view.findViewById<TextView>(R.id.tvExportStatus)
            progress.isIndeterminate = state.percent < 0 && !state.failed
            progress.progress = state.percent.coerceAtLeast(0)
            progress.visibility = if (state.failed) View.GONE else View.VISIBLE
            val finished = state.done || state.failed || state.cancelled
            (dialog as? AlertDialog)?.getButton(DialogInterface.BUTTON_NEGATIVE)?.setText(
                if (finished) R.string.reader_enhance_close else android.R.string.cancel)
            if (finished) dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            when {
                state.done -> status.setText(R.string.enhancement_export_done)
                state.failed -> status.setText(if (state.busy) R.string.reader_enhance_busy
                    else R.string.enhancement_export_failed)
                state.cancelled -> dismiss()
                state.total > 0 -> status.text = getString(R.string.enhancement_book_progress,
                    state.page, state.total, state.pagePercent, state.percent)
                state.percent >= 0 -> status.text = getString(R.string.enhancement_writing, state.percent)
                else -> status.setText(R.string.enhancement_choose_destination)
            }
        }
        if (!model.started && !pickerLaunched) {
            pickerLaunched = true
            createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = MimeTypeMap.getSingleton().getMimeTypeFromExtension("cbz")
                    ?: "application/vnd.comicbook+zip"
                putExtra(Intent.EXTRA_TITLE, "${source.nameWithoutExtension}_HD.cbz")
            })
        }
    }

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
        outState.putBoolean("done", model.state.value?.done == true)
        outState.putBoolean("failed", model.state.value?.failed == true)
        model.target?.let { outState.putString("target", it.toString()) }
        super.onSaveInstanceState(outState)
    }

    companion object {
        fun show(activity: FragmentActivity, source: File) {
            if (activity.supportFragmentManager.findFragmentByTag("archive-enhancement") != null) return
            ArchiveEnhancementDialog().apply {
                arguments = Bundle().apply {
                    putString("source", source.absolutePath)
                    putInt("scale", AppSettings.getEnhancementScale(activity))
                }
            }.show(activity.supportFragmentManager, "archive-enhancement")
        }
    }
}
