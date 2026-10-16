package com.example.comiclab.ui.browser.organize

import android.app.AlertDialog
import android.os.Environment
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.AppSettings
import com.example.comiclab.FileListScrollState
import com.example.comiclab.R
import com.example.comiclab.core.organize.ComicArchiveMergeProgress
import com.example.comiclab.core.organize.ComicClassifier
import com.example.comiclab.core.organize.ComicMergeOperations
import com.example.comiclab.core.organize.ComicMergeResult
import com.example.comiclab.core.organize.DirectorySimilarityProgress
import com.example.comiclab.core.organize.DirectorySimilarityReportMessage
import com.example.comiclab.core.organize.DirectorySimilarityScanner
import com.example.comiclab.core.organize.ImageFileMergeOperations
import com.example.comiclab.core.organize.ImageFileMergeProgress
import com.example.comiclab.core.organize.ImageFileMergeResult
import com.example.comiclab.createRounded
import com.example.comiclab.showRounded
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the classify, merge and similarity-report operations launched from the classify menu.
 * Moved out of MainActivity without behaviour change. The executor and generation counter stay
 * owned by MainActivity (shutdown and invalidation happen in its onDestroy) and are injected here.
 */
internal class ComicOrganizeController(
    private val activity: AppCompatActivity,
    private val classifyExecutor: ExecutorService,
    private val classifyGeneration: AtomicInteger,
    private val currentPathProvider: () -> String,
    private val openManageAllFilesAccessSettings: () -> Unit,
    private val captureFileListScrollState: () -> FileListScrollState?,
    private val showMessageText: (String) -> Unit,
    private val reloadCurrentDirectory: () -> Unit,
    private val reloadCurrentDirectoryWithScrollState: (FileListScrollState?) -> Unit
) {
    @Volatile
    internal var isClassifyingComics = false
        private set

    @Volatile
    internal var isBuildingDirectorySimilarityReport = false
        private set

    @Volatile
    internal var isMergingComics = false
        private set

    @Volatile
    internal var isMergingFiles = false
        private set

    internal fun confirmClassifyCurrentDirectory() {
        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        if (isClassifyingComics) {
            showMessage(getString(R.string.classify_comics_running))
            return
        }

        if (isBuildingDirectorySimilarityReport) {
            showMessage(MESSAGE_FINDING_SIMILAR_DIRECTORY_NAMES)
            return
        }

        if (isMergingComics) {
            showMessage(MESSAGE_MERGING_COMICS)
            return
        }

        if (isMergingFiles) {
            showMessage(MESSAGE_MERGING_FILES)
            return
        }

        val rootDirectory = File(currentPathProvider())
        if (!rootDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.classify_comics_confirm_title)
            .setMessage(R.string.classify_comics_confirm_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                startClassifyComics(rootDirectory)
            }
            .setNegativeButton(R.string.no, null)
            .showRounded()
    }

    private fun startClassifyComics(rootDirectory: File) {
        val generation = classifyGeneration.incrementAndGet()
        val content = activity.layoutInflater.inflate(R.layout.dialog_classify_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvClassifyProgressStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressClassifyComics)
        val tvCount = content.findViewById<TextView>(R.id.tvClassifyProgressCount)
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.classify_comics)
            .setView(content)
            .setCancelable(false)
            .createRounded()

        isClassifyingComics = true
        progressDialog.show()

        val errorTags = AppSettings.getStartMarkerErrorTags(activity)
        runCatching {
            classifyExecutor.execute {
                val result = runCatching {
                    ComicClassifier.classify(rootDirectory, errorTags) { progress ->
                        activity.runOnUiThread {
                            if (generation == classifyGeneration.get() && !activity.isDestroyed) {
                                updateClassifyProgress(progress, progressBar, tvStatus, tvCount)
                            }
                        }
                    }
                }

                activity.runOnUiThread {
                    if (generation != classifyGeneration.get() || activity.isDestroyed) {
                        return@runOnUiThread
                    }

                    isClassifyingComics = false
                    progressDialog.dismiss()
                    result
                        .onSuccess { classifyResult ->
                            handleClassifyResult(classifyResult)
                        }
                        .onFailure {
                            showMessage(getString(R.string.classify_comics_failed))
                        }
                    reloadCurrentDirectory()
                }
            }
        }.onFailure {
            isClassifyingComics = false
            progressDialog.dismiss()
            showMessage(getString(R.string.classify_comics_failed))
        }
    }

    internal fun startFindSimilarDirectoryNames() {
        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        if (isClassifyingComics) {
            showMessage(getString(R.string.classify_comics_running))
            return
        }

        if (isMergingComics) {
            showMessage(MESSAGE_MERGING_COMICS)
            return
        }

        if (isMergingFiles) {
            showMessage(MESSAGE_MERGING_FILES)
            return
        }

        if (isBuildingDirectorySimilarityReport) {
            showMessage(MESSAGE_FINDING_SIMILAR_DIRECTORY_NAMES)
            return
        }

        val rootDirectory = File(currentPathProvider())
        if (!rootDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        val generation = classifyGeneration.incrementAndGet()
        val rootPath = rootDirectory.absolutePath
        val scrollState = captureFileListScrollState()
        val content = activity.layoutInflater.inflate(R.layout.dialog_classify_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvClassifyProgressStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressClassifyComics)
        val tvCount = content.findViewById<TextView>(R.id.tvClassifyProgressCount)
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(MENU_TITLE_FIND_SIMILAR_DIRECTORY_NAMES)
            .setView(content)
            .setCancelable(false)
            .createRounded()

        isBuildingDirectorySimilarityReport = true
        progressBar.isIndeterminate = true
        tvStatus.setText(MESSAGE_SCANNING_DIRECTORIES)
        tvCount.text = ""
        progressDialog.show()

        runCatching {
            classifyExecutor.execute {
                val result = runCatching {
                    DirectorySimilarityScanner.createReport(
                        rootDirectory = rootDirectory,
                        formatMessage = ::formatDirectorySimilarityReportMessage
                    ) { progress ->
                        activity.runOnUiThread {
                            if (generation == classifyGeneration.get() && !activity.isDestroyed) {
                                updateDirectorySimilarityProgress(progress, progressBar, tvStatus, tvCount)
                            }
                        }
                    }
                }

                activity.runOnUiThread {
                    if (generation != classifyGeneration.get() || activity.isDestroyed) {
                        return@runOnUiThread
                    }

                    isBuildingDirectorySimilarityReport = false
                    progressDialog.dismiss()
                    result
                        .onSuccess { report ->
                            showMessage(
                                getString(
                                    R.string.language_report_created,
                                    report.outputFile.name,
                                    report.pairCount
                                )
                            )
                            if (File(currentPathProvider()).absolutePath == rootPath) {
                                reloadCurrentDirectoryWithScrollState(scrollState)
                            }
                        }
                        .onFailure {
                            showMessage(MESSAGE_DIRECTORY_SIMILARITY_REPORT_FAILED)
                        }
                }
            }
        }.onFailure {
            isBuildingDirectorySimilarityReport = false
            progressDialog.dismiss()
            showMessage(MESSAGE_DIRECTORY_SIMILARITY_REPORT_FAILED)
        }
    }

    internal fun startMergeComicsNonRecursive() {
        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        if (isClassifyingComics) {
            showMessage(getString(R.string.classify_comics_running))
            return
        }

        if (isBuildingDirectorySimilarityReport) {
            showMessage(MESSAGE_FINDING_SIMILAR_DIRECTORY_NAMES)
            return
        }

        if (isMergingComics) {
            showMessage(MESSAGE_MERGING_COMICS)
            return
        }

        if (isMergingFiles) {
            showMessage(MESSAGE_MERGING_FILES)
            return
        }

        val rootDirectory = File(currentPathProvider())
        if (!rootDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        val generation = classifyGeneration.incrementAndGet()
        val rootPath = rootDirectory.absolutePath
        val scrollState = captureFileListScrollState()
        val content = activity.layoutInflater.inflate(R.layout.dialog_classify_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvClassifyProgressStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressClassifyComics)
        val tvCount = content.findViewById<TextView>(R.id.tvClassifyProgressCount)
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(MENU_TITLE_MERGE_COMICS_NON_RECURSIVE)
            .setView(content)
            .setCancelable(false)
            .createRounded()

        isMergingComics = true
        progressBar.isIndeterminate = true
        tvStatus.setText(MESSAGE_SCANNING_COMIC_ARCHIVES)
        tvCount.text = ""
        progressDialog.show()

        runCatching {
            classifyExecutor.execute {
                val result = runCatching {
                    ComicMergeOperations.merge(rootDirectory) { progress ->
                        activity.runOnUiThread {
                            if (generation == classifyGeneration.get() && !activity.isDestroyed) {
                                updateComicArchiveMergeProgress(progress, progressBar, tvStatus, tvCount)
                            }
                        }
                    }
                }

                activity.runOnUiThread {
                    if (generation != classifyGeneration.get() || activity.isDestroyed) {
                        return@runOnUiThread
                    }

                    isMergingComics = false
                    progressDialog.dismiss()
                    result
                        .onSuccess { mergeResult ->
                            val shouldRefresh = handleComicMergeResult(mergeResult)
                            if (shouldRefresh && File(currentPathProvider()).absolutePath == rootPath) {
                                reloadCurrentDirectoryWithScrollState(scrollState)
                            }
                        }
                        .onFailure {
                            showMessage(MESSAGE_MERGE_COMICS_FAILED)
                        }
                }
            }
        }.onFailure {
            isMergingComics = false
            progressDialog.dismiss()
            showMessage(MESSAGE_MERGE_COMICS_FAILED)
        }
    }

    internal fun startMergeFilesNonRecursive() {
        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        if (isClassifyingComics) {
            showMessage(getString(R.string.classify_comics_running))
            return
        }

        if (isBuildingDirectorySimilarityReport) {
            showMessage(MESSAGE_FINDING_SIMILAR_DIRECTORY_NAMES)
            return
        }

        if (isMergingComics) {
            showMessage(MESSAGE_MERGING_COMICS)
            return
        }

        if (isMergingFiles) {
            showMessage(MESSAGE_MERGING_FILES)
            return
        }

        val rootDirectory = File(currentPathProvider())
        if (!rootDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        val generation = classifyGeneration.incrementAndGet()
        val rootPath = rootDirectory.absolutePath
        val scrollState = captureFileListScrollState()
        val content = activity.layoutInflater.inflate(R.layout.dialog_classify_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvClassifyProgressStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressClassifyComics)
        val tvCount = content.findViewById<TextView>(R.id.tvClassifyProgressCount)
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(MENU_TITLE_MERGE_FILES_NON_RECURSIVE)
            .setView(content)
            .setCancelable(false)
            .createRounded()

        isMergingFiles = true
        progressBar.isIndeterminate = true
        tvStatus.setText(MESSAGE_SCANNING_IMAGE_DIRECTORIES)
        tvCount.text = ""
        progressDialog.show()

        runCatching {
            classifyExecutor.execute {
                val result = runCatching {
                    ImageFileMergeOperations.merge(rootDirectory) { progress ->
                        activity.runOnUiThread {
                            if (generation == classifyGeneration.get() && !activity.isDestroyed) {
                                updateImageFileMergeProgress(progress, progressBar, tvStatus, tvCount)
                            }
                        }
                    }
                }

                activity.runOnUiThread {
                    if (generation != classifyGeneration.get() || activity.isDestroyed) {
                        return@runOnUiThread
                    }

                    isMergingFiles = false
                    progressDialog.dismiss()
                    result
                        .onSuccess { mergeResult ->
                            val shouldRefresh = handleFileMergeResult(mergeResult)
                            if (shouldRefresh && File(currentPathProvider()).absolutePath == rootPath) {
                                reloadCurrentDirectoryWithScrollState(scrollState)
                            }
                        }
                        .onFailure {
                            showMessage(MESSAGE_MERGE_FILES_FAILED)
                        }
                }
            }
        }.onFailure {
            isMergingFiles = false
            progressDialog.dismiss()
            showMessage(MESSAGE_MERGE_FILES_FAILED)
        }
    }

    private fun updateClassifyProgress(
        progress: ComicClassifier.Progress,
        progressBar: ProgressBar,
        tvStatus: TextView,
        tvCount: TextView
    ) {
        when (progress.stage) {
            ComicClassifier.Stage.SCANNING -> {
                progressBar.isIndeterminate = true
                tvStatus.text = getString(R.string.classify_comics_scanning)
                tvCount.text = ""
            }

            ComicClassifier.Stage.COPYING -> {
                progressBar.isIndeterminate = false
                progressBar.max = progress.total.coerceAtLeast(1)
                progressBar.progress = progress.completed.coerceIn(0, progress.total.coerceAtLeast(1))
                tvStatus.text = if (progress.currentFileName.isBlank()) {
                    getString(R.string.classify_comics)
                } else {
                    getString(R.string.classify_comics_copying, progress.currentFileName)
                }
                tvCount.text = getString(
                    R.string.classify_comics_progress_count,
                    progress.completed,
                    progress.total
                )
            }
        }
    }

    private fun handleClassifyResult(result: ComicClassifier.Result) {
        val outputDirectory = result.outputDirectory
        if (outputDirectory == null || result.totalCount <= 0) {
            showMessage(getString(R.string.classify_comics_no_files))
            return
        }

        showMessage(
            getString(
                R.string.classify_comics_complete,
                result.copiedCount,
                result.totalCount,
                result.failedCount,
                outputDirectory.name
            )
        )
    }

    private fun updateComicArchiveMergeProgress(
        progress: ComicArchiveMergeProgress,
        progressBar: ProgressBar,
        tvStatus: TextView,
        tvCount: TextView
    ) {
        tvStatus.text = when (progress.stage) {
            ComicArchiveMergeProgress.Stage.SCANNING_ARCHIVES -> getString(MESSAGE_SCANNING_COMIC_ARCHIVES)
            ComicArchiveMergeProgress.Stage.READING_ARCHIVES -> getString(MESSAGE_READING_COMIC_ARCHIVES)
            ComicArchiveMergeProgress.Stage.MERGING_ARCHIVE_ENTRIES -> getString(
                R.string.language_merging_source,
                progress.sourceArchiveName.orEmpty()
            )
        }
        if (progress.total <= 0) {
            progressBar.isIndeterminate = true
            tvCount.text = ""
            return
        }

        progressBar.isIndeterminate = false
        progressBar.max = progress.total
        progressBar.progress = progress.completed.coerceIn(0, progress.total)
        tvCount.text = getString(
            R.string.classify_comics_progress_count,
            progress.completed,
            progress.total
        )
    }

    private fun handleComicMergeResult(result: ComicMergeResult): Boolean {
        return when (result) {
            is ComicMergeResult.Failed -> {
                showMessage(MESSAGE_MERGE_COMICS_FAILED)
                false
            }

            is ComicMergeResult.Completed -> {
                val outputDirectory = result.outputDirectory
                if (result.archiveCount <= 0) {
                    showMessage(MESSAGE_MERGE_COMICS_NO_ARCHIVES)
                } else if (result.totalImageCount <= 0 || outputDirectory == null) {
                    showMessage(MESSAGE_MERGE_COMICS_NO_IMAGES)
                } else {
                    showMessage(
                        getString(
                            R.string.language_comics_merge_result,
                            result.copiedCount,
                            result.totalImageCount,
                            result.failedCount,
                            outputDirectory.name
                        )
                    )
                }
                true
            }
        }
    }

    private fun updateImageFileMergeProgress(
        progress: ImageFileMergeProgress,
        progressBar: ProgressBar,
        tvStatus: TextView,
        tvCount: TextView
    ) {
        tvStatus.text = when (progress.stage) {
            ImageFileMergeProgress.Stage.SCANNING_DIRECTORIES -> getString(MESSAGE_SCANNING_IMAGE_DIRECTORIES)
            ImageFileMergeProgress.Stage.READING_DIRECTORIES -> getString(MESSAGE_READING_IMAGE_DIRECTORIES)
            ImageFileMergeProgress.Stage.COPYING_IMAGES -> getString(
                R.string.language_merging_source,
                progress.sourceDirectoryName.orEmpty()
            )
        }
        if (progress.total <= 0) {
            progressBar.isIndeterminate = true
            tvCount.text = ""
            return
        }

        progressBar.isIndeterminate = false
        progressBar.max = progress.total
        progressBar.progress = progress.completed.coerceIn(0, progress.total)
        tvCount.text = getString(
            R.string.classify_comics_progress_count,
            progress.completed,
            progress.total
        )
    }

    private fun handleFileMergeResult(result: ImageFileMergeResult): Boolean {
        return when (result) {
            is ImageFileMergeResult.Failed -> {
                showMessage(MESSAGE_MERGE_FILES_FAILED)
                false
            }

            is ImageFileMergeResult.Completed -> {
                val outputDirectory = result.outputDirectory
                if (result.folderCount <= 0) {
                    showMessage(MESSAGE_MERGE_FILES_NO_FOLDERS)
                } else if (result.totalImageCount <= 0 || outputDirectory == null) {
                    showMessage(MESSAGE_MERGE_FILES_NO_IMAGES)
                } else {
                    showMessage(
                        getString(
                            R.string.language_files_merge_result,
                            result.copiedCount,
                            result.totalImageCount,
                            result.failedCount,
                            outputDirectory.name
                        )
                    )
                }
                true
            }
        }
    }

    private fun updateDirectorySimilarityProgress(
        progress: DirectorySimilarityProgress,
        progressBar: ProgressBar,
        tvStatus: TextView,
        tvCount: TextView
    ) {
        tvStatus.setText(
            when (progress.stage) {
                DirectorySimilarityProgress.Stage.SCANNING_DIRECTORIES -> MESSAGE_SCANNING_DIRECTORIES
                DirectorySimilarityProgress.Stage.COMPARING_DIRECTORY_NAMES -> MESSAGE_COMPARING_DIRECTORY_NAMES
            }
        )
        if (progress.total <= 0) {
            progressBar.isIndeterminate = true
            tvCount.text = ""
            return
        }

        progressBar.isIndeterminate = false
        progressBar.max = progress.total
        progressBar.progress = progress.completed.coerceIn(0, progress.total)
        tvCount.text = getString(
            R.string.classify_comics_progress_count,
            progress.completed,
            progress.total
        )
    }

    private fun formatDirectorySimilarityReportMessage(
        message: DirectorySimilarityReportMessage,
        arguments: Array<out Any>
    ): String {
        val stringResource = when (message) {
            DirectorySimilarityReportMessage.TITLE -> R.string.language_report_title
            DirectorySimilarityReportMessage.GENERATED_AT -> R.string.language_report_generated_at
            DirectorySimilarityReportMessage.ROOT_DIRECTORY -> R.string.language_report_root
            DirectorySimilarityReportMessage.SIMILARITY_RULE -> R.string.language_report_rule
            DirectorySimilarityReportMessage.DIRECTORY_COUNT -> R.string.language_report_folder_count
            DirectorySimilarityReportMessage.PAIR_COUNT -> R.string.language_report_pair_count
            DirectorySimilarityReportMessage.NO_PAIRS -> R.string.language_report_no_pairs
            DirectorySimilarityReportMessage.PAIR_SIMILARITY -> R.string.language_report_pair_similarity
            DirectorySimilarityReportMessage.NAME_A -> R.string.language_report_name_a
            DirectorySimilarityReportMessage.PATH_A -> R.string.language_report_path_a
            DirectorySimilarityReportMessage.NAME_B -> R.string.language_report_name_b
            DirectorySimilarityReportMessage.PATH_B -> R.string.language_report_path_b
        }
        return getString(stringResource, *arguments)
    }

    private fun getString(@StringRes resId: Int): String = activity.getString(resId)

    private fun getString(@StringRes resId: Int, vararg formatArgs: Any): String =
        activity.getString(resId, *formatArgs)

    private fun showMessage(message: String) {
        showMessageText(message)
    }

    private fun showMessage(@StringRes messageResId: Int) {
        showMessageText(getString(messageResId))
    }

    companion object {
        internal val MENU_TITLE_MERGE_COMICS_NON_RECURSIVE = R.string.language_menu_merge_comics
        internal val MENU_TITLE_MERGE_FILES_NON_RECURSIVE = R.string.language_menu_merge_files
        internal val MESSAGE_FINDING_SIMILAR_DIRECTORY_NAMES = R.string.language_finding_similar_directories
        internal val MESSAGE_MERGING_COMICS = R.string.language_merging_comics
        internal val MESSAGE_MERGING_FILES = R.string.language_merging_files
        private val MENU_TITLE_FIND_SIMILAR_DIRECTORY_NAMES = R.string.language_menu_find_similar_directories
        private val MESSAGE_SCANNING_DIRECTORIES = R.string.language_scanning_directories
        private val MESSAGE_COMPARING_DIRECTORY_NAMES = R.string.language_comparing_directory_names
        private val MESSAGE_SCANNING_COMIC_ARCHIVES = R.string.language_scanning_comic_archives
        private val MESSAGE_READING_COMIC_ARCHIVES = R.string.language_reading_comic_archives
        private val MESSAGE_SCANNING_IMAGE_DIRECTORIES = R.string.language_scanning_image_directories
        private val MESSAGE_READING_IMAGE_DIRECTORIES = R.string.language_reading_image_directories
        private val MESSAGE_DIRECTORY_SIMILARITY_REPORT_FAILED = R.string.language_similarity_report_failed
        private val MESSAGE_MERGE_COMICS_FAILED = R.string.language_merge_comics_failed
        private val MESSAGE_MERGE_COMICS_NO_ARCHIVES = R.string.language_merge_comics_no_archives
        private val MESSAGE_MERGE_COMICS_NO_IMAGES = R.string.language_merge_comics_no_images
        private val MESSAGE_MERGE_FILES_FAILED = R.string.language_merge_files_failed
        private val MESSAGE_MERGE_FILES_NO_FOLDERS = R.string.language_merge_files_no_folders
        private val MESSAGE_MERGE_FILES_NO_IMAGES = R.string.language_merge_files_no_images
    }
}
