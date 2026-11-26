package com.example.comiclab.ui.browser.transfer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.MainThread
import com.example.comiclab.AppSettings
import com.example.comiclab.MainActivity.FileOperationProgressViews
import com.example.comiclab.R
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileOperationProgress
import com.example.comiclab.core.transfer.FileOperationProgressPolicy
import com.example.comiclab.core.transfer.FileTransferOperations
import com.example.comiclab.ui.browser.FileListScrollState
import com.example.comiclab.ui.common.createRounded
import com.example.comiclab.ui.privacy.PrivacyAccessGuard
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

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
    companion object {
        // The executor can finish an accepted paste after its Activity is destroyed.
        private val pasteInProgress = AtomicBoolean(false)
        private val detachedNormalPasteListeners = mutableSetOf<(List<File>) -> Unit>()

        @MainThread
        fun addDetachedNormalPasteListener(listener: (List<File>) -> Unit) {
            detachedNormalPasteListeners.add(listener)
        }

        @MainThread
        fun removeDetachedNormalPasteListener(listener: (List<File>) -> Unit) {
            detachedNormalPasteListeners.remove(listener)
        }
    }

    private class ActivePaste(
        val progress: FileOperationProgressViews,
        val pathCheck: () -> Boolean,
        val reportProgress: (FileOperationProgress) -> Unit
    )

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
    private var activePaste: ActivePaste? = null
    private var conflictDialog: AlertDialog? = null
    private var conflictIsPrivate = false

    private fun getString(resourceId: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) activity.getString(resourceId)
        else activity.getString(resourceId, *formatArgs)

    private fun showMessage(message: String) = showMessageCallback(message)

    private fun updateFileTransferToolbar() = updateToolbarCallback()

    private fun captureFileListScrollState(): FileListScrollState? = captureScrollState()

    private fun invalidateDirectoryChildCount(directory: File?) =
        invalidateChildCount(directory)

    private fun invalidatePasteDirectories(
        transfer: PendingFileTransfer,
        destination: File,
        sourceMayHaveChanged: Boolean
    ): List<File> {
        val movedSource = File(transfer.sourcePath).takeIf {
            transfer.mode == FileTransferMode.CUT && sourceMayHaveChanged
        }
        return listOfNotNull(destination, movedSource?.parentFile, movedSource?.takeIf { transfer.isDirectory })
            .distinct()
            .also { directories -> directories.forEach(::invalidateDirectoryChildCount) }
    }

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

        val privateEpoch = PrivacySessionManager.sessionEpoch.takeIf { PrivacySessionManager.isInPrivacySpace }
        val transfer = PendingFileTransfer(file.absolutePath, mode, file.isDirectory, privateEpoch)
        sourceRestriction(transfer)?.let { restriction ->
            showRestriction(restriction, privateEpoch != null)
            return
        }
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
        if (!canStartAt(transfer, destinationDirectory, File(destinationDirectory, sourceFile.name))) return
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
        if (!canStartAt(transfer, destinationDirectory, File(destinationDirectory, sourceDirectory.name))) return
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
        showConflict(dialog, transfer)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (conflictDialog !== dialog || !dialog.isShowing || !matchesMode(transfer)) return@setOnClickListener
            val targetName = input.text?.toString().orEmpty().trim()
            val nameErrorMessage = when (DirectoryTransferOperations.directoryNameError(targetName)) {
                null -> null
                DirectoryTransferOperations.CreateDirectoryResult.EMPTY_NAME -> R.string.rename_directory_name_empty
                DirectoryTransferOperations.CreateDirectoryResult.HIDDEN_NAME -> R.string.directory_name_hidden
                else -> R.string.rename_directory_name_invalid
            }
            if (nameErrorMessage != null) {
                input.error = getString(nameErrorMessage)
                return@setOnClickListener
            }

            val target = File(destinationDirectory, targetName)
            if (!canStartAt(transfer, destinationDirectory, target)) return@setOnClickListener
            if (target.exists()) {
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
        if (!canStartAt(transfer, destinationDirectory, File(destinationDirectory, sourceFile.name))) return
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
        showConflict(dialog, transfer)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (conflictDialog !== dialog || !dialog.isShowing || !matchesMode(transfer)) return@setOnClickListener
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

            val target = File(destinationDirectory, targetName)
            if (!canStartAt(transfer, destinationDirectory, target)) return@setOnClickListener
            if (target.exists()) {
                input.error = getString(R.string.paste_file_name_exists)
                return@setOnClickListener
            }

            dialog.dismiss()
            startFileTransfer(transfer, destinationDirectory, targetName)
        }
    }

    private fun matchesMode(transfer: PendingFileTransfer): Boolean = transfer.privacyEpoch?.let {
        PrivacySessionManager.isCurrentSession(it)
    } ?: !PrivacySessionManager.isInPrivacySpace

    private fun sourceRestriction(transfer: PendingFileTransfer): Int? {
        val file = File(transfer.sourcePath)
        val privateMode = transfer.privacyEpoch != null
        return if (transfer.isDirectory) PrivacyAccessGuard.directoryChangeRestriction(applicationContext, file, privateMode)
        else R.string.privacy_operation_denied.takeIf {
            PrivacyAccessGuard.resolveLocalPathInMode(applicationContext, file, privateMode) == null
        }
    }

    private fun destinationAllowed(transfer: PendingFileTransfer, destination: File, target: File): Boolean =
        PrivacyAccessGuard.allowsLocalChild(applicationContext, destination, target, transfer.privacyEpoch != null) &&
            (!transfer.isDirectory || !PrivacyStore.hasOwnOrDescendantPrivacyMark(applicationContext, target.path))

    private fun capturePathCheck(transfer: PendingFileTransfer, destination: File, target: File): () -> Boolean {
        val source = File(transfer.sourcePath)
        val approvedSource = PrivacyAccessGuard.resolveLocalPath(source)
        val approvedTarget = PrivacyAccessGuard.resolveLocalPath(target)
        return {
            approvedSource != null && approvedTarget != null &&
                PrivacyAccessGuard.resolveLocalPath(source) == approvedSource &&
                PrivacyAccessGuard.resolveLocalPath(target) == approvedTarget &&
                sourceRestriction(transfer) == null && destinationAllowed(transfer, destination, target)
        }
    }

    private fun canStartAt(transfer: PendingFileTransfer, destination: File, target: File): Boolean {
        val restriction = sourceRestriction(transfer) ?: R.string.privacy_operation_denied.takeIf {
            !matchesMode(transfer) || !destinationAllowed(transfer, destination, target)
        }
        if (restriction == null) return true
        showMessage(getString(restriction))
        return false
    }

    private fun showRestriction(message: Int, privateMode: Boolean) {
        showTrackedDialog(AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .createRounded(), privateMode)
    }

    private fun showConflict(dialog: AlertDialog, transfer: PendingFileTransfer) =
        showTrackedDialog(dialog, transfer.privacyEpoch != null)

    private fun showTrackedDialog(dialog: AlertDialog, privateMode: Boolean) {
        dismissConflict()
        conflictDialog = dialog
        conflictIsPrivate = privateMode
        if (privateMode) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { if (conflictDialog === dialog) conflictDialog = null }
        dialog.show()
    }

    private fun dismissConflict() {
        val dialog = conflictDialog ?: return
        conflictDialog = null
        if (dialog.isShowing) dialog.window?.decorView?.visibility = View.INVISIBLE
        dialog.dismiss()
    }

    fun onPrivacyLocked() {
        if (conflictIsPrivate) dismissConflict()
        if (pendingFileTransfer?.privacyEpoch == null) return
        activePaste?.progress?.let(::dismissFileOperationProgress)
        if (stateController.exit()) updateFileTransferToolbar()
    }

    fun onPrivacyMarksChanged() {
        dismissConflict()
        activePaste?.let { if (!it.pathCheck()) dismissFileOperationProgress(it.progress) }
        val transfer = pendingFileTransfer ?: return
        if (sourceRestriction(transfer) != null && stateController.exit()) updateFileTransferToolbar()
    }

    fun dismissDialogs() {
        dismissConflict()
        activePaste?.progress?.let(::dismissFileOperationProgress)
    }

    private fun beginPaste(
        transfer: PendingFileTransfer,
        pathCheck: () -> Boolean,
        finishFailed: () -> Boolean
    ): ActivePaste? {
        if (!pasteInProgress.compareAndSet(false, true)) {
            showMessage(getString(R.string.file_transfer_in_progress))
            return null
        }
        if (!stateController.startPaste(transfer)) {
            pasteInProgress.set(false)
            return null
        }
        var progress: FileOperationProgressViews? = null
        try {
            val views = showFileOperationProgress()
            progress = views
            val paste = ActivePaste(views, pathCheck, createFileOperationProgressReporter(views))
            updateFileTransferToolbar()
            activePaste = paste
            return paste
        } catch (failure: RuntimeException) {
            try {
                finishFailed()
                progress?.let(::dismissFileOperationProgress)
            } finally {
                pasteInProgress.set(false)
            }
            throw failure
        }
    }

    private fun completePaste(
        transfer: PendingFileTransfer,
        progress: FileOperationProgressViews,
        pathCheck: () -> Boolean,
        changedDirectories: List<File> = emptyList(),
        finish: () -> Boolean
    ): Boolean {
        val ownsPaste = activePaste?.progress === progress
        val modeIsCurrent: Boolean
        val scopeIsValid: Boolean
        try {
            if (!finish()) return false
            modeIsCurrent = matchesMode(transfer)
            scopeIsValid = modeIsCurrent && runCatching(pathCheck).getOrDefault(false)
            val discardPendingSource = (transfer.privacyEpoch != null && !modeIsCurrent) ||
                runCatching { sourceRestriction(transfer) != null }.getOrDefault(true)
            if (discardPendingSource && pendingFileTransfer == transfer) stateController.exit()
        } finally {
            try {
                dismissFileOperationProgress(progress)
            } finally {
                if (ownsPaste) {
                    activePaste = null
                    pasteInProgress.set(false)
                }
            }
        }
        if (isDestroyed || isFinishing) {
            if (transfer.privacyEpoch == null && changedDirectories.isNotEmpty()) {
                detachedNormalPasteListeners.toList().forEach { it(changedDirectories) }
            }
            return false
        }
        updateFileTransferToolbar()
        if (!scopeIsValid && modeIsCurrent && transfer.privacyEpoch == null) {
            showMessage(getString(R.string.privacy_operation_denied))
        }
        return scopeIsValid
    }

    private fun enqueue(onFailure: () -> Unit, task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (_: Exception) {
                    runOnUiThread(onFailure)
                }
            }
        } catch (_: RejectedExecutionException) {
            onFailure()
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
        if (transfer.isDirectory || !FileTransferOperations.isValidFileName(targetName) ||
            !canStartAt(transfer, destinationDirectory, targetFile)
        ) return
        if (targetFile.exists()) {
            showPasteFileNameDialog(transfer, destinationDirectory)
            return
        }

        val scrollState = captureFileListScrollState()
        val wasFavorite = transfer.mode == FileTransferMode.CUT &&
            FavoriteComicStore.isFavorite(activity, sourceFile)
        val wasInReadingHistory = transfer.mode == FileTransferMode.CUT &&
            ReadingHistoryStore.items(activity).any { it.file.absolutePath == sourceFile.absolutePath }
        val pathCheck = capturePathCheck(transfer, destinationDirectory, targetFile)
        val finishFailed = { stateController.finishPaste(transfer, FileTransferOperations.Result.FAILED) }
        val paste = beginPaste(transfer, pathCheck, finishFailed) ?: return
        val progressViews = paste.progress
        enqueue(onFailure = {
            if (completePaste(transfer, progressViews, pathCheck, finish = finishFailed)) {
                showMessage(getString(R.string.file_transfer_failed))
            }
        }) {
            val updateEbookProgress = if (transfer.mode == FileTransferMode.CUT && pathCheck()) {
                runCatching { EbookProgressStore.prepareMove(applicationContext, sourceFile) }.getOrNull()
            } else null
            val result = runCatching {
                FileTransferOperations.transfer(
                    sourceFile = sourceFile,
                    targetFile = targetFile,
                    moveSource = transfer.mode == FileTransferMode.CUT,
                    canContinue = pathCheck,
                    onProgress = paste.reportProgress
                )
            }.getOrDefault(FileTransferOperations.Result.FAILED)
            if (result == FileTransferOperations.Result.SUCCESS &&
                transfer.mode == FileTransferMode.CUT
            ) {
                runCatching { updateEbookProgress?.invoke(targetFile) }
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
            val changedDirectories = if (result == FileTransferOperations.Result.SUCCESS ||
                result == FileTransferOperations.Result.COPIED_SOURCE_RETAINED ||
                result == FileTransferOperations.Result.COPY_INCOMPLETE
            ) {
                invalidatePasteDirectories(transfer, destinationDirectory, result == FileTransferOperations.Result.SUCCESS)
            } else emptyList()
            runOnUiThread {
                val showResult = completePaste(transfer, progressViews, pathCheck, changedDirectories) {
                    stateController.finishPaste(transfer, result)
                }
                if (!showResult) return@runOnUiThread
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

                    FileTransferOperations.Result.COPIED_SOURCE_RETAINED -> {
                        showMessage(getString(R.string.file_transfer_copied_source_retained))
                        loadCurrentDirectory(scrollStateToRestore = scrollState)
                    }

                    FileTransferOperations.Result.COPY_INCOMPLETE -> {
                        showMessage(getString(R.string.file_transfer_copy_incomplete))
                        loadCurrentDirectory(scrollStateToRestore = scrollState)
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
        if (!transfer.isDirectory || DirectoryTransferOperations.directoryNameError(targetName) != null ||
            !canStartAt(transfer, destinationDirectory, targetDirectory)
        ) return
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
        val pathCheck = capturePathCheck(transfer, destinationDirectory, targetDirectory)
        val paste = beginPaste(transfer, pathCheck) {
            stateController.finishPaste(transfer, DirectoryTransferOperations.Result.FAILED)
        } ?: return
        val progressViews = paste.progress
        enqueue(onFailure = {
            handleDirectoryTransferResult(transfer, DirectoryTransferOperations.Result.FAILED, destinationDirectory, scrollState, progressViews, pathCheck)
        }) {
            val updateEbookProgress = if (transfer.mode == FileTransferMode.CUT && pathCheck()) {
                runCatching { EbookProgressStore.prepareMove(applicationContext, sourceDirectory) }.getOrNull()
            } else null
            val result = runCatching {
                DirectoryTransferOperations.transfer(
                    sourceDirectory = sourceDirectory,
                    destinationDirectory = destinationDirectory,
                    targetName = targetName,
                    moveSource = transfer.mode == FileTransferMode.CUT,
                    canContinue = pathCheck,
                    onProgress = paste.reportProgress
                )
            }.getOrDefault(DirectoryTransferOperations.Result.FAILED)
            if (result == DirectoryTransferOperations.Result.SUCCESS &&
                transfer.mode == FileTransferMode.CUT
            ) {
                runCatching { updateEbookProgress?.invoke(targetDirectory) }
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
            val changedDirectories = if (result == DirectoryTransferOperations.Result.SUCCESS ||
                result == DirectoryTransferOperations.Result.COPIED_SOURCE_RETAINED ||
                result == DirectoryTransferOperations.Result.COPY_INCOMPLETE
            ) {
                invalidatePasteDirectories(transfer, destinationDirectory, result != DirectoryTransferOperations.Result.COPY_INCOMPLETE)
            } else emptyList()
            runOnUiThread {
                handleDirectoryTransferResult(
                    transfer = transfer,
                    result = result,
                    destinationDirectory = destinationDirectory,
                    scrollState = scrollState,
                    progressViews = progressViews,
                    pathCheck = pathCheck,
                    changedDirectories = changedDirectories
                )
            }
        }
    }

    private fun handleDirectoryTransferResult(
        transfer: PendingFileTransfer,
        result: DirectoryTransferOperations.Result,
        destinationDirectory: File,
        scrollState: FileListScrollState?,
        progressViews: FileOperationProgressViews,
        pathCheck: () -> Boolean = capturePathCheck(transfer, destinationDirectory, File(destinationDirectory, File(transfer.sourcePath).name)),
        changedDirectories: List<File> = emptyList()
    ) {
        val showResult = completePaste(transfer, progressViews, pathCheck, changedDirectories) {
            stateController.finishPaste(transfer, result)
        }
        if (!showResult) return
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

            DirectoryTransferOperations.Result.COPIED_SOURCE_RETAINED -> {
                showMessage(getString(R.string.file_transfer_copied_source_retained))
                loadCurrentDirectory(scrollStateToRestore = scrollState)
            }

            DirectoryTransferOperations.Result.COPY_INCOMPLETE -> {
                showMessage(getString(R.string.file_transfer_copy_incomplete))
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
        if (stateController.exit()) {
            dismissConflict()
            updateFileTransferToolbar()
        }
    }
}
