package com.example.comiclab

import android.app.AlertDialog
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ComicMigrationActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var btnChooseMigrationRoot: Button
    private lateinit var tvSourceDirectory: TextView
    private lateinit var tvMigrationRoot: TextView
    private lateinit var tvDefaultDirectory: TextView
    private lateinit var progressMigration: ProgressBar
    private lateinit var tvMigrationStatus: TextView
    private lateinit var tvMigrationProgress: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var tvCurrentMarker: TextView
    private lateinit var tvCurrentDestination: TextView
    private lateinit var tvMigrationHistory: TextView

    private val migrationExecutor = Executors.newSingleThreadExecutor()
    private val migrationGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    // 调试阶段显式关闭真实文件写入；准备启用复制前再单独打开。
    private val planner = ComicMigrationPlanner(allowFileCopy = false)
    private val historyLines = mutableListOf<String>()

    private var sourceDirectory: File? = null
    private var migrationRoot: File? = null
    private var defaultDirectory: File? = null
    private var comicFiles: List<File> = emptyList()
    private var currentIndex = 0
    private var isMigrationActive = false
    private var destroyed = false
    private var pickerDirectory: File? = null
    private var directoryPickerDialog: AlertDialog? = null
    private var targetDirectoryDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comic_migration)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground),
            statusBarColorResId = R.color.comiclab_file_picker_background,
            lightStatusBars = true
        )

        btnBack = findViewById(R.id.btnBack)
        btnChooseMigrationRoot = findViewById(R.id.btnChooseMigrationRoot)
        tvSourceDirectory = findViewById(R.id.tvComicMigrationSource)
        tvMigrationRoot = findViewById(R.id.tvComicMigrationRoot)
        tvDefaultDirectory = findViewById(R.id.tvComicMigrationDefaultDirectory)
        progressMigration = findViewById(R.id.progressComicMigration)
        tvMigrationStatus = findViewById(R.id.tvComicMigrationStatus)
        tvMigrationProgress = findViewById(R.id.tvComicMigrationProgress)
        tvCurrentFile = findViewById(R.id.tvComicMigrationFile)
        tvCurrentMarker = findViewById(R.id.tvComicMigrationMarker)
        tvCurrentDestination = findViewById(R.id.tvComicMigrationDestination)
        tvMigrationHistory = findViewById(R.id.tvComicMigrationHistory)

        val sourcePath = intent.getStringExtra(EXTRA_SOURCE_DIRECTORY_PATH)
            ?.takeIf { it.isNotBlank() }
        val source = sourcePath?.let(::File)
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (source?.isDirectory != true) {
            Toast.makeText(this, R.string.message_invalid_directory, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sourceDirectory = source
        tvSourceDirectory.text = source.absolutePath
        tvMigrationRoot.text = getString(R.string.comic_migration_root_not_selected)
        tvDefaultDirectory.text = getString(R.string.comic_migration_default_not_selected)
        tvMigrationStatus.text = getString(R.string.comic_migration_status_title)
        tvMigrationHistory.text = ""
        progressMigration.visibility = View.GONE

        btnBack.setOnClickListener {
            handleBackAction()
        }
        btnChooseMigrationRoot.setOnClickListener {
            if (isMigrationActive) {
                Toast.makeText(
                    this,
                    R.string.comic_migration_running,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                showMigrationRootPicker()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })

        mainHandler.post {
            if (!destroyed && !isFinishing) {
                showMigrationRootPicker()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        migrationGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        directoryPickerDialog?.dismiss()
        targetDirectoryDialog?.dismiss()
        directoryPickerDialog = null
        targetDirectoryDialog = null
        migrationExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun showMigrationRootPicker() {
        showDirectoryPicker(DirectoryPickerPurpose.DESTINATION_TREE)
    }

    private fun showDefaultDirectoryPicker() {
        showDirectoryPicker(DirectoryPickerPurpose.DEFAULT_DESTINATION)
    }

    private fun showDirectoryPicker(purpose: DirectoryPickerPurpose) {
        if (destroyed || isFinishing || isMigrationActive) {
            return
        }

        val storageRoot = Environment.getExternalStorageDirectory()
        pickerDirectory = when (purpose) {
            DirectoryPickerPurpose.DESTINATION_TREE -> sourceDirectory?.parentFile
            DirectoryPickerPurpose.DEFAULT_DESTINATION -> migrationRoot
        }?.takeIf { it.isDirectory } ?: storageRoot

        val content = layoutInflater.inflate(
            R.layout.dialog_comic_migration_directory_picker,
            null
        )
        val tvPath = content.findViewById<TextView>(R.id.tvComicMigrationPickerPath)
        val btnUp = content.findViewById<Button>(R.id.btnComicMigrationPickerUp)
        val listDirectories = content.findViewById<ListView>(
            R.id.listComicMigrationPickerDirectories
        )
        val tvEmpty = content.findViewById<TextView>(R.id.tvComicMigrationPickerEmpty)
        val directories = mutableListOf<File>()
        val adapter = ComicMigrationDirectoryAdapter(this, directories)
        listDirectories.adapter = adapter

        fun refreshPickerContents() {
            val currentDirectory = pickerDirectory
                ?.takeIf { it.isDirectory }
                ?: storageRoot.also { pickerDirectory = it }
            tvPath.text = currentDirectory.absolutePath

            val parent = currentDirectory.parentFile
            btnUp.isEnabled = parent?.isDirectory == true &&
                currentDirectory.stablePath() != storageRoot.stablePath()

            directories.clear()
            directories.addAll(listChildDirectories(currentDirectory))
            adapter.notifyDataSetChanged()
            listDirectories.visibility = if (directories.isEmpty()) View.GONE else View.VISIBLE
            tvEmpty.visibility = if (directories.isEmpty()) View.VISIBLE else View.GONE
        }

        listDirectories.setOnItemClickListener { _, _, position, _ ->
            pickerDirectory = directories.getOrNull(position)
            refreshPickerContents()
        }
        btnUp.setOnClickListener {
            val currentDirectory = pickerDirectory ?: storageRoot
            val parent = currentDirectory.parentFile
            if (parent?.isDirectory == true &&
                currentDirectory.stablePath() != storageRoot.stablePath()
            ) {
                pickerDirectory = parent
                refreshPickerContents()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(
                if (purpose == DirectoryPickerPurpose.DESTINATION_TREE) {
                    R.string.comic_migration_picker_title
                } else {
                    R.string.comic_migration_default_picker_title
                }
            )
            .setView(content)
            .setPositiveButton(
                if (purpose == DirectoryPickerPurpose.DESTINATION_TREE) {
                    R.string.comic_migration_select_current_directory
                } else {
                    R.string.comic_migration_select_default_directory
                }
            ) { _, _ ->
                val selectedDirectory = pickerDirectory ?: storageRoot
                val source = sourceDirectory ?: return@setPositiveButton
                when (purpose) {
                    DirectoryPickerPurpose.DESTINATION_TREE -> {
                        if (!ComicMigrationPlanner.isSafeDestinationDirectory(source, selectedDirectory)) {
                            Toast.makeText(
                                this,
                                R.string.comic_migration_destination_inside_source,
                                Toast.LENGTH_SHORT
                            ).show()
                            mainHandler.post { showMigrationRootPicker() }
                            return@setPositiveButton
                        }
                        migrationRoot = selectedDirectory
                        defaultDirectory = null
                        tvMigrationRoot.text = getString(
                            R.string.comic_migration_root_value,
                            selectedDirectory.absolutePath
                        )
                        tvDefaultDirectory.text = getString(
                            R.string.comic_migration_default_not_selected
                        )
                        mainHandler.post { showDefaultDirectoryPicker() }
                    }

                    DirectoryPickerPurpose.DEFAULT_DESTINATION -> {
                        val destinationTree = migrationRoot
                        if (destinationTree == null ||
                            !ComicMigrationPlanner.isSafeDestinationDirectory(source, selectedDirectory)
                        ) {
                            Toast.makeText(
                                this,
                                R.string.comic_migration_destination_inside_source,
                                Toast.LENGTH_SHORT
                            ).show()
                            mainHandler.post { showDefaultDirectoryPicker() }
                            return@setPositiveButton
                        }
                        startMigration(destinationTree, selectedDirectory)
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .createRounded()
        dialog.setOnDismissListener {
            if (directoryPickerDialog === dialog) {
                directoryPickerDialog = null
            }
        }
        directoryPickerDialog = dialog
        refreshPickerContents()
        dialog.show()
    }

    private fun startMigration(rootDirectory: File, defaultTargetDirectory: File) {
        if (destroyed || isMigrationActive) {
            return
        }
        val source = sourceDirectory ?: return
        if (!ComicMigrationPlanner.isSafeDestinationDirectory(source, rootDirectory) ||
            !ComicMigrationPlanner.isSafeDestinationDirectory(source, defaultTargetDirectory)
        ) {
            Toast.makeText(this, R.string.message_invalid_directory, Toast.LENGTH_SHORT).show()
            return
        }

        directoryPickerDialog?.dismiss()
        directoryPickerDialog = null
        migrationRoot = rootDirectory
        defaultDirectory = defaultTargetDirectory
        comicFiles = emptyList()
        currentIndex = 0
        historyLines.clear()
        tvMigrationHistory.text = ""
        tvMigrationRoot.text = getString(
            R.string.comic_migration_root_value,
            rootDirectory.absolutePath
        )
        tvDefaultDirectory.text = getString(
            R.string.comic_migration_default_value,
            defaultTargetDirectory.absolutePath
        )
        tvMigrationProgress.text = getString(R.string.comic_migration_current_item)
        tvCurrentFile.text = getString(R.string.comic_migration_current_file)
        tvCurrentMarker.text = getString(R.string.comic_migration_current_marker)
        tvCurrentDestination.text = getString(R.string.comic_migration_current_destination)
        tvMigrationStatus.text = getString(R.string.comic_migration_debug_scanning_source)
        progressMigration.visibility = View.VISIBLE
        btnChooseMigrationRoot.isEnabled = false
        isMigrationActive = true

        val generation = migrationGeneration.incrementAndGet()
        runCatching {
            migrationExecutor.execute {
                val result = runCatching {
                    planner.scanComicFilesRecursively(source)
                }
                runOnUiThread {
                    if (destroyed || generation != migrationGeneration.get()) {
                        return@runOnUiThread
                    }

                    result
                        .onSuccess { files ->
                            comicFiles = files
                            if (files.isEmpty()) {
                                completeWithoutItems()
                            } else {
                                processCurrentComic()
                            }
                        }
                        .onFailure { error ->
                            failMigration(error.localizedMessage)
                        }
                }
            }
        }.onFailure { error ->
            failMigration(error.localizedMessage)
        }
    }

    private fun processCurrentComic() {
        if (!isMigrationActive || destroyed) {
            return
        }

        val rootDirectory = migrationRoot
        val source = sourceDirectory
        val defaultTarget = defaultDirectory
        val currentFile = comicFiles.getOrNull(currentIndex)
        if (currentFile == null) {
            completeMigration()
            return
        }
        if (rootDirectory == null || source == null || defaultTarget == null) {
            failMigration()
            return
        }

        val marker = CommonFunc.extractStartMarker(
            currentFile.name,
            AppSettings.getStartMarkerErrorTags(this)
        )
        tvMigrationProgress.text = getString(
            R.string.comic_migration_progress_value,
            currentIndex + 1,
            comicFiles.size
        )
        tvCurrentFile.text = getString(
            R.string.comic_migration_file_value,
            currentFile.absolutePath
        )
        tvCurrentMarker.text = getString(
            R.string.comic_migration_marker_value,
            marker.ifBlank { getString(R.string.comic_migration_marker_not_found) }
        )
        tvCurrentDestination.text = getString(R.string.comic_migration_current_destination)
        tvMigrationStatus.text = getString(R.string.comic_migration_searching_targets)

        val generation = migrationGeneration.get()
        runCatching {
            migrationExecutor.execute {
                val result = runCatching {
                    planner.findMatchingDirectories(
                        rootDirectory,
                        marker,
                        excludedDirectory = source
                    )
                }
                runOnUiThread {
                    if (destroyed || generation != migrationGeneration.get() ||
                        !isMigrationActive
                    ) {
                        return@runOnUiThread
                    }

                    result
                        .onSuccess { matches ->
                            if (matches.isEmpty()) {
                                val status = if (marker.isBlank()) {
                                    getString(
                                        R.string.comic_migration_empty_marker,
                                        defaultTarget.absolutePath
                                    )
                                } else {
                                    getString(
                                        R.string.comic_migration_no_match,
                                        defaultTarget.absolutePath
                                    )
                                }
                                recordDebugTarget(currentFile, defaultTarget, status)
                            } else {
                                showTargetDirectoryChoices(
                                    currentFile,
                                    marker,
                                    matches
                                )
                            }
                        }
                        .onFailure { error ->
                            failMigration(error.localizedMessage)
                        }
                }
            }
        }.onFailure { error ->
            failMigration(error.localizedMessage)
        }
    }

    private fun showTargetDirectoryChoices(
        currentFile: File,
        marker: String,
        matches: List<ComicMigrationDirectoryMatch>
    ) {
        val directories = matches.map { it.directory }
        val matchReasons = matches.associate { match ->
            match.directory.absolutePath to matchReasonText(match.reason)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
        }
        val message = TextView(this).apply {
            text = getString(
                R.string.comic_migration_choose_target_message,
                marker.ifBlank { getString(R.string.comic_migration_marker_not_found) }
            )
            setTextColor(getColor(R.color.comiclab_text_secondary))
            textSize = 13f
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        val listDirectories = ListView(this).apply {
            divider = ContextCompat.getDrawable(
                this@ComicMigrationActivity,
                R.drawable.divider_file_picker_item
            )
            dividerHeight = 1
            adapter = ComicMigrationDirectoryAdapter(
                this@ComicMigrationActivity,
                directories,
                matchReasons
            )
        }
        content.addView(message)
        content.addView(
            listDirectories,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(360)
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.comic_migration_choose_target, currentFile.name))
            .setView(content)
            .setNegativeButton(R.string.comic_migration_aborted) { _, _ ->
                abortMigration()
            }
            .setCancelable(false)
            .createRounded()
        listDirectories.setOnItemClickListener { _, _, position, _ ->
            val targetDirectory = directories.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            if (targetDirectoryDialog === dialog) {
                targetDirectoryDialog = null
            }
            val reason = matchReasons[targetDirectory.absolutePath].orEmpty()
            recordDebugTarget(
                currentFile,
                targetDirectory,
                getString(
                    R.string.comic_migration_selected_target,
                    targetDirectory.absolutePath,
                    reason
                )
            )
        }
        dialog.setOnDismissListener {
            if (targetDirectoryDialog === dialog) {
                targetDirectoryDialog = null
            }
        }
        targetDirectoryDialog = dialog
        dialog.show()
    }

    private fun matchReasonText(reason: ComicMigrationMatchReason): String {
        val resource = when (reason) {
            ComicMigrationMatchReason.EXACT_NAME -> R.string.comic_migration_match_exact
            ComicMigrationMatchReason.CONTAINS_MARKER -> R.string.comic_migration_match_contains
            ComicMigrationMatchReason.COMPONENT_ALIAS -> R.string.comic_migration_match_component
            ComicMigrationMatchReason.KANA_ROMAJI -> R.string.comic_migration_match_kana_romaji
        }
        return getString(resource)
    }

    private fun recordDebugTarget(currentFile: File, targetDirectory: File, context: String) {
        if (!isMigrationActive || destroyed) {
            return
        }

        tvCurrentDestination.text = getString(
            R.string.comic_migration_destination_value,
            targetDirectory.absolutePath
        )
        tvMigrationStatus.text = getString(R.string.comic_migration_debug_recording_target)

        val generation = migrationGeneration.get()
        runCatching {
            migrationExecutor.execute {
                // 调试阶段只记录拟用目标，不调用 planner.copyComicFile()。
                runOnUiThread {
                    if (destroyed || generation != migrationGeneration.get() ||
                        !isMigrationActive
                    ) {
                        return@runOnUiThread
                    }

                    val previewStatus = getString(R.string.comic_migration_debug_not_copied)
                    val status = "$context\n$previewStatus"
                    tvMigrationStatus.text = status
                    historyLines.add(
                        getString(
                            R.string.comic_migration_history_item,
                            currentIndex + 1,
                            currentFile.absolutePath,
                            targetDirectory.absolutePath,
                            previewStatus
                        )
                    )
                    tvMigrationHistory.text = historyLines.joinToString(separator = "\n")
                    currentIndex++
                    processCurrentComic()
                }
            }
        }.onFailure { error ->
            failMigration(error.localizedMessage)
        }
    }

    private fun completeWithoutItems() {
        isMigrationActive = false
        progressMigration.visibility = View.GONE
        btnChooseMigrationRoot.isEnabled = true
        tvMigrationStatus.text = getString(R.string.comic_migration_no_files)
    }

    private fun completeMigration() {
        isMigrationActive = false
        progressMigration.visibility = View.GONE
        btnChooseMigrationRoot.isEnabled = true
        tvMigrationStatus.text = getString(
            R.string.comic_migration_debug_complete,
            comicFiles.size
        )
    }

    private fun failMigration(error: String? = null) {
        if (destroyed) {
            return
        }
        isMigrationActive = false
        progressMigration.visibility = View.GONE
        btnChooseMigrationRoot.isEnabled = true
        tvMigrationStatus.text = error
            ?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.comic_migration_failed_detail, it) }
            ?: getString(R.string.comic_migration_failed)
    }

    private fun abortMigration() {
        migrationGeneration.incrementAndGet()
        isMigrationActive = false
        val dialog = targetDirectoryDialog
        targetDirectoryDialog = null
        dialog?.dismiss()
        progressMigration.visibility = View.GONE
        btnChooseMigrationRoot.isEnabled = true
        tvMigrationStatus.text = getString(R.string.comic_migration_aborted)
    }

    private fun handleBackAction() {
        if (isMigrationActive) {
            abortMigration()
        } else {
            finish()
        }
    }

    private fun listChildDirectories(directory: File): List<File> {
        return runCatching {
            directory.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedWith(
                    compareBy<File>(
                        { it.name.lowercase(Locale.ROOT) },
                        { it.absolutePath.lowercase(Locale.ROOT) }
                    )
                )
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun File.stablePath(): String {
        return runCatching { canonicalPath }.getOrDefault(absolutePath)
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private enum class DirectoryPickerPurpose {
        DESTINATION_TREE,
        DEFAULT_DESTINATION
    }

    companion object {
        const val EXTRA_SOURCE_DIRECTORY_PATH = "comic_migration_source_directory_path"
    }
}
