package com.example.comiclab

import android.app.ActivityManager
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
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class ComicMigrationActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tvSourceDirectory: TextView
    private lateinit var tvMigrationRoot: TextView
    private lateinit var tvDefaultDirectory: TextView
    private lateinit var progressMigration: ProgressBar
    private lateinit var tvMigrationStatus: TextView
    private lateinit var tvMigrationProgress: TextView
    private lateinit var tvMigrationHistory: LinearLayout

    private val migrationExecutor = Executors.newSingleThreadExecutor()
    private var migrationWorkerExecutor: ExecutorService? = null
    private val migrationGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val planner = ComicMigrationPlanner(allowFileCopy = true)
    private var sourceDirectory: File? = null
    private var migrationRoot: File? = null
    private var targetPlans: List<ComicMigrationTargetPlan> = emptyList()
    private var currentIndex = 0
    private var copiedCount = 0
    private var skippedCount = 0
    private var failedCount = 0
    private var isMigrationActive = false
    private var destroyed = false
    private var pickerDirectory: File? = null
    private var directoryPickerDialog: AlertDialog? = null
    private var preparationProgressDialog: AlertDialog? = null
    private var preparationProgressBar: ProgressBar? = null
    private var tvPreparationProgress: TextView? = null
    private var preparationTask: Future<*>? = null
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
        tvSourceDirectory = findViewById(R.id.tvComicMigrationSource)
        tvMigrationRoot = findViewById(R.id.tvComicMigrationRoot)
        tvDefaultDirectory = findViewById(R.id.tvComicMigrationDefaultDirectory)
        progressMigration = findViewById(R.id.progressComicMigration)
        tvMigrationStatus = findViewById(R.id.tvComicMigrationStatus)
        tvMigrationProgress = findViewById(R.id.tvComicMigrationProgress)
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
        tvMigrationRoot.text = getString(R.string.comic_migration_path_not_selected)
        tvDefaultDirectory.text = getString(R.string.comic_migration_path_not_selected)
        tvMigrationStatus.text = getString(R.string.comic_migration_status_title)
        tvMigrationHistory.removeAllViews()
        updateMigrationProgress(0, 0)

        btnBack.setOnClickListener {
            handleBackAction()
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
        preparationTask?.cancel(true)
        preparationTask = null
        mainHandler.removeCallbacksAndMessages(null)
        directoryPickerDialog?.dismiss()
        dismissPreparationProgressDialog()
        targetDirectoryDialog?.dismiss()
        directoryPickerDialog = null
        targetDirectoryDialog = null
        migrationExecutor.shutdownNow()
        releaseMigrationWorkerExecutor()
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
                        tvMigrationRoot.text = selectedDirectory.absolutePath
                        tvDefaultDirectory.text = getString(
                            R.string.comic_migration_path_not_selected
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
        targetPlans = emptyList()
        currentIndex = 0
        copiedCount = 0
        skippedCount = 0
        failedCount = 0
        tvMigrationHistory.removeAllViews()
        tvMigrationRoot.text = rootDirectory.absolutePath
        tvDefaultDirectory.text = defaultTargetDirectory.absolutePath
        updateMigrationProgress(0, 0)
        tvMigrationStatus.text = getString(R.string.comic_migration_preparing_targets)
        isMigrationActive = true
        showPreparationProgressDialog()

        val workerExecutor = Executors.newFixedThreadPool(resolveMigrationWorkerCount())
        migrationWorkerExecutor = workerExecutor
        val generation = migrationGeneration.incrementAndGet()
        runCatching {
            preparationTask = migrationExecutor.submit {
                val result = runCatching {
                    val comicFiles = planner.scanComicFilesRecursively(
                        rootDirectory = source,
                        workerExecutor = workerExecutor
                    )
                    val markerErrorTags = AppSettings.getStartMarkerErrorTags(applicationContext)
                    planner.prepareTargetPlans(
                        comicFiles = comicFiles,
                        rootDirectory = rootDirectory,
                        defaultDirectory = defaultTargetDirectory,
                        excludedDirectory = source,
                        markerForFile = { file ->
                            CommonFunc.extractStartMarker(file.name, markerErrorTags)
                        },
                        workerExecutor = workerExecutor,
                        onProgress = { completed, total ->
                            val updateInterval = (total / 100).coerceAtLeast(1)
                            if (completed == 0 || completed == total ||
                                completed % updateInterval == 0
                            ) {
                                mainHandler.post {
                                    if (destroyed || generation != migrationGeneration.get() ||
                                        !isMigrationActive
                                    ) {
                                        return@post
                                    }
                                    val progressBar = preparationProgressBar
                                        ?: return@post
                                    progressBar.isIndeterminate = false
                                    progressBar.max = total.coerceAtLeast(1)
                                    val displayedCompleted = maxOf(
                                        progressBar.progress,
                                        completed.coerceIn(0, progressBar.max)
                                    )
                                    progressBar.progress = displayedCompleted
                                    tvPreparationProgress?.text = getString(
                                        R.string.comic_migration_preparation_matching_progress,
                                        displayedCompleted,
                                        total
                                    )
                                }
                            }
                        }
                    )
                }
                runOnUiThread {
                    if (destroyed || generation != migrationGeneration.get()) {
                        return@runOnUiThread
                    }

                    result
                        .onSuccess { plans ->
                            preparationTask = null
                            releaseMigrationWorkerExecutor()
                            dismissPreparationProgressDialog()
                            targetPlans = plans
                            updateMigrationProgress(0, plans.size)
                            if (plans.isEmpty()) {
                                completeWithoutItems()
                            } else {
                                processCurrentComic()
                            }
                        }
                        .onFailure { error ->
                            preparationTask = null
                            failMigration(error.localizedMessage)
                        }
                }
            }
        }.onFailure { error ->
            failMigration(error.localizedMessage)
        }
    }

    private fun showPreparationProgressDialog() {
        val content = layoutInflater.inflate(
            R.layout.dialog_comic_migration_preparing,
            null
        )
        preparationProgressBar = content.findViewById(R.id.progressComicMigrationPreparation)
        tvPreparationProgress = content.findViewById(R.id.tvComicMigrationPreparationProgress)
        preparationProgressBar?.isIndeterminate = true

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.comic_migration_preparation_title)
            .setView(content)
            .setNegativeButton(R.string.comic_migration_aborted) { _, _ ->
                abortMigration()
            }
            .setCancelable(false)
            .createRounded()
        dialog.setOnDismissListener {
            if (preparationProgressDialog === dialog) {
                preparationProgressDialog = null
                preparationProgressBar = null
                tvPreparationProgress = null
            }
        }
        preparationProgressDialog = dialog
        dialog.show()
    }

    private fun dismissPreparationProgressDialog() {
        val dialog = preparationProgressDialog
        preparationProgressDialog = null
        preparationProgressBar = null
        tvPreparationProgress = null
        dialog?.dismiss()
    }

    private fun processCurrentComic() {
        if (!isMigrationActive || destroyed) {
            return
        }

        val currentPlan = targetPlans.getOrNull(currentIndex)
        if (currentPlan == null) {
            completeMigration()
            return
        }
        showTargetDirectoryChoices(
            currentPlan.comicFile,
            currentPlan.startMarker,
            currentPlan.directories
        )
    }

    private fun showTargetDirectoryChoices(
        currentFile: File,
        marker: String,
        directories: List<File>
    ) {
        val content = layoutInflater.inflate(
            R.layout.dialog_comic_migration_target_picker,
            null
        )
        content.findViewById<TextView>(R.id.tvComicMigrationTargetFileName).text =
            currentFile.name
        content.findViewById<TextView>(R.id.tvComicMigrationTargetMarker).text =
            marker.ifBlank { getString(R.string.comic_migration_marker_not_found) }
        content.findViewById<TextView>(R.id.tvComicMigrationTargetLabel).text =
            getString(R.string.comic_migration_current_destination)
        val listDirectories = content.findViewById<ListView>(
            R.id.listComicMigrationTargetDirectories
        )
        listDirectories.adapter = ComicMigrationDirectoryAdapter(this, directories)
        listDirectories.layoutParams = listDirectories.layoutParams.apply {
            height = dpToPx(directories.size.coerceAtMost(4).coerceAtLeast(1) * 72)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton(R.string.comic_migration_aborted) { _, _ ->
                abortMigration()
            }
            .setCancelable(false)
            .createRounded()
        listDirectories.setOnItemClickListener { _, _, position, _ ->
            val targetDirectory = directories.getOrNull(position) ?: return@setOnItemClickListener
            listDirectories.isEnabled = false
            listDirectories.setOnItemClickListener(null)
            content.findViewById<TextView>(R.id.tvComicMigrationTargetLabel).text =
                getString(R.string.comic_migration_copying)
            copySelectedTarget(currentFile, targetDirectory, dialog)
        }
        dialog.setOnDismissListener {
            if (targetDirectoryDialog === dialog) {
                targetDirectoryDialog = null
            }
        }
        targetDirectoryDialog = dialog
        dialog.show()
    }

    private fun copySelectedTarget(
        currentFile: File,
        targetDirectory: File,
        dialog: AlertDialog
    ) {
        if (!isMigrationActive || destroyed) {
            return
        }

        tvMigrationStatus.text = getString(R.string.comic_migration_copying)

        val generation = migrationGeneration.get()
        runCatching {
            migrationExecutor.execute {
                val result = planner.copyComicFile(currentFile, targetDirectory) {
                    destroyed || !isMigrationActive || generation != migrationGeneration.get()
                }
                runOnUiThread {
                    if (destroyed || generation != migrationGeneration.get() ||
                        !isMigrationActive
                    ) {
                        return@runOnUiThread
                    }

                    val (copyStatus, historyDestination) = when (result.status) {
                        ComicMigrationCopyStatus.COPIED -> {
                            copiedCount++
                            getString(R.string.comic_migration_copied) to getString(
                                R.string.comic_migration_history_copied,
                                targetDirectory.absolutePath
                            )
                        }

                        ComicMigrationCopyStatus.ALREADY_EXISTS -> {
                            skippedCount++
                            getString(R.string.comic_migration_copy_conflict) to getString(
                                R.string.comic_migration_history_source_kept,
                                currentFile.parentFile?.absolutePath.orEmpty()
                            )
                        }

                        ComicMigrationCopyStatus.FAILED,
                        ComicMigrationCopyStatus.PREVIEW_ONLY -> {
                            failedCount++
                            val status = result.error
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    getString(R.string.comic_migration_copy_failed_detail, it)
                                }
                                ?: getString(R.string.comic_migration_copy_failed)
                            status to getString(
                                R.string.comic_migration_history_source_kept,
                                currentFile.parentFile?.absolutePath.orEmpty()
                            )
                        }

                        ComicMigrationCopyStatus.CANCELLED -> {
                            abortMigration()
                            return@runOnUiThread
                        }
                    }
                    tvMigrationStatus.text = copyStatus
                    val historyItem = layoutInflater.inflate(
                        R.layout.item_comic_migration_history,
                        tvMigrationHistory,
                        false
                    )
                    historyItem.findViewById<TextView>(
                        R.id.tvComicMigrationHistoryFileName
                    ).text = currentFile.name
                    historyItem.findViewById<TextView>(
                        R.id.tvComicMigrationHistoryDestination
                    ).text = historyDestination
                    tvMigrationHistory.addView(historyItem)
                    dialog.dismiss()
                    currentIndex++
                    updateMigrationProgress(currentIndex, targetPlans.size)
                    processCurrentComic()
                }
            }
        }.onFailure { error ->
            failMigration(error.localizedMessage)
        }
    }

    private fun updateMigrationProgress(completed: Int, total: Int) {
        val progressMaximum = total.coerceAtLeast(1)
        progressMigration.max = progressMaximum
        progressMigration.progress = completed.coerceIn(0, progressMaximum)
        tvMigrationProgress.text = getString(
            R.string.comic_migration_progress_value,
            completed,
            total
        )
    }

    private fun completeWithoutItems() {
        isMigrationActive = false
        releaseMigrationWorkerExecutor()
        tvMigrationStatus.text = getString(R.string.comic_migration_no_files)
    }

    private fun completeMigration() {
        isMigrationActive = false
        releaseMigrationWorkerExecutor()
        tvMigrationStatus.text = getString(
            R.string.comic_migration_complete,
            targetPlans.size,
            copiedCount,
            skippedCount,
            failedCount
        )
    }

    private fun failMigration(error: String? = null) {
        if (destroyed) {
            return
        }
        isMigrationActive = false
        targetDirectoryDialog?.dismiss()
        targetDirectoryDialog = null
        preparationTask?.cancel(true)
        preparationTask = null
        releaseMigrationWorkerExecutor()
        dismissPreparationProgressDialog()
        tvMigrationStatus.text = error
            ?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.comic_migration_failed_detail, it) }
            ?: getString(R.string.comic_migration_failed)
    }

    private fun abortMigration() {
        migrationGeneration.incrementAndGet()
        isMigrationActive = false
        preparationTask?.cancel(true)
        preparationTask = null
        releaseMigrationWorkerExecutor()
        val dialog = targetDirectoryDialog
        targetDirectoryDialog = null
        dialog?.dismiss()
        dismissPreparationProgressDialog()
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

    private fun resolveMigrationWorkerCount(): Int {
        val cpuLimit = Runtime.getRuntime().availableProcessors()
            .coerceIn(MIN_MIGRATION_WORKERS, MAX_MIGRATION_WORKERS)
        val memoryInfo = ActivityManager.MemoryInfo()
        val lowMemory = runCatching {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(memoryInfo)
            memoryInfo.lowMemory
        }.getOrDefault(false)
        return if (lowMemory) {
            MIN_MIGRATION_WORKERS
        } else {
            cpuLimit
        }
    }

    private fun releaseMigrationWorkerExecutor() {
        migrationWorkerExecutor?.shutdownNow()
        migrationWorkerExecutor = null
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
        private const val MIN_MIGRATION_WORKERS = 2
        private const val MAX_MIGRATION_WORKERS = 8
    }
}
