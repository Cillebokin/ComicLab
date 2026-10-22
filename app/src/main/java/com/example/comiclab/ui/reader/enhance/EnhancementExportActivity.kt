package com.example.comiclab.ui.reader.enhance

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.example.comiclab.R
import com.example.comiclab.ui.common.SystemBars
import java.io.File

internal class EnhancementExportActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[EnhancementExportViewModel::class.java] }
    private val source get() = File(checkNotNull(intent.getStringExtra(EXTRA_SOURCE)))
    private val createDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) finish()
        else {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            model.start(source, uri, book = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enhancement_export)
        SystemBars.fitContentBelowSystemBars(this, findViewById(R.id.enhancementExportRoot),
            statusBarColorResId = R.color.black)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getStringExtra(EXTRA_SOURCE) == null || !source.isFile) { finish(); return }
        run {
            // 只接收预览窗口生成的独立暂存图片，不将用户漫画文件作为可删除的暂存源
            val root = File(cacheDir, "enhancement-exports").canonicalFile
            if (source.canonicalFile.parentFile != root) { finish(); return }
            model.ownImage(source)
        }
        val title = findViewById<TextView>(R.id.tvExportTitle)
        val status = findViewById<TextView>(R.id.tvExportStatus)
        val progress = findViewById<ProgressBar>(R.id.progressExport)
        val close = findViewById<Button>(R.id.btnExportCancel)
        title.setText(R.string.reader_enhance_save)
        close.setOnClickListener { model.cancelled.set(true); finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { model.cancelled.set(true); finish() }
        })
        model.state.observe(this) { state ->
            progress.isIndeterminate = state.percent < 0 && !state.failed
            progress.progress = state.percent.coerceAtLeast(0)
            when {
                state.done -> {
                    status.setText(R.string.enhancement_export_done)
                    close.setText(R.string.reader_enhance_close)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    run {
                        Toast.makeText(this, R.string.enhancement_export_done, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                state.failed -> {
                    status.setText(if (state.busy) R.string.reader_enhance_busy else R.string.enhancement_export_failed)
                    close.setText(R.string.reader_enhance_close)
                    progress.visibility = View.GONE
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                state.cancelled -> finish()
                state.total > 0 -> status.text = getString(R.string.enhancement_book_progress,
                    state.page, state.total, state.pagePercent, state.percent)
                state.percent >= 0 -> status.text = getString(R.string.enhancement_writing, state.percent)
                else -> status.setText(R.string.enhancement_choose_destination)
            }
        }
        if (!model.started) {
            val restoredTarget = savedInstanceState?.getString(STATE_TARGET)?.let(Uri::parse)
            if (restoredTarget != null) model.start(source, restoredTarget, book = false)
            else if (savedInstanceState == null) {
                createDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/png"
                    putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(EXTRA_NAME))
                })
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        model.target?.let { outState.putString(STATE_TARGET, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val EXTRA_SOURCE = "enhancement_source"
        private const val EXTRA_NAME = "enhancement_name"
        private const val STATE_TARGET = "enhancement_target"

        fun launchImage(activity: Activity, staged: File, name: String) {
            activity.startActivity(Intent(activity, EnhancementExportActivity::class.java)
                .putExtra(EXTRA_SOURCE, staged.absolutePath).putExtra(EXTRA_NAME, name))
        }

    }
}
