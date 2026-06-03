package com.example.comiclab

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var layoutDebugTools: View
    private lateinit var btnExportCrashLog: Button
    private lateinit var btnRunReaderStressTest: Button
    private lateinit var radioReadingDirection: RadioGroup
    private lateinit var switchDetectMangaCollections: SwitchCompat
    private lateinit var inputStartMarkerErrorTags: EditText
    private lateinit var switchVolumeKeyPageTurn: SwitchCompat
    private lateinit var switchAutoHideSystemBars: SwitchCompat
    private lateinit var switchCustomReaderBrightness: SwitchCompat
    private lateinit var sliderSettingsReaderBrightness: SeekBar
    private lateinit var tvSettingsBrightnessValue: TextView
    private val debugToolExecutor = Executors.newSingleThreadExecutor()
    private var isUpdatingBrightnessControls = false
    @Volatile
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground),
            statusBarColorResId = R.color.comiclab_file_picker_background,
            lightStatusBars = true
        )

        btnBack = findViewById(R.id.btnBack)
        layoutDebugTools = findViewById(R.id.layoutDebugTools)
        btnExportCrashLog = findViewById(R.id.btnExportCrashLog)
        btnRunReaderStressTest = findViewById(R.id.btnRunReaderStressTest)
        radioReadingDirection = findViewById(R.id.radioReadingDirection)
        switchDetectMangaCollections = findViewById(R.id.switchDetectMangaCollections)
        inputStartMarkerErrorTags = findViewById(R.id.inputStartMarkerErrorTags)
        switchVolumeKeyPageTurn = findViewById(R.id.switchVolumeKeyPageTurn)
        switchAutoHideSystemBars = findViewById(R.id.switchAutoHideSystemBars)
        switchCustomReaderBrightness = findViewById(R.id.switchCustomReaderBrightness)
        sliderSettingsReaderBrightness = findViewById(R.id.sliderSettingsReaderBrightness)
        tvSettingsBrightnessValue = findViewById(R.id.tvSettingsBrightnessValue)

        btnBack.setOnClickListener {
            finish()
        }

        bindReadingDirection()
        bindDetectMangaCollections()
        bindStartMarkerErrorTags()
        bindVolumeKeyPageTurn()
        bindAutoHideSystemBars()
        bindCustomReaderBrightness()
        bindDebugTools()
    }

    override fun onDestroy() {
        destroyed = true
        debugToolExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindReadingDirection() {
        val checkedId = when (AppSettings.getReadingDirection(this)) {
            AppSettings.READING_DIRECTION_RIGHT_TO_LEFT -> R.id.radioRightToLeft
            AppSettings.READING_DIRECTION_LEFT_TO_RIGHT -> R.id.radioLeftToRight
            else -> R.id.radioTopToBottom
        }

        radioReadingDirection.check(checkedId)
        radioReadingDirection.setOnCheckedChangeListener { _, checkedRadioId ->
            val direction = when (checkedRadioId) {
                R.id.radioRightToLeft -> AppSettings.READING_DIRECTION_RIGHT_TO_LEFT
                R.id.radioLeftToRight -> AppSettings.READING_DIRECTION_LEFT_TO_RIGHT
                else -> AppSettings.READING_DIRECTION_TOP_TO_BOTTOM
            }
            AppSettings.setReadingDirection(this, direction)
        }
    }

    private fun bindVolumeKeyPageTurn() {
        switchVolumeKeyPageTurn.isChecked = AppSettings.isVolumeKeyPageTurnEnabled(this)
        switchVolumeKeyPageTurn.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setVolumeKeyPageTurnEnabled(this, isChecked)
        }
    }

    private fun bindDetectMangaCollections() {
        switchDetectMangaCollections.isChecked = AppSettings.isDetectMangaCollectionsEnabled(this)
        switchDetectMangaCollections.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setDetectMangaCollectionsEnabled(this, isChecked)
        }
    }

    private fun bindStartMarkerErrorTags() {
        inputStartMarkerErrorTags.setText(AppSettings.getStartMarkerErrorTags(this))
        inputStartMarkerErrorTags.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                AppSettings.setStartMarkerErrorTags(this@SettingsActivity, s?.toString().orEmpty())
            }
        })
    }

    private fun bindAutoHideSystemBars() {
        switchAutoHideSystemBars.isChecked = AppSettings.isAutoHideSystemBarsEnabled(this)
        switchAutoHideSystemBars.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoHideSystemBarsEnabled(this, isChecked)
        }
    }

    private fun bindCustomReaderBrightness() {
        sliderSettingsReaderBrightness.min = AppSettings.MIN_READER_BRIGHTNESS
        sliderSettingsReaderBrightness.max = AppSettings.MAX_READER_BRIGHTNESS
        updateBrightnessControls()

        switchCustomReaderBrightness.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingBrightnessControls) {
                return@setOnCheckedChangeListener
            }

            AppSettings.setCustomReaderBrightnessEnabled(this, isChecked)
            updateBrightnessControls()
        }

        sliderSettingsReaderBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingBrightnessControls) {
                    return
                }

                val brightness = normalizeBrightness(progress)
                AppSettings.setCustomReaderBrightness(this@SettingsActivity, brightness)
                updateBrightnessValueText(brightness)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val brightness = normalizeBrightness(seekBar.progress)
                AppSettings.setCustomReaderBrightness(this@SettingsActivity, brightness)
                updateBrightnessValueText(brightness)
            }
        })
    }

    private fun updateBrightnessControls() {
        isUpdatingBrightnessControls = true
        val isCustomBrightnessEnabled = AppSettings.isCustomReaderBrightnessEnabled(this)
        val brightness = AppSettings.getCustomReaderBrightness(this)

        switchCustomReaderBrightness.isChecked = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.progress = brightness
        sliderSettingsReaderBrightness.isEnabled = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.isClickable = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.isFocusable = isCustomBrightnessEnabled
        sliderSettingsReaderBrightness.alpha = if (isCustomBrightnessEnabled) {
            ENABLED_BRIGHTNESS_SLIDER_ALPHA
        } else {
            DISABLED_BRIGHTNESS_SLIDER_ALPHA
        }
        updateBrightnessValueText(brightness)
        isUpdatingBrightnessControls = false
    }

    private fun updateBrightnessValueText(brightness: Int) {
        val percent = (normalizeBrightness(brightness) * 100f / AppSettings.MAX_READER_BRIGHTNESS)
            .toInt()
            .coerceIn(1, 100)
        tvSettingsBrightnessValue.text = getString(R.string.brightness_percent, percent)
    }

    private fun normalizeBrightness(brightness: Int): Int {
        return brightness.coerceIn(
            AppSettings.MIN_READER_BRIGHTNESS,
            AppSettings.MAX_READER_BRIGHTNESS
        )
    }

    private fun bindDebugTools() {
        val showDebugTools = isDebuggableBuild()
        layoutDebugTools.visibility = if (showDebugTools) View.VISIBLE else View.GONE
        btnExportCrashLog.visibility = if (showDebugTools) View.VISIBLE else View.GONE
        btnRunReaderStressTest.visibility = if (showDebugTools) View.VISIBLE else View.GONE
        if (!showDebugTools) {
            return
        }

        btnExportCrashLog.setOnClickListener {
            exportCrashLog()
        }
        btnRunReaderStressTest.setOnClickListener {
            runReaderStressTest()
        }
    }

    private fun exportCrashLog() {
        val exportFile = CrashLogManager.createLatestCrashLogExport(this)
        if (exportFile == null) {
            showMessage(getString(R.string.crash_log_empty))
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            exportFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_log_share_title))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.crash_log_share_title)))
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.message_no_app_for_file))
        }
    }

    private fun runReaderStressTest() {
        if (!Environment.isExternalStorageManager()) {
            showMessage(getString(R.string.storage_permission_required))
            return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.run_reader_stress_test)
            .setMessage(R.string.debug_stress_scanning)
            .setCancelable(false)
            .create()
        progressDialog.show()

        val scanRoot = savedBrowserDirectoryForStress()
        debugToolExecutor.execute {
            val targetFile = findLargestSupportedComic(scanRoot)
            runOnUiThread {
                if (destroyed) {
                    return@runOnUiThread
                }

                progressDialog.dismiss()
                if (targetFile == null) {
                    showMessage(getString(R.string.debug_stress_no_comic_found))
                    return@runOnUiThread
                }

                launchReaderStressTest(targetFile)
            }
        }
    }

    private fun launchReaderStressTest(file: File) {
        val intent = Intent().setClassName(this, "$packageName.ReaderTestActivity").apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
            putExtra(MangaReaderActivity.EXTRA_START_FROM_BEGINNING, true)
            putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS, true)
            putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_ITERATIONS, DEBUG_STRESS_ITERATIONS)
            putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_DELAY_MS, DEBUG_STRESS_DELAY_MS)
            putExtra(MangaReaderActivity.EXTRA_DEBUG_READER_STRESS_TRIM_EVERY, DEBUG_STRESS_TRIM_EVERY)
        }
        showMessage(getString(R.string.debug_stress_starting, file.name))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.debug_stress_reader_unavailable))
        }
    }

    private fun savedBrowserDirectoryForStress(): File {
        val savedPath = getSharedPreferences(BROWSER_PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_BROWSER_CURRENT_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
        return savedPath ?: Environment.getExternalStorageDirectory()
    }

    private fun findLargestSupportedComic(root: File): File? {
        val startedAt = SystemClock.elapsedRealtime()
        val pending = ArrayDeque<File>().apply {
            add(root)
        }
        var visitedFileCount = 0
        var bestFile: File? = null

        while (pending.isNotEmpty() &&
            visitedFileCount < MAX_DEBUG_STRESS_SCAN_FILES &&
            SystemClock.elapsedRealtime() - startedAt < MAX_DEBUG_STRESS_SCAN_MS
        ) {
            val file = pending.removeLast()
            if (file.name.startsWith(".")) {
                continue
            }

            if (file.isDirectory) {
                val children = runCatching { file.listFiles() }.getOrNull().orEmpty()
                children.forEach { child ->
                    pending.add(child)
                }
                continue
            }

            visitedFileCount++
            if (ComicArchive.isSupportedArchive(file) &&
                (bestFile == null || file.length() > bestFile.length())
            ) {
                bestFile = file
            }
        }

        return bestFile
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ENABLED_BRIGHTNESS_SLIDER_ALPHA = 1f
        private const val DISABLED_BRIGHTNESS_SLIDER_ALPHA = 0.72f
        private const val BROWSER_PREFS_NAME = "saf_prefs"
        private const val KEY_BROWSER_CURRENT_PATH = "current_path"
        private const val MAX_DEBUG_STRESS_SCAN_FILES = 5_000
        private const val MAX_DEBUG_STRESS_SCAN_MS = 12_000L
        private const val DEBUG_STRESS_ITERATIONS = 720
        private const val DEBUG_STRESS_DELAY_MS = 24L
        private const val DEBUG_STRESS_TRIM_EVERY = 18
    }
}
