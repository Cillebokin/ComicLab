package com.example.comiclab.ui.reader.translation

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.davemorrissey.labs.subscaleview.ImageSource
import com.example.comiclab.R
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.core.reader.translation.*
import com.example.comiclab.ui.common.SystemBars
import com.example.comiclab.ui.reader.enhance.EnhancementExportActivity
import com.example.comiclab.ui.reader.manga.ReaderSubsamplingImageView
import java.io.File
import java.util.UUID

internal class PageTranslationActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[PageTranslationViewModel::class.java] }
    private lateinit var image: ReaderSubsamplingImageView
    private lateinit var start: Button
    private lateinit var save: Button
    private lateinit var edit: Button
    private lateinit var original: Button
    private lateinit var status: TextView
    private lateinit var language: Spinner
    private lateinit var font: Spinner
    private lateinit var size: SeekBar
    private var displayed: File? = null
    private var generation = 0
    private var saving = false
    private val languages = arrayOf("Simplified Chinese", "Traditional Chinese", "English", "Japanese", "Korean")
    private fun style() = TranslationStyle(if (font.selectedItemPosition == 1) "serif" else "sans-serif", (size.progress + 50) / 100f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("archive") == null || intent.getStringExtra("entry") == null) { finish(); return }
        model.acquirePreview()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(16, 8, 16, 8) }
        setContentView(root); SystemBars.fitContentBelowSystemBars(this, root, statusBarColorResId = R.color.black)
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; root.addView(this, ViewGroup.LayoutParams(-1, -2)) }
        fun button(parent: LinearLayout, title: Int, action: () -> Unit): Button = Button(this).apply {
            setText(title); textSize = 12f; isAllCaps = false; minWidth = 0; setPadding(4, 0, 4, 0)
            parent.addView(this, LinearLayout.LayoutParams(0, -2, 1f)); setOnClickListener { action() }
        }
        val header = row()
        button(header, R.string.back) { finishSafely() }
        button(header, R.string.translation_settings) { TranslationSettingsDialog.show(this) }
        root.addView(TextView(this).apply { setText(R.string.translation_first_use); setTextColor(Color.LTGRAY); textSize = 12f })
        val options = row()
        fun spinner(values: Array<String>): Spinner = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@PageTranslationActivity, android.R.layout.simple_spinner_dropdown_item, values) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also { (it as? TextView)?.setTextColor(Color.WHITE) }
            }
            options.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        language = spinner(resources.getStringArray(R.array.translation_languages))
        font = spinner(resources.getStringArray(R.array.translation_fonts))
        size = SeekBar(this).apply { max = 50; progress = savedInstanceState?.getInt("fontSize", 50) ?: 50; contentDescription = getString(R.string.translation_font_size); root.addView(this) }
        language.setSelection(savedInstanceState?.getInt("language", 0) ?: 0)
        font.setSelection(savedInstanceState?.getInt("font", 0) ?: 0)
        image = ReaderSubsamplingImageView(this).apply { setMaxScale(8f) }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; root.addView(this) }
        val actions = row()
        start = button(actions, R.string.translation_start) {
            if (model.state.value?.busy == true) { model.cancel(); status.setText(R.string.translation_cancelling) }
            else {
                val config = runCatching { TranslationSettingsStore.load(this) }.getOrNull()
                if (config == null || config.key.isBlank()) { TranslationSettingsDialog.show(this); return@button }
                model.start(File(intent.getStringExtra("archive")!!), intent.getStringExtra("entry")!!, languages[language.selectedItemPosition], style())
            }
        }
        original = button(actions, R.string.translation_compare) {
            val data = model.analysis ?: return@button
            val file = if (displayed == data.source) model.state.value?.result ?: data.source else data.source
            display(file)
        }
        edit = button(actions, R.string.translation_edit) { editBlocks() }
        save = button(actions, R.string.reader_enhance_save) { saveResult() }
        model.state.observe(this) { state ->
            start.setText(if (state.busy) android.R.string.cancel else if (model.analysis != null) R.string.translation_render else R.string.translation_start)
            save.isEnabled = !state.busy && state.result != null && !saving
            edit.isEnabled = !state.busy && model.analysis != null
            original.isEnabled = model.analysis != null
            language.isEnabled = !state.busy && model.analysis == null
            font.isEnabled = !state.busy; size.isEnabled = !state.busy
            if (state.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val stage = state.stage.substringBefore('|')
            val text = when (stage) {
                "models" -> R.string.translation_stage_models; "extract" -> R.string.translation_stage_extract
                "detect" -> R.string.translation_stage_detect; "ocr" -> R.string.translation_stage_ocr
                "translate" -> R.string.translation_stage_translate; "inpaint" -> R.string.translation_stage_inpaint
                "render", "encode" -> R.string.translation_stage_render; "done" -> R.string.translation_done
                "cancelled" -> R.string.translation_cancelled; "failed" -> R.string.translation_failed
                else -> R.string.translation_ready
            }
            status.text = getString(text) + state.stage.substringAfter('|', "").let { if (it.isBlank()) "" else "\n$it" } +
                state.error?.let { "\n" + getString(when(it) {
                    "busy" -> R.string.reader_enhance_busy; "no_text" -> R.string.translation_no_text
                    "overflow" -> R.string.translation_overflow; "memory" -> R.string.translation_memory
                    "storage" -> R.string.translation_storage; "limit" -> R.string.translation_limit
                    else -> R.string.translation_error_help
                }) + if (it.startsWith("AI HTTP ")) " ($it)" else "" }.orEmpty()
            if (!state.busy && state.result != null && state.result != displayed) display(state.result)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = finishSafely() })
    }

    private fun display(file: File) {
        val current = ++generation
        model.retainFile(file)
        image.clearReaderImage { result ->
            if (result.isSuccess) {
                displayed?.let { model.releaseFile(it) }
                displayed = null
            }
            if (isDestroyed || current != generation || result.isFailure) {
                model.releaseFile(file)
                return@clearReaderImage
            }
            try {
                val orientation = if (file == model.analysis?.source) model.analysis?.orientation ?: 1 else 1
                image.setOrientation(when(orientation) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 })
                image.setReaderImage(ImageSource.uri(Uri.fromFile(file)).tilingEnabled())
                displayed = file
            } catch (error: Exception) { model.releaseFile(file); throw error }
        }
    }

    private fun editBlocks() {
        val blocks = model.analysis?.blocks ?: return
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val inputs = blocks.mapIndexed { i, block ->
            list.addView(TextView(this).apply { text = "${i + 1}. ${block.region.sourceText}" })
            EditText(this).apply { setText(block.text); list.addView(this) }
        }
        AlertDialog.Builder(this).setTitle(R.string.translation_edit).setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (inputs.all { it.text.isNotBlank() && it.text.length <= 6000 }) {
                    blocks.forEachIndexed { i, block -> block.text = inputs[i].text.toString() }
                    model.start(File(intent.getStringExtra("archive")!!), intent.getStringExtra("entry")!!, languages[language.selectedItemPosition], style())
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun saveResult() {
        val result = model.state.value?.result ?: return
        if (saving) return
        saving = true; save.isEnabled = false
        model.acquirePreview() // 独立导出暂存复制结束前，不释放任务源文件
        model.retainFile(result)
        EnhancementRuntime.io.execute {
            var staged: File? = null
            val ok = runCatching {
                val root = File(cacheDir, "enhancement-exports").apply { check(mkdirs() || isDirectory) }
                File(root, "${UUID.randomUUID()}.png").also { staged = it; result.copyTo(it) }
            }.getOrNull()
            runOnUiThread {
                saving = false
                try {
                    if (ok != null && !isFinishing && !isDestroyed) {
                        EnhancementExportActivity.launchImage(this, ok, "${File(intent.getStringExtra("archive")!!).nameWithoutExtension}_${intent.getIntExtra("page", 1)}_translated.png")
                    } else staged?.delete()
                    if (!isDestroyed) save.isEnabled = model.state.value?.busy != true && model.state.value?.result != null
                } finally { model.releaseFile(result); model.releasePreview() }
            }
        }
    }

    private fun finishSafely() { model.cancel(); finish() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::size.isInitialized) { outState.putInt("fontSize", size.progress); outState.putInt("font", font.selectedItemPosition); outState.putInt("language", language.selectedItemPosition) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        generation++
        if (::image.isInitialized) image.closeReaderImage {
            if (it.isSuccess) { displayed?.let { file -> model.releaseFile(file) }; displayed = null; model.releasePreview() }
        }
        super.onDestroy()
    }
    companion object {
        fun launch(activity: Activity, archive: File, entry: String, page: Int) {
            activity.startActivity(Intent(activity, PageTranslationActivity::class.java).putExtra("archive", archive.path).putExtra("entry", entry).putExtra("page", page))
        }
    }
}
