package com.example.comiclab.ui.browser.transfer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.example.comiclab.AppSettings
import com.example.comiclab.MainActivity.FileOperationProgressViews
import com.example.comiclab.R
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileOperationProgress
import com.example.comiclab.core.transfer.FileOperationProgressPolicy
import com.example.comiclab.core.transfer.FileTransferOperations
import com.example.comiclab.ui.browser.FileListScrollState
import com.example.comiclab.ui.common.createRounded
import java.io.File
import java.util.concurrent.ExecutorService

/** Coordinates browser file and directory copy/cut flows using Activity-owned UI and executor resources. */
internal class FileTransferWorkflowController(
    private val activity: Activity,
    private val stateController: FileTransferController,
    private val executor: ExecutorService,
    private val showProgressCallback: () -> FileOperationProgressViews,
    private val createProgressReporterCallback: (FileOperationProgressViews) -> ((FileOperationProgress) -> Unit),
    private val dismissProgressCallback: (FileOperationProgressViews) -> Unit,
    private val currentPathProvider: () -> String,
    private val captureScrollState: () -> FileListScrollState?,
    private val loadDirectory: (FileListScrollState?) -> Unit,
    private val invalidateChildCount: (File?) -> Unit,
    private val showMessageCallback: (String) -> Unit,
    private val updateToolbarCallback: () -> Unit
) {
    private val pendingFileTransfer: PendingFileTransfer?
        get() = stateController.pendingTransfer

    private val isFileTransferInProgress: Boolean
        get() = stateController.isTransferInProgress

    private val currentPath: String
        get() = currentPathProvider()

    private val applicationContext: Context
        get() = activity.applicationContext

    private val layoutInflater
        get() = activity.layoutInflater

    private val isDestroyed: Boolean
        get() = activity.isDestroyed

    private val isFinishing: Boolean
        get() = activity.isFinishing

    private var progressViewsForTest: FileOperationProgressViews? = null

    private fun getString(resourceId: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) activity.getString(resourceId)
        else activity.getString(resourceId, *formatArgs)

    private fun showMessage(message: String) = showMessageCallback(message)

    private fun updateFileTransferToolbar() = updateToolbarCallback()

    private fun captureFileListScrollState(): FileListScrollState? = captureScrollState()

    private fun invalidateDirectoryChildCount(directory: File?) =
        invalidateChildCount(directory)

    private fun loadCurrentDirectory(scrollStateToRestore: FileListScrollState? = null) =
        loadDirectory(scrollStateToRestore)

    private fun runOnUiThread(action: () -> Unit) {
        activity.runOnUiThread { action() }
    }

    internal fun showFileOperationProgressForTest() {
        progressViewsForTest = showFileOperationProgress()
    }

    internal fun handleDirectoryTransferFailureForTest(
        transfer: PendingFileTransfer,
        result: DirectoryTransferOperations.Result,
        destinationDirectory: File
    ) {
        handleDirectoryTransferResult(
            transfer,
            result,
            destinationDirectory,
            null,
            checkNotNull(progressViewsForTest)
        )
    }

    fun beginFileTransfer(file: File, mode: FileTransferMode) {
        if (pendingFileTransfer != null || isFileTransferInProgress) {
            return
        }
        if (!file.isFile && !file.isDirectory) {
            showMessage(getString(R.string.file_transfer_source_missing))
            return
        }

        val transfer = PendingFileTransfer(file.absolutePath, mode, file.isDirectory)
        if (stateController.begin(transfer)) {
            updateFileTransferToolbar()
        }
    }

    fun pastePendingFile() {
        if (isFileTransferInProgress) {
            return
        }
        val transfer = pendingFileTransfer ?: return
        val sourceFile = File(transfer.sourcePath)
        val destinationDirectory = File(currentPath)
        val sourceExists = if (transfer.isDirectory) sourceFile.isDirectory else sourceFile.isFile
        if (!sourceExists) {
            showMessage(getString(R.string.file_transfer_source_missing))
            return
        }
        if (!destinationDirectory.isDirectory) {
            showMessage(getString(R.string.file_transfer_failed))
            return
        }

        if (transfer.isDirectory) {
            if (DirectoryTransferOperations.isDestinationInsideSource(
                    sourceFile,
                    destinationDirectory
                )
            ) {
                showMessage(getString(R.string.folder_paste_into_source))
                return
            }
            val targetDirectory = File(destinationDirectory, sourceFile.name)
            if (targetDirectory.exists()) {
                showPasteDirectoryNameDialog(transfer, destinationDirectory)
            } else {
                startDirectoryTransfer(transfer, destinationDirectory, sourceFile.name)
            }
            return
        }

        val targetFile = File(destinationDirectory, sourceFile.name)
        if (targetFile.exists() || targetFile.absoluteFile == sourceFile.absoluteFile) {
            showPasteFileNameDialog(transfer, destinationDirectory)
        } else {
            startFileTransfer(transfer, destinationDirectory, sourceFile.name)
        }
    }

    private fun showPasteDirectoryNameDialog(
        transfer: PendingFileTransfer,
        destinationDirectory: File
    ) {
        if (pendingFileTransfer != transfer || isFileTransferInProgress) {
            return
        }
        val sourceDirectory = File(transfer.sourcePath)
        if (!transfer.isDirectory || !sourceDirectory.isDirectory) {
            showMessage(getString(R.string.file_transfer_source_missing))
            return
        }

        val input = EditText(activity).apply {
            setSingleLine(true)
            setText(
                DirectoryTransferOperations.suggestedName(
                    sourceDirectory.name,
                    destinationDirectory
                )
            )
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.paste_folder_conflict_title)
            .setMessage(R.string.paste_folder_conflict_message)
            .setView(input)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .createRounded()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val targetName = DirectoryTransferOperations.targetDirectoryName(
                input.text?.toString().orEmpty()
            )
            if (targetName == null) {
                val errorMessage = if (input.text.isNullOrBlank()) {
                    getString(R.string.rename_directory_name_empty)
                } else {
                    getString(R.string.rename_directory_name_invalid)
                }
                input.error = errorMessage
                return@setOnClickListener
            }

            if (File(destinationDirectory, targetName).exists()) {
                input.error = getString(R.string.paste_folder_name_exists)
                return@setOnClickListener
            }

            dialog.dismiss()
            startDirectoryTransfer(transfer, destinationDirectory, targetName)
        }
    }

    private fun showPasteFileNameDialog(
        transfer: PendingFileTransfer,
        destinationDirectory: File
    ) {
        if (pendingFileTransfer != transfer || isFileTransferInProgress) {
            return
        }
        val sourceFile = File(transfer.sourcePath)
        if (transfer.isDirectory || !sourceFile.isFile) {
            showMessage(getString(R.string.file_transfer_source_missing))
            return
        }

        val input = EditText(activity).apply {
            setSingleLine(true)
            setText(FileTransferOperations.suggestedName(sourceFile.name, destinationDirectory))
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.paste_file_conflict_title)
            .setMessage(R.string.paste_file_conflict_message)
            .setView(input)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .createRounded()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val targetName = FileTransferOperations.targetFileName(
                sourceFile.name,
                input.text?.toString().orEmpty()
            )
            if (targetName == null) {
                val errorMessage = if (input.text.isNullOrBlank()) {
                    getString(R.string.rename_file_name_empty)
                } else {
                    getString(R.string.rename_file_name_invalid)
                }
                input.error = errorMessage
                return@setOnClickListener
            }

            if (File(destinationDirectory, targetName).exists()) {
                input.error = getString(R.string.paste_file_name_exists)
                return@setOnClickListener
            }

            dialog.dismiss()
            startFileTransfer(transfer, destinationDirectory, targetName)
        }
    }

    private fun showFileOperationProgress(): FileOperationProgressViews =
        showProgressCallback()

    private fun createFileOperationProgressReporter(
        views: FileOperationProgressViews
    ): (FileOperationProgress) -> Unit = createProgressReporterCallback(views)



    private fun dismissFileOperationProgress(views: FileOperationProgressViews) =
        dismissProgressCallback(views)

    fun startFileTransfer(
        transfer: PendingFileTransfer,
        destinationDirectory: File,
        targetName: String
    ) {
        if (pendingFileTransfer != transfer || isFileTransferInProgress) {
            return
        }
        val sourceFile = File(transfer.sourcePath)
        val targetFile = File(destinationDirectory, targetName)
        if (targetFile.exists()) {
            showPasteFileNameDialog(transfer, destinationDirectory)
            return
        }

        val scrollState = captureFileListScrollState()
        val wasFavorite = transfer.mode == FileTransferMode.CUT &&
            FavoriteComicStore.isFavorite(activity, sourceFile)
        val wasInReadingHistory = transfer.mode == FileTransferMode.CUT &&
            ReadingHistoryStore.items(activity).any { it.file.absolutePath == sourceFile.absolutePath }
        if (!stateController.startPaste(transfer)) {
            return
        }
        updateFileTransferToolbar()
        val progressViews = showFileOperationProgress()
        val reportProgress = createFileOperationProgressReporter(progressViews)
        executor.execute {
            val result = FileTransferOperations.transfer(
                sourceFile = sourceFile,
                targetFile = targetFile,
                moveSource = transfer.mode == FileTransferMode.CUT,
                onProgress = reportProgress
            )
            if (result == FileTransferOperations.Result.SUCCESS &&
                transfer.mode == FileTransferMode.CUT
            ) {
                FileTransferMetadataUpdatePolicy.applyFileCut(
                    updateFavorites = {
                        if (wasFavorite) {
                            FavoriteComicStore.remove(applicationContext, sourceFile)
                            FavoriteComicStore.record(applicationContext, targetFile)
                        }
                    },
                    updateReadingHistory = {
                        if (wasInReadingHistory) {
                            ReadingHistoryStore.remove(applicationContext, sourceFile)
                            ReadingHistoryStore.record(applicationContext, targetFile)
                        }
                    },
                    updateReaderProgress = {
                        ReaderProgressStore.moveFile(applicationContext, sourceFile, targetFile)
                    }
                )
            }
            if (result == FileTransferOperations.Result.SUCCESS ||
                result == FileTransferOperations.Result.SOURCE_DELETE_FAILED
            ) {
                invalidateDirectoryChildCount(destinationDirectory)
                if (result == FileTransferOperations.Result.SUCCESS &&
                    transfer.mode == FileTransferMode.CUT
                ) {
                    invalidateDirectoryChildCount(sourceFile.parentFile)
                }
            }
            runOnUiThread {
                dismissFileOperationProgress(progressViews)
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                stateController.finishPaste(
                    transfer,
                    result = result
                )
                updateFileTransferToolbar()
                when (result) {
                    FileTransferOperations.Result.SUCCESS -> {
                        showMessage(
                            getString(
                                if (transfer.mode == FileTransferMode.COPY) {
                                    R.string.file_copy_success
                                } else {
                                    R.string.file_cut_success
                                }
                            )
                        )
                        loadCurrentDirectory(scrollStateToRestore = scrollState)
                    }

                    FileTransferOperations.Result.TARGET_EXISTS -> {
                        showPasteFileNameDialog(transfer, destinationDirectory)
                    }

                    FileTransferOperations.Result.SOURCE_NOT_FILE -> {
                        showMessage(getString(R.string.file_transfer_source_missing))
                    }

                    FileTransferOperations.Result.SOURCE_DELETE_FAILED -> {
                        showMessage(getString(R.string.file_cut_source_delete_failed))
                    }

                    FileTransferOperations.Result.DESTINATION_NOT_DIRECTORY,
                    FileTransferOperations.Result.FAILED -> {
                        showMessage(getString(R.string.file_transfer_failed))
                    }
                }
            }
        }
    }

    private fun startDirectoryTransfer(
        transfer: PendingFileTransfer,
        destinationDirectory: File,
        targetName: String
    ) {
        if (pendingFileTransfer != transfer || isFileTransferInProgress) {
            return
        }
        val sourceDirectory = File(transfer.sourcePath)
        val targetDirectory = File(destinationDirectory, targetName)
        if (DirectoryTransferOperations.isDestinationInsideSource(
                sourceDirectory,
                destinationDirectory
            )
        ) {
            showMessage(getString(R.string.folder_paste_into_source))
            return
        }
        if (targetDirectory.exists()) {
            showPasteDirectoryNameDialog(transfer, destinationDirectory)
            return
        }

        val scrollState = captureFileListScrollState()
        if (!stateController.startPaste(transfer)) {
            return
        }
        updateFileTransferToolbar()
        val progressViews = showFileOperationProgress()
        val reportProgress = createFileOperationProgressReporter(progressViews)
        executor.execute {
            val result = DirectoryTransferOperations.transfer(
                sourceDirectory = sourceDirectory,
                destinationDirectory = destinationDirectory,
                targetName = targetName,
                moveSource = transfer.mode == FileTransferMode.CUT,
                onProgress = reportProgress
            )
            if (result == DirectoryTransferOperations.Result.SUCCESS &&
                transfer.mode == FileTransferMode.CUT
            ) {
                FileTransferMetadataUpdatePolicy.applyDirectoryCut(
                    updateFavoritePaths = {
                        FavoritePathStore.moveDirectory(
                            applicationContext,
                            sourceDirectory,
                            targetDirectory
                        )
                    },
                    updateFavoriteComics = {
                        FavoriteComicStore.moveDirectory(
                            applicationContext,
                            sourceDirectory,
                            targetDirectory
                        )
                    },
                    updateReadingHistory = {
                        ReadingHistoryStore.moveDirectory(
                            applicationContext,
                            sourceDirectory,
                            targetDirectory
                        )
                    },
                    updateBookcasePaths = {
                        AppSettings.renameBookcaseDirectory(
                            applicationContext,
                            sourceDirectory,
                            targetDirectory
                        )
                    },
                    updateReaderProgress = {
                        ReaderProgressStore.moveDirectory(
                            applicationContext,
                            sourceDirectory,
                            targetDirectory
                        )
                    }
                )
            }
            if (result == DirectoryTransferOperations.Result.SUCCESS ||
                result == DirectoryTransferOperations.Result.SOURCE_DELETE_FAILED
            ) {
                invalidateDirectoryChildCount(destinationDirectory)
                if (transfer.mode == FileTransferMode.CUT) {
                    invalidateDirectoryChildCount(sourceDirectory.parentFile)
                    if (result == DirectoryTransferOperations.Result.SOURCE_DELETE_FAILED) {
                        invalidateDirectoryChildCount(sourceDirectory)
                    }
                }
            }
            runOnUiThread {
                handleDirectoryTransferResult(
                    transfer = transfer,
                    result = result,
                    destinationDirectory = destinationDirectory,
                    scrollState = scrollState,
                    progressViews = progressViews
                )
            }
        }
    }

    private fun handleDirectoryTransferResult(
        transfer: PendingFileTransfer,
        result: DirectoryTransferOperations.Result,
        destinationDirectory: File,
        scrollState: FileListScrollState?,
        progressViews: FileOperationProgressViews
    ) {
        dismissFileOperationProgress(progressViews)
        if (isDestroyed || isFinishing) {
            return
        }
        stateController.finishPaste(transfer, result)
        updateFileTransferToolbar()
        when (result) {
            DirectoryTransferOperations.Result.SUCCESS -> {
                showMessage(
                    getString(
                        if (transfer.mode == FileTransferMode.COPY) {
                            R.string.folder_copy_success
                        } else {
                            R.string.folder_cut_success
                        }
                    )
                )
                loadCurrentDirectory(scrollStateToRestore = scrollState)
            }

            DirectoryTransferOperations.Result.TARGET_EXISTS -> {
                showPasteDirectoryNameDialog(transfer, destinationDirectory)
            }

            DirectoryTransferOperations.Result.SOURCE_NOT_DIRECTORY -> {
                showMessage(getString(R.string.file_transfer_source_missing))
            }

            DirectoryTransferOperations.Result.DESTINATION_INSIDE_SOURCE -> {
                showMessage(getString(R.string.folder_paste_into_source))
            }

            DirectoryTransferOperations.Result.SOURCE_DELETE_FAILED -> {
                showMessage(getString(R.string.folder_cut_source_delete_failed))
                loadCurrentDirectory(scrollStateToRestore = scrollState)
            }

            DirectoryTransferOperations.Result.DESTINATION_NOT_DIRECTORY,
            DirectoryTransferOperations.Result.INVALID_TARGET_NAME,
            DirectoryTransferOperations.Result.FAILED -> {
                showMessage(getString(R.string.file_transfer_failed))
            }
        }
    }

    fun exitPendingFileTransfer() {
        if (stateController.exit()) updateFileTransferToolbar()
    }

    private companion object {
        const val PROGRESS_UI_UPDATE_INTERVAL_MS = 120L
    }
}
