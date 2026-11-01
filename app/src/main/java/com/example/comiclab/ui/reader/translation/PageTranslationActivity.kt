package com.example.comiclab.ui.reader.translation

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.davemorrissey.labs.subscaleview.ImageSource
import com.example.comiclab.ComicArchive
import com.example.comiclab.R
import com.example.comiclab.SettingsActivity
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
    private lateinit var rerender: Button
    private lateinit var save: Button
    private lateinit var edit: Button
    private lateinit var original: Button
    private lateinit var translated: Button
    private lateinit var status: TextView
    private lateinit var sizeValue: TextView
    private lateinit var language: Spinner
    private lateinit var font: Spinner
    private lateinit var size: SeekBar
    private var displayed: File? = null
    private var originalPreview: File? = null
    private var generation = 0
    private var saving = false
    private var switching = false
    private val languages = arrayOf("Simplified Chinese", "Traditional Chinese", "English", "Japanese", "Korean")
    private fun style() = TranslationStyle(if (font.selectedItemPosition == 1) "serif" else "sans-serif", (size.progress + 50) / 100f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("archive") == null || intent.getStringExtra("entry") == null) { finish(); return }
        model.acquirePreview()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(16, 8, 16, 8) }
        setContentView(root); SystemBars.fitContentBelowSystemBars(this, root, statusBarColorResId = R.color.black)
        fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
        fun row(topMarginDp: Int = 0) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            root.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
                if (topMarginDp > 0) topMargin = dp(topMarginDp)
            })
        }
        fun button(parent: LinearLayout, title: Int, action: () -> Unit): Button = MaterialButton(this).apply {
            setText(title); textSize = 14f; includeFontPadding = false; isSingleLine = true
            isAllCaps = false; minWidth = 0; setPadding(dp(4), 0, dp(4), 0)
            backgroundTintList = resources.getColorStateList(R.color.comiclab_button_tint, theme)
            cornerRadius = dp(8)
            val params = LinearLayout.LayoutParams(0, dp(48), 1f)
            if (parent.childCount > 0) params.marginStart = dp(4)
            parent.addView(this, params); setOnClickListener { action() }
        }
        fun spacer(parent: LinearLayout) {
            val params = LinearLayout.LayoutParams(0, dp(48), 1f)
            if (parent.childCount > 0) params.marginStart = dp(4)
            parent.addView(View(this), params)
        }
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
        val sizeRow = row()
        sizeRow.addView(TextView(this).apply {
            setText(R.string.translation_font_size_label); setTextColor(Color.WHITE); textSize = 14f
        }, LinearLayout.LayoutParams(dp(36), -2))
        size = SeekBar(this).apply {
            max = 50; progress = savedInstanceState?.getInt("fontSize", 50) ?: 50
            contentDescription = getString(R.string.translation_font_size)
        }
        sizeRow.addView(size, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })
        sizeValue = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.END
            minWidth = dp(38)
        }
        sizeRow.addView(sizeValue, ViewGroup.LayoutParams(dp(38), -2))
        updateFontSizeValue(size.progress)
        size.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateFontSizeValue(progress)
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val defaultLanguage = runCatching { TranslationSettingsStore.load(this).targetLanguageIndex }.getOrDefault(0)
        language.setSelection((savedInstanceState?.getInt("language") ?: defaultLanguage)
            .coerceIn(0, resources.getStringArray(R.array.translation_languages).lastIndex))
        font.setSelection(savedInstanceState?.getInt("font", 0) ?: 0)
        image = ReaderSubsamplingImageView(this).apply { setMaxScale(8f) }
        root.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; minLines = 2
            root.addView(this)
        }
        val translationActions = row(8)
        start = button(translationActions, R.string.translation_start) {
            if (model.state.value?.busy == true) { model.cancel(); status.setText(R.string.translation_cancelling) }
            else {
                if (model.analysis != null) return@button
                val config = runCatching { TranslationSettingsStore.load(this) }.getOrNull()
                if (config == null || config.address.isBlank() || config.model.isBlank() || config.key.isBlank()) {
                    Toast.makeText(this, R.string.translation_config_required, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, SettingsActivity::class.java).putExtra(
                        SettingsActivity.EXTRA_SETTINGS_SECTION,
                        SettingsActivity.SETTINGS_SECTION_AUTO_TRANSLATION
                    ))
                    return@button
                }
                model.start(File(intent.getStringExtra("archive")!!), intent.getStringExtra("entry")!!, languages[language.selectedItemPosition], style())
            }
        }
        edit = button(translationActions, R.string.translation_edit) { editBlocks() }
        rerender = button(translationActions, R.string.translation_render) {
            if (model.analysis != null) model.start(
                File(intent.getStringExtra("archive")!!), intent.getStringExtra("entry")!!,
                languages[language.selectedItemPosition], style()
            )
        }
        spacer(translationActions)
        val pageActions = row()
        original = button(pageActions, R.string.reader_enhance_original) {
            (model.analysis?.source ?: originalPreview)?.let { display(it) }
        }.apply { setTextColor(resources.getColorStateList(R.color.enhancement_comparison_text, theme)) }
        translated = button(pageActions, R.string.translation_result) {
            model.state.value?.result?.let { display(it) }
        }.apply { setTextColor(resources.getColorStateList(R.color.enhancement_comparison_text, theme)) }
        save = button(pageActions, R.string.reader_enhance_save) { saveResult() }
        button(pageActions, R.string.translation_close) { finishSafely() }
        model.state.observe(this) { state ->
            start.setText(if (state.busy) android.R.string.cancel else R.string.translation_start)
            start.isEnabled = state.busy || model.analysis == null
            rerender.isEnabled = !state.busy && model.analysis != null
            save.isEnabled = !state.busy && state.result != null && !saving
            edit.isEnabled = !state.busy && model.analysis != null
            language.isEnabled = !state.busy && model.analysis == null
            font.isEnabled = !state.busy; size.isEnabled = !state.busy
            refreshComparisonButtons(state)
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
        loadOriginalPreview()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = finishSafely() })
    }

    private fun loadOriginalPreview() {
        val archive = File(intent.getStringExtra("archive")!!)
        val entry = intent.getStringExtra("entry")!!
        val preview = File(model.directory, "preview.${entry.substringAfterLast('.', "img")}")
        model.acquirePreview()
        EnhancementRuntime.io.execute {
            val loaded = runCatching {
                check(model.directory.mkdirs() || model.directory.isDirectory)
                ComicArchive.extractImageEntryToFile(archive, entry, preview)
            }.getOrDefault(false)
            runOnUiThread {
                try {
                    if (loaded && !isFinishing && !isDestroyed) {
                        originalPreview = preview
                        if (model.state.value?.result == null) display(preview)
                    } else if (!loaded) preview.delete()
                } finally { model.releasePreview() }
            }
        }
    }

    private fun display(file: File) {
        val current = ++generation
        model.retainFile(file)
        switching = true
        refreshComparisonButtons()
        image.clearReaderImage { result ->
            if (result.isSuccess) {
                displayed?.let { model.releaseFile(it) }
                displayed = null
            }
            if (isDestroyed || current != generation || result.isFailure) {
                model.releaseFile(file)
                if (!isDestroyed && current == generation) {
                    switching = false
                    refreshComparisonButtons()
                }
                return@clearReaderImage
            }
            try {
                val orientation = model.analysis?.takeIf { file == it.source }?.orientation ?: runCatching {
                    when (ExifInterface(file.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_180 -> 3
                        ExifInterface.ORIENTATION_ROTATE_90 -> 6
                        ExifInterface.ORIENTATION_ROTATE_270 -> 8
                        else -> 1
                    }
                }.getOrDefault(1)
                displayed = file
                image.setOnImageEventListener(object : com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        if (isDestroyed || current != generation) return
                        switching = false
                        refreshComparisonButtons()
                    }
                    override fun onImageLoadError(e: Exception) {
                        if (isDestroyed || current != generation) return
                        displayed = null
                        model.releaseFile(file)
                        switching = false
                        status.setText(R.string.translation_preview_failed)
                        refreshComparisonButtons()
                    }
                })
                image.setOrientation(when(orientation) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 })
                image.setReaderImage(ImageSource.uri(Uri.fromFile(file)).tilingEnabled())
            } catch (_: Exception) {
                if (displayed == file) displayed = null
                model.releaseFile(file)
                switching = false
                status.setText(R.string.translation_preview_failed)
                refreshComparisonButtons()
            }
        }
    }

    private fun refreshComparisonButtons(state: TranslationUiState? = model.state.value) {
        if (!::original.isInitialized || !::translated.isInitialized) return
        val result = state?.result
        val showingOriginal = displayed != null &&
            (displayed == model.analysis?.source || displayed == originalPreview)
        val showingTranslated = result != null && displayed == result
        original.isSelected = showingOriginal
        translated.isSelected = showingTranslated
        original.isEnabled = !switching && state?.busy != true && result != null && showingTranslated
        translated.isEnabled = !switching && state?.busy != true && result != null && showingOriginal
    }

    private fun updateFontSizeValue(progress: Int) {
        if (::sizeValue.isInitialized) sizeValue.text = getString(R.string.translation_font_size_value, progress + 50)
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
