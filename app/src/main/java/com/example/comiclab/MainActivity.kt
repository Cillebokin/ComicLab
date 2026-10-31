package com.example.comiclab

import com.example.comiclab.ui.browser.organize.ComicOrganizeController
import com.example.comiclab.ui.browser.refresh.DirectoryRefreshController
import com.example.comiclab.core.concurrent.LatestTaskDispatcher
import com.example.comiclab.ui.browser.toolbar.RoundedPopupMenuItem
import com.example.comiclab.ui.browser.toolbar.SortAndSearchController
import com.example.comiclab.ui.browser.toolbar.showRoundedPopupMenu
import com.example.comiclab.ui.browser.panels.SidePanelController
import com.example.comiclab.ui.common.SystemBars
import com.example.comiclab.ui.common.createRounded
import com.example.comiclab.ui.common.showRounded
import com.example.comiclab.ui.common.showRoundedContent

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.PrefsGateway
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.core.nas.NasDirectoryEntry
import com.example.comiclab.core.nas.NasShareArchiveDownloader
import com.example.comiclab.core.nas.NasShareDirectoryBrowser
import com.example.comiclab.core.nas.NasShareProfile
import com.example.comiclab.core.nas.NasShareStore
import com.example.comiclab.ebook.ReaderFileDetector
import com.example.comiclab.ebook.ReaderFileType
import com.example.comiclab.ui.browser.FileListAdapter
import com.example.comiclab.ui.browser.NasFileListAdapter
import com.example.comiclab.ui.reader.enhance.ArchiveEnhancementDialog
import com.example.comiclab.ui.browser.DirectoryBrowserController
import com.example.comiclab.ui.browser.DirectoryChildCountCache
import com.example.comiclab.ui.browser.DirectoryScrollStateCache
import com.example.comiclab.ui.browser.FileListScrollState
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.ui.browser.transfer.FileTransferController
import com.example.comiclab.ui.browser.transfer.FileTransferWorkflowController
import com.example.comiclab.ui.browser.transfer.FileTransferMetadataUpdatePolicy
import com.example.comiclab.ui.browser.transfer.FileTransferMode
import com.example.comiclab.ui.browser.transfer.PendingFileTransfer
import com.example.comiclab.core.transfer.DirectoryFileTree
import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileOperationProgress
import com.example.comiclab.core.transfer.FileOperationProgressPolicy
import com.example.comiclab.core.transfer.FileTransferOperations
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    internal data class FileOperationProgressViews(
        val dialog: AlertDialog,
        val status: TextView,
        val progressBar: ProgressBar,
        val count: TextView
    )

    private lateinit var listView: ListView
    private lateinit var etPath: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnHelp: ImageButton
    private lateinit var btnOptions: ImageButton
    private lateinit var btnSort: ImageButton
    private lateinit var btnClassify: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnReadingHistory: ImageButton
    private lateinit var btnFavoriteComics: ImageButton
    private lateinit var btnFavoritePaths: ImageButton
    private lateinit var btnPasteFile: ImageButton
    private lateinit var btnCreateTransferDirectory: ImageButton
    private lateinit var btnExitFileTransfer: ImageButton
    private lateinit var btnTransferParent: ImageButton
    private lateinit var readingHistoryScrim: View
    private lateinit var nasPathStateSink: TextView
    private lateinit var nasEmptyState: View
    private lateinit var nasEmptyPlaceholder: View
    private lateinit var nasBrowserContent: View
    private lateinit var nasEmptyTitle: TextView
    private lateinit var nasEmptyMessage: TextView
    private lateinit var btnNasManageShares: Button
    private lateinit var nasDirectoryListView: ListView
    private lateinit var nasLoadingPanel: View
    private lateinit var nasStatusPanel: View
    private lateinit var tvNasBrowserStatus: TextView
    private lateinit var tvNasEmptyHint: TextView
    private lateinit var btnNasRetry: Button
    private lateinit var btnNasCacheManagement: TextView
    private lateinit var btnNasCacheManagementEmpty: TextView
    private lateinit var btnNasSwitchShare: TextView
    private lateinit var sourceSwitchDock: View
    private lateinit var sourceSwitchTrack: View
    private lateinit var sourceSwitchThumb: View
    private lateinit var sourceNasButton: View
    private lateinit var sourceLocalButton: View
    private lateinit var sourceNasIcon: ImageView
    private lateinit var sourceLocalIcon: ImageView
    private lateinit var sourceNasLabel: TextView
    private lateinit var sourceLocalLabel: TextView
    private lateinit var fileListAdapter: FileListAdapter
    private lateinit var nasFileListAdapter: NasFileListAdapter

    private val fileItems = mutableListOf<FileItem>()
    private val nasDirectoryItems = mutableListOf<NasDirectoryEntry>()
    private val directoryLoadExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    )
    private val latestDirectoryLoadDispatcher = LatestTaskDispatcher(directoryLoadExecutor)
    private val directoryChildCountExecutor = Executors.newSingleThreadExecutor()
    private val directoryChildCountCache = DirectoryChildCountCache()
    private val directoryScrollStateCache = DirectoryScrollStateCache()
    private val pendingDirectoryChildCountPaths = Collections.synchronizedSet(mutableSetOf<String>())
    private val classifyExecutor = Executors.newSingleThreadExecutor()
    private val fileTransferExecutor = Executors.newSingleThreadExecutor()
    private val nasDirectoryExecutor = Executors.newSingleThreadExecutor()
    private val nasDownloadExecutor = Executors.newSingleThreadExecutor()
    private val nasCacheExecutor = Executors.newSingleThreadExecutor()
    private val directoryLoadGeneration = AtomicInteger(0)
    private val nasDirectoryLoadGeneration = AtomicInteger(0)
    private val directoryBrowserController = DirectoryBrowserController(
        STORAGE_ROOT_PATH,
        latestDirectoryLoadDispatcher,
        directoryChildCountExecutor,
        directoryLoadGeneration,
        directoryChildCountCache,
        directoryScrollStateCache,
        pendingDirectoryChildCountPaths
    )
    private val classifyGeneration = AtomicInteger(0)
    private val sidePanelController = SidePanelController(
        activity = this,
        scrimProvider = { readingHistoryScrim },
        onOpenMangaPreview = { file -> openMangaPreview(file) },
        onJumpToDirectory = { path -> openDirectoryContainingTarget(path) },
        onNavigateToDirectory = { path ->
            setCurrentPath(path)
            loadCurrentDirectory()
        },
        onListChanged = { notifyListChanged() }
    )
    private val comicOrganizeController = ComicOrganizeController(
        activity = this,
        classifyExecutor = classifyExecutor,
        classifyGeneration = classifyGeneration,
        currentPathProvider = { currentPath },
        openManageAllFilesAccessSettings = { openManageAllFilesAccessSettings() },
        captureFileListScrollState = { captureFileListScrollState() },
        showMessageText = { message -> showMessage(message) },
        reloadCurrentDirectory = { loadCurrentDirectory() },
        reloadCurrentDirectoryWithScrollState = { state -> loadCurrentDirectory(scrollStateToRestore = state) }
    )
    private val sortAndSearchController = SortAndSearchController(
        activity = this,
        prefsProvider = { prefs },
        sortAnchorProvider = { btnSort },
        currentPathProvider = { currentPath },
        openManageAllFilesAccessSettings = { openManageAllFilesAccessSettings() },
        reloadCurrentDirectory = { loadCurrentDirectory() }
    )
    private val directoryRefreshController = DirectoryRefreshController(
        activity = this,
        listViewOrNull = { if (::listView.isInitialized) listView else null },
        pathInputProvider = { if (isNasSourceSelected) nasPathStateSink else etPath },
        fileItems = fileItems,
        fileListAdapterProvider = { fileListAdapter },
        directoryBrowserController = directoryBrowserController,
        directoryLoadGeneration = directoryLoadGeneration,
        sortOrderProvider = { sortAndSearchController.currentDirectorySortOrder() },
        currentPathProvider = { currentPath },
        applyResolvedPath = { path ->
            currentPath = path
            saveCurrentPath()
        },
        notifyListChanged = { notifyListChanged() }
    )
    private var browserInitialized = false
    private var skipNextResumeDirectoryReload = false
    private var pendingCenterTargetPath: String? = null
    private var isNasSourceSelected = false
    private var sourceTransitionGeneration = 0
    private var currentPath = STORAGE_ROOT_PATH
    private var currentNasRelativePath = ""
    private var selectedNasProfile: NasShareProfile? = null
    private var availableNasProfiles: List<NasShareProfile> = emptyList()
    private var nasDirectoryLoadInProgress = false
    private var nasDownloadInProgress = false
    private var nasDownloadGeneration = 0
    private var nasDownloadCancellation: AtomicBoolean? = null
    private var nasDownloadProgressDialog: AlertDialog? = null
    private var nasDownloadProgressStatus: TextView? = null
    private var nasDownloadProgressBar: ProgressBar? = null
    private var nasDownloadProgressCount: TextView? = null
    private var nasDownloadLastProgressUpdateAt = 0L
    private var nasCacheManagementInProgress = false
    private var nasCacheClearDialog: AlertDialog? = null
    private var nasCachedArchivePickerDialog: AlertDialog? = null
    private var nasCachedArchiveActionDialog: AlertDialog? = null
    private var nasCacheDeleteConfirmDialog: AlertDialog? = null
    private var isNasProfileChooserShowing = false

    private sealed interface NasDirectoryLoadResult {
        data class Loaded(val entries: List<NasDirectoryEntry>) : NasDirectoryLoadResult
        data object PasswordUnavailable : NasDirectoryLoadResult
        data object Failed : NasDirectoryLoadResult
    }

    private sealed interface NasDownloadResult {
        data class Completed(val file: File) : NasDownloadResult
        data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : NasDownloadResult
        data object Cancelled : NasDownloadResult
        data class Failed(val exception: Exception) : NasDownloadResult
    }

    private sealed interface NasCacheClearOutcome {
        data class Completed(val result: NasShareArchiveDownloader.CacheClearResult) : NasCacheClearOutcome
        data class Failed(val exception: Exception) : NasCacheClearOutcome
    }

    private sealed interface NasCachedArchiveDeleteOutcome {
        data class Completed(val bytesFreed: Long) : NasCachedArchiveDeleteOutcome
        data class Failed(val exception: Exception) : NasCachedArchiveDeleteOutcome
    }

    private class NasSavedPasswordUnavailableException : Exception()

    private val fileTransferController = FileTransferController()
    private val pendingFileTransfer: PendingFileTransfer?
        get() = fileTransferController.pendingTransfer
    private val isFileTransferInProgress: Boolean
        get() = fileTransferController.isTransferInProgress

    private val fileTransferWorkflowController by lazy(LazyThreadSafetyMode.NONE) {
        FileTransferWorkflowController(
            activity = this,
            stateController = fileTransferController,
            executor = fileTransferExecutor,
            showProgressCallback = { showFileOperationProgress() },
            createProgressReporterCallback = { views ->
                createFileOperationProgressReporter(views)
            },
            dismissProgressCallback = { views -> dismissFileOperationProgress(views) },
            currentPathProvider = { currentPath },
            captureScrollState = { captureFileListScrollState() },
            loadDirectory = { scrollState ->
                loadCurrentDirectory(scrollStateToRestore = scrollState)
            },
            invalidateChildCount = { directory -> invalidateDirectoryChildCount(directory) },
            showMessageCallback = { message -> showMessage(message) },
            updateToolbarCallback = { updateFileTransferToolbar() }
        )
    }

    private val prefs by lazy {
        PrefsGateway.open(this, PREFS_NAME)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground),
            statusBarColorResId = R.color.comiclab_file_picker_background,
            lightStatusBars = true
        )

        listView = findViewById(R.id.listFiles)
        etPath = findViewById(R.id.etPath)
        btnBack = findViewById(R.id.btnBack)
        btnHelp = findViewById(R.id.btnHelp)
        btnOptions = findViewById(R.id.btnOptions)
        btnSort = findViewById(R.id.btnSort)
        btnClassify = findViewById(R.id.btnClassify)
        btnSearch = findViewById(R.id.btnSearch)
        btnReadingHistory = findViewById(R.id.btnReadingHistory)
        btnFavoriteComics = findViewById(R.id.btnFavoriteComics)
        btnFavoritePaths = findViewById(R.id.btnFavoritePaths)
        btnPasteFile = findViewById(R.id.btnPasteFile)
        btnCreateTransferDirectory = findViewById(R.id.btnCreateTransferDirectory)
        btnExitFileTransfer = findViewById(R.id.btnExitFileTransfer)
        btnTransferParent = findViewById(R.id.btnTransferParent)
        readingHistoryScrim = findViewById(R.id.readingHistoryScrim)
        nasPathStateSink = findViewById(R.id.nasPathStateSink)
        nasEmptyState = findViewById(R.id.nasEmptyState)
        nasEmptyPlaceholder = findViewById(R.id.nasEmptyPlaceholder)
        nasBrowserContent = findViewById(R.id.nasBrowserContent)
        nasEmptyTitle = findViewById(R.id.tvNasEmptyTitle)
        nasEmptyMessage = findViewById(R.id.tvNasEmptyMessage)
        btnNasManageShares = findViewById(R.id.btnNasManageShares)
        nasDirectoryListView = findViewById(R.id.listNasFiles)
        nasLoadingPanel = findViewById(R.id.nasLoadingPanel)
        nasStatusPanel = findViewById(R.id.nasStatusPanel)
        tvNasBrowserStatus = findViewById(R.id.tvNasBrowserStatus)
        tvNasEmptyHint = findViewById(R.id.tvNasEmptyHint)
        btnNasRetry = findViewById(R.id.btnNasRetry)
        btnNasCacheManagement = findViewById(R.id.btnNasCacheManagement)
        btnNasCacheManagementEmpty = findViewById(R.id.btnNasCacheManagementEmpty)
        btnNasSwitchShare = findViewById(R.id.btnNasSwitchShare)
        sourceSwitchDock = findViewById(R.id.sourceSwitchDock)
        sourceSwitchTrack = findViewById(R.id.sourceSwitchTrack)
        sourceSwitchThumb = findViewById(R.id.sourceSwitchThumb)
        sourceNasButton = findViewById(R.id.btnSourceNas)
        sourceLocalButton = findViewById(R.id.btnSourceLocal)
        sourceNasIcon = findViewById(R.id.ivSourceNasIcon)
        sourceLocalIcon = findViewById(R.id.ivSourceLocalIcon)
        sourceNasLabel = findViewById(R.id.tvSourceNasLabel)
        sourceLocalLabel = findViewById(R.id.tvSourceLocalLabel)
        restorePendingFileTransfer(savedInstanceState)

        fileListAdapter = FileListAdapter(
            context = this,
            items = fileItems,
            onCollectionCoverClick = { directory ->
                setCurrentPath(directory.absolutePath)
                loadCurrentDirectory()
            },
            onDirectoryLongClick = { directory ->
                showDirectoryMenu(directory)
                clearFileListTouchState()
            },
            onDirectoryChildCountRequested = directoryRefreshController::requestDirectoryChildCount
        )
        listView.adapter = fileListAdapter
        nasFileListAdapter = NasFileListAdapter(this, nasDirectoryItems)
        nasDirectoryListView.adapter = nasFileListAdapter
        nasDirectoryListView.setOnItemClickListener { _, view, position, _ ->
            val entry = nasDirectoryItems.getOrNull(position)
                ?: return@setOnItemClickListener
            when {
                entry.isParent -> goParent()
                entry.isDirectory -> {
                    currentNasRelativePath = entry.relativePath
                    loadNasDirectory()
                }
                else -> startNasArchiveDownload(entry)
            }
            clearNasListTouchState(view)
        }
        sourceNasButton.setOnClickListener { selectFileSource(isNas = true) }
        sourceLocalButton.setOnClickListener { selectFileSource(isNas = false) }
        btnNasManageShares.setOnClickListener { openNasShareSettings() }
        btnNasSwitchShare.setOnClickListener {
            showNasProfilePicker(availableNasProfiles)
        }
        btnNasCacheManagement.setOnClickListener { showNasCacheManagementDialog() }
        btnNasCacheManagementEmpty.setOnClickListener { showNasCacheManagementDialog() }
        btnNasRetry.setOnClickListener { loadNasDirectory() }
        updateNasCacheSummary()
        updateSourceSwitchAppearance(isNas = false, animate = false)
        refreshNasFeatureAvailability()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleSystemBack()
            }
        })

        btnBack.setOnClickListener {
            if (!isFileTransferInProgress) {
                goParent()
            }
        }

        btnPasteFile.setOnClickListener {
            pastePendingFile()
        }

        btnCreateTransferDirectory.setOnClickListener {
            if (pendingFileTransfer != null && !isFileTransferInProgress) {
                showCreateDirectoryDialog()
            }
        }

        btnExitFileTransfer.setOnClickListener {
            exitPendingFileTransfer()
        }

        btnTransferParent.setOnClickListener {
            if (!isFileTransferInProgress) {
                goParent()
            }
        }

        btnOptions.setOnClickListener {
            openSettings()
        }

        btnHelp.setOnClickListener {
            openHelp()
        }

        btnReadingHistory.setOnClickListener {
            sidePanelController.showReadingHistoryPanel()
        }

        readingHistoryScrim.setOnClickListener {
            sidePanelController.dismissSidePanels()
        }

        btnFavoriteComics.setOnClickListener {
            sidePanelController.showFavoriteComicsPanel()
        }

        btnFavoritePaths.setOnClickListener {
            sidePanelController.showFavoritePathsPanel()
        }

        btnSort.setOnClickListener {
            sortAndSearchController.showSortDialog()
        }

        btnClassify.setOnClickListener {
            showClassifyMenu()
        }

        btnSearch.setOnClickListener {
            sortAndSearchController.openSearch()
        }

        etPath.setOnLongClickListener {
            if (isNasSourceSelected) {
                val profile = selectedNasProfile ?: return@setOnLongClickListener true
                val relativePath = currentNasRelativePath
                    .takeIf(String::isNotBlank)
                    ?.let { "\\$it" }
                    .orEmpty()
                copyToClipboard(
                    label = getString(R.string.path_placeholder),
                    text = listOf("\\\\", profile.host, "\\", profile.shareName, relativePath)
                        .joinToString(""),
                    copiedMessage = getString(R.string.copied_path)
                )
                return@setOnLongClickListener true
            }
            copyToClipboard(
                label = getString(R.string.path_placeholder),
                text = currentPath,
                copiedMessage = getString(R.string.copied_path)
            )
            true
        }

        listView.setOnItemClickListener { _, view, position, _ ->
            val item = fileItems[position]

            if (item.isParent) {
                goParent()
                clearFileListTouchState(view)
                return@setOnItemClickListener
            }

            val file = item.file
            if (file == null) {
                clearFileListTouchState(view)
                return@setOnItemClickListener
            }
            if (file.isDirectory) {
                setCurrentPath(file.absolutePath)
                loadCurrentDirectory()
            } else if (ReaderFileDetector.isEbook(file) || ComicArchive.isArchive(file)) {
                showArchiveMenu(file)
            } else {
                openFile(file)
            }
            clearFileListTouchState(view)
        }

        listView.setOnItemLongClickListener { _, view, position, _ ->
            val item = fileItems.getOrNull(position) ?: return@setOnItemLongClickListener false
            if (item.isParent) {
                return@setOnItemLongClickListener false
            }

            val file = item.file
            if (file?.isDirectory != true) {
                return@setOnItemLongClickListener false
            }

            showDirectoryMenu(file)
            clearFileListTouchState(view)
            true
        }

        pendingCenterTargetPath = intent.getStringExtra(EXTRA_CENTER_TARGET_PATH)
        val restoredScrollState = savedInstanceState?.readFileListScrollState()
        directoryScrollStateCache.restore(
            savedInstanceState?.readFileListScrollStates().orEmpty()
        )
        if (!showStoragePermissionNoticeIfNeeded()) {
            initializeBrowserIfPermitted(restoredScrollState)
            skipNextResumeDirectoryReload = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingCenterTargetPath = intent.getStringExtra(EXTRA_CENTER_TARGET_PATH)
        if (!pendingCenterTargetPath.isNullOrBlank() &&
            Environment.isExternalStorageManager()
        ) {
            sidePanelController.dismissSidePanels()
            initializeBrowserIfPermitted()
            skipNextResumeDirectoryReload = true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNasFeatureAvailability()
        updateNasCacheSummary()
        clearFileListTouchState()
        if (isNasSourceSelected) {
            updateNasEmptyState()
        }
        if (skipNextResumeDirectoryReload) {
            skipNextResumeDirectoryReload = false
            return
        }

        if (prefs.getBoolean(KEY_STORAGE_PERMISSION_PROMPTED, false)) {
            initializeBrowserIfPermitted(captureFileListScrollState())
        }
    }

    override fun onPause() {
        sidePanelController.dismissSidePanels()
        clearFileListTouchState()
        saveCurrentPath()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureFileListScrollState()?.let { scrollState ->
            directoryScrollStateCache.save(scrollState)
            scrollState.writeTo(outState)
        }
        outState.putParcelableArrayList(
            KEY_FILE_LIST_SCROLL_STATES,
            ArrayList(directoryScrollStateCache.snapshot().map { scrollState ->
                Bundle().apply { scrollState.writeTo(this) }
            })
        )
        pendingFileTransfer?.let { transfer ->
            outState.putString(KEY_PENDING_FILE_TRANSFER_PATH, transfer.sourcePath)
            outState.putString(KEY_PENDING_FILE_TRANSFER_MODE, transfer.mode.name)
            outState.putBoolean(KEY_PENDING_FILE_TRANSFER_IS_DIRECTORY, transfer.isDirectory)
        }
    }

    override fun onDestroy() {
        directoryLoadGeneration.incrementAndGet()
        nasDirectoryLoadGeneration.incrementAndGet()
        nasDownloadGeneration++
        nasDownloadCancellation?.set(true)
        dismissNasDownloadProgress()
        nasCacheClearDialog?.dismiss()
        nasCacheClearDialog = null
        nasCachedArchivePickerDialog?.dismiss()
        nasCachedArchivePickerDialog = null
        nasCachedArchiveActionDialog?.dismiss()
        nasCachedArchiveActionDialog = null
        nasCacheDeleteConfirmDialog?.dismiss()
        nasCacheDeleteConfirmDialog = null
        directoryRefreshController.removePendingFirstPreDrawListener()
        classifyGeneration.incrementAndGet()
        directoryLoadExecutor.shutdownNow()
        directoryChildCountExecutor.shutdownNow()
        pendingDirectoryChildCountPaths.clear()
        classifyExecutor.shutdownNow()
        fileTransferExecutor.shutdownNow()
        nasDirectoryExecutor.shutdownNow()
        nasDownloadExecutor.shutdownNow()
        nasCacheExecutor.shutdownNow()
        fileListAdapter.close()
        super.onDestroy()
    }

    private fun showStoragePermissionNoticeIfNeeded(): Boolean {
        if (prefs.getBoolean(KEY_STORAGE_PERMISSION_PROMPTED, false)) {
            return false
        }

        if (Environment.isExternalStorageManager()) {
            prefs.edit()
                .putBoolean(KEY_STORAGE_PERMISSION_PROMPTED, true)
                .apply()
            return false
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.storage_permission_title)
            .setMessage(R.string.storage_permission_message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_STORAGE_PERMISSION_PROMPTED, true)
                    .apply()
                openManageAllFilesAccessSettings()
            }
            .setCancelable(false)
            .showRounded()

        return true
    }

    private fun openManageAllFilesAccessSettings() {
        val appSettingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )

        try {
            startActivity(appSettingsIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openHelp() {
        startActivity(Intent(this, HelpActivity::class.java))
    }

    private fun showPendingFeature(labelResId: Int) {
        Toast.makeText(
            this,
            getString(R.string.feature_not_implemented, getString(labelResId)),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun initializeBrowserIfPermitted(scrollStateToRestore: FileListScrollState? = null) {
        if (!Environment.isExternalStorageManager()) {
            etPath.setText(R.string.storage_permission_required)
            fileItems.clear()
            notifyListChanged()
            return
        }

        if (openPendingCenteredTargetIfPresent()) {
            return
        }

        if (browserInitialized) {
            loadCurrentDirectory(scrollStateToRestore = scrollStateToRestore)
            return
        }

        browserInitialized = true
        currentPath = savedCurrentPath()
        loadCurrentDirectory(scrollStateToRestore = scrollStateToRestore)
    }

    private fun openPendingCenteredTargetIfPresent(): Boolean {
        val targetPath = pendingCenterTargetPath?.takeIf { it.isNotBlank() } ?: return false
        pendingCenterTargetPath = null
        val target = canonicalPathWithinStorageRoot(targetPath) ?: return false

        browserInitialized = true
        openDirectoryContainingTarget(target.absolutePath)
        return true
    }

    private fun canonicalPathWithinStorageRoot(path: String): File? {
        val storageRoot = runCatching { File(STORAGE_ROOT_PATH).canonicalFile.toPath() }
            .getOrNull()
            ?: return null
        return runCatching {
            File(path).canonicalFile.takeIf { it.toPath().startsWith(storageRoot) }
        }.getOrNull()
    }

    private fun openDirectoryContainingTarget(targetPath: String) {
        val storageRoot = File(STORAGE_ROOT_PATH).canonicalFile
        val target = File(targetPath).canonicalFile
        val targetAbsolutePath = target.absolutePath
        if (targetAbsolutePath == storageRoot.absolutePath) {
            setCurrentPath(STORAGE_ROOT_PATH)
            loadCurrentDirectory()
            return
        }

        val parentDirectory = target.parentFile
            ?.takeIf { it.isDirectory }
            ?: target.takeIf { it.isDirectory }
            ?: storageRoot
        val parentPath = if (
            runCatching { parentDirectory.canonicalFile == storageRoot }.getOrDefault(false)
        ) {
            STORAGE_ROOT_PATH
        } else {
            parentDirectory.absolutePath
        }
        setCurrentPath(parentPath)
        loadCurrentDirectory(targetAbsolutePath)
    }

    private fun goParent() {
        if (isNasSourceSelected) {
            if (currentNasRelativePath.isNotEmpty()) {
                currentNasRelativePath =
                    NasShareDirectoryBrowser.parentPath(currentNasRelativePath)
                updateNasPathDisplay()
                loadNasDirectory()
            }
            return
        }

        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        if (isAtStorageRoot()) {
            loadCurrentDirectory()
            return
        }

        val navigation = directoryBrowserController.parentNavigation(currentPath)
        setCurrentPath(navigation.path)
        loadCurrentDirectory(
            pathToCenter = navigation.pathToCenter,
            scrollStateToRestore = navigation.scrollState
        )
    }

    private fun goStorageRoot() {
        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        setCurrentPath(STORAGE_ROOT_PATH)
        loadCurrentDirectory()
    }

    private fun handleSystemBack() {
        if (isFileTransferInProgress) {
            return
        }
        if (pendingFileTransfer != null) {
            goParent()
            return
        }
        if (isNasSourceSelected) {
            if (currentNasRelativePath.isNotEmpty()) {
                goParent()
            } else {
                finish()
            }
            return
        }
        if (!Environment.isExternalStorageManager() || isAtStorageRoot()) {
            finish()
            return
        }

        goParent()
    }

    // 测试接口：返回最近一次恢复目录滚动时请求的 top 值，不参与运行时逻辑。
    internal val requestedScrollTopForTest: Int?
        get() = directoryRefreshController.requestedScrollTopForTest

    internal var currentPathForTest: String
        get() = currentPath
        set(value) {
            currentPath = value
        }

    internal val directoryLoadExecutorForTest: ThreadPoolExecutor
        get() = directoryLoadExecutor

    internal val directoryScrollStateCacheForTest: DirectoryScrollStateCache
        get() = directoryScrollStateCache

    internal fun loadCurrentDirectoryForTest() {
        loadCurrentDirectory()
    }

    internal val fileTransferControllerForTest: FileTransferController
        get() = fileTransferController

    internal val fileTransferExecutorForTest: ExecutorService
        get() = fileTransferExecutor

    internal val directoryLoadGenerationForTest: Int
        get() = directoryLoadGeneration.get()

    internal fun beginFileTransferForTest(file: File, mode: FileTransferMode) {
        beginFileTransfer(file, mode)
    }

    internal fun startFileTransferForTest(
        transfer: PendingFileTransfer,
        destinationDirectory: File,
        targetName: String
    ) {
        startFileTransfer(transfer, destinationDirectory, targetName)
    }

    internal fun updateFileTransferToolbarForTest() {
        updateFileTransferToolbar()
    }

    internal fun showFileOperationProgressForTest() {
        fileTransferWorkflowController.showFileOperationProgressForTest()
    }

    internal fun handleDirectoryTransferFailureForTest(
        transfer: PendingFileTransfer,
        result: DirectoryTransferOperations.Result,
        destinationDirectory: File
    ) {
        fileTransferWorkflowController.handleDirectoryTransferFailureForTest(
            transfer,
            result,
            destinationDirectory
        )
    }

    private fun isAtStorageRoot(): Boolean {
        return File(currentPath).absolutePath == File(STORAGE_ROOT_PATH).absolutePath
    }

    private fun loadCurrentDirectory(
        pathToCenter: String? = null,
        scrollStateToRestore: FileListScrollState? = null
    ) {
        directoryRefreshController.loadCurrentDirectory(pathToCenter, scrollStateToRestore)
    }

    private fun invalidateDirectoryChildCount(directory: File?) {
        directory?.let { directoryChildCountCache.invalidate(it.absolutePath) }
    }

    private fun captureFileListScrollState(): FileListScrollState? {
        return directoryRefreshController.captureFileListScrollState()
    }

    private fun showClassifyMenu() {
        val items = listOf(
            RoundedPopupMenuItem(getString(R.string.create_directory_name)) {
                showCreateDirectoryDialog()
            },
            RoundedPopupMenuItem(getString(R.string.classify_comics)) {
                comicOrganizeController.confirmClassifyCurrentDirectory()
            },
            RoundedPopupMenuItem(getString(R.string.comic_migration)) {
                startComicMigration()
            },
            RoundedPopupMenuItem(getString(ComicOrganizeController.MENU_TITLE_MERGE_COMICS_NON_RECURSIVE)) {
                comicOrganizeController.startMergeComicsNonRecursive()
            },
            RoundedPopupMenuItem(getString(ComicOrganizeController.MENU_TITLE_MERGE_FILES_NON_RECURSIVE)) {
                comicOrganizeController.startMergeFilesNonRecursive()
            }
        )

        showRoundedPopupMenu(btnClassify, items, widthDp = 252)
    }

    private fun showCreateDirectoryDialog() {
        val parentDirectory = File(currentPath)
        if (!parentDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.create_directory_name_hint)
            setText(DirectoryTransferOperations.suggestedNewFolderName(parentDirectory))
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.create_directory_name)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                createDirectory(parentDirectory, input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showRounded()
    }

    private fun createDirectory(parentDirectory: File, requestedName: String) {
        when (DirectoryTransferOperations.createDirectory(parentDirectory, requestedName)) {
            DirectoryTransferOperations.CreateDirectoryResult.SUCCESS -> {
                invalidateDirectoryChildCount(parentDirectory)
                loadCurrentDirectory(scrollStateToRestore = captureFileListScrollState())
            }

            DirectoryTransferOperations.CreateDirectoryResult.PARENT_NOT_DIRECTORY ->
                showMessage(getString(R.string.message_invalid_directory))

            DirectoryTransferOperations.CreateDirectoryResult.EMPTY_NAME ->
                showMessage(getString(R.string.create_directory_name_empty))

            DirectoryTransferOperations.CreateDirectoryResult.INVALID_NAME ->
                showMessage(getString(R.string.create_directory_name_invalid))

            DirectoryTransferOperations.CreateDirectoryResult.TARGET_EXISTS ->
                showMessage(getString(R.string.create_directory_name_exists))

            DirectoryTransferOperations.CreateDirectoryResult.FAILED ->
                showMessage(getString(R.string.create_directory_name_failed))
        }
    }

    private fun startComicMigration() {
        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        if (comicOrganizeController.isClassifyingComics) {
            showMessage(getString(R.string.classify_comics_running))
            return
        }

        if (comicOrganizeController.isBuildingDirectorySimilarityReport) {
            showMessage(ComicOrganizeController.MESSAGE_FINDING_SIMILAR_DIRECTORY_NAMES)
            return
        }

        if (comicOrganizeController.isMergingComics) {
            showMessage(ComicOrganizeController.MESSAGE_MERGING_COMICS)
            return
        }

        if (comicOrganizeController.isMergingFiles) {
            showMessage(ComicOrganizeController.MESSAGE_MERGING_FILES)
            return
        }

        val sourceDirectory = File(currentPath)
        if (!sourceDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        startActivity(
            Intent(this, ComicMigrationActivity::class.java).apply {
                putExtra(
                    ComicMigrationActivity.EXTRA_SOURCE_DIRECTORY_PATH,
                    sourceDirectory.absolutePath
                )
            }
        )
    }

    private fun openFile(file: File) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (_: IllegalArgumentException) {
            showMessage(getString(R.string.message_invalid_file))
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
        } catch (_: ActivityNotFoundException) {
            showMessage(getString(R.string.message_no_app_for_file))
        }
    }

    private fun showDirectoryMenu(directory: File) {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_directory_actions, null)
        val tvDirectoryActionTitle = content.findViewById<TextView>(R.id.tvDirectoryActionTitle)
        val btnFavoritePath = content.findViewById<TextView>(R.id.btnFavoritePathAction)
        val btnBookcaseDirectory = content.findViewById<TextView>(R.id.btnBookcaseDirectoryAction)
        val btnSetChildDirectoriesAsBookcases =
            content.findViewById<TextView>(R.id.btnSetChildDirectoriesAsBookcases)
        val btnCancelChildDirectoriesAsBookcases =
            content.findViewById<TextView>(R.id.btnCancelChildDirectoriesAsBookcases)
        val btnCopyPathName = content.findViewById<TextView>(R.id.btnCopyPathName)
        val btnRenameDirectoryName = content.findViewById<TextView>(R.id.btnRenameDirectoryName)
        val btnCopyDirectory = content.findViewById<TextView>(R.id.btnCopyDirectory)
        val btnCutDirectory = content.findViewById<TextView>(R.id.btnCutDirectory)
        val btnDeleteDirectory = content.findViewById<TextView>(R.id.btnDeleteDirectory)
        val isFavorite = FavoritePathStore.isFavorite(this, directory)
        val isProtectedByPendingTransfer = containsPendingFileTransferSource(directory)
        val canStartTransfer = pendingFileTransfer == null && !isFileTransferInProgress

        tvDirectoryActionTitle.text = directory.name
        sizeBottomSheetActionIcons(
            btnFavoritePath,
            btnBookcaseDirectory,
            btnSetChildDirectoriesAsBookcases,
            btnCancelChildDirectoriesAsBookcases,
            btnCopyPathName,
            btnRenameDirectoryName,
            btnCopyDirectory,
            btnCutDirectory,
            btnDeleteDirectory
        )
        btnFavoritePath.text = getString(
            if (isFavorite) R.string.cancel_favorite else R.string.favorite_path_action
        )
        val isBookcase = AppSettings.isBookcaseDirectory(this, directory)
        btnBookcaseDirectory.text = getString(
            if (isBookcase) R.string.cancel_bookcase else R.string.set_as_bookcase
        )

        btnBookcaseDirectory.setOnClickListener {
            val enableBookcase = !AppSettings.isBookcaseDirectory(this, directory)
            AppSettings.setBookcaseDirectory(this, directory, enableBookcase)
            showMessage(
                getString(
                    if (enableBookcase) R.string.bookcase_enabled else R.string.bookcase_disabled
                )
            )
            dialog.setOnDismissListener {
                resetFileListState()
                listView.post {
                    if (!isFinishing && !isDestroyed) {
                        fileListAdapter.notifyDataSetChangedWhenIdle(listView)
                    }
                }
            }
            dialog.dismiss()
        }

        btnSetChildDirectoriesAsBookcases.setOnClickListener {
            dialog.dismiss()
            updateChildDirectoryBookcases(directory, enabled = true)
        }

        btnCancelChildDirectoriesAsBookcases.setOnClickListener {
            dialog.dismiss()
            updateChildDirectoryBookcases(directory, enabled = false)
        }

        btnFavoritePath.setOnClickListener {
            if (FavoritePathStore.isFavorite(this, directory)) {
                FavoritePathStore.remove(this, directory)
                showMessage(getString(R.string.favorite_path_removed))
                notifyListChanged()
                dialog.dismiss()
                return@setOnClickListener
            }

            val result = FavoritePathStore.record(this, directory)
            when (result) {
                FavoritePathStore.RecordResult.ADDED,
                FavoritePathStore.RecordResult.ALREADY_EXISTS -> {
                    showMessage(getString(R.string.favorite_path_added))
                    notifyListChanged()
                }

                FavoritePathStore.RecordResult.LIMIT_REACHED -> {
                    showMessage(getString(R.string.favorite_path_limit_reached))
                }

                FavoritePathStore.RecordResult.INVALID -> {
                    showMessage(getString(R.string.message_invalid_directory))
                }

                FavoritePathStore.RecordResult.STORAGE_ERROR -> {
                    showMessage(getString(R.string.stored_collection_unreadable))
                }
            }
            dialog.dismiss()
        }

        btnCopyPathName.setOnClickListener {
            copyToClipboard(
                label = getString(R.string.copy_path_name),
                text = directory.absolutePath,
                copiedMessage = getString(R.string.copied_path)
            )
            dialog.dismiss()
        }

        btnCopyDirectory.isEnabled = canStartTransfer
        btnCutDirectory.isEnabled = canStartTransfer
        btnCopyDirectory.alpha = if (canStartTransfer) 1f else 0.38f
        btnCutDirectory.alpha = if (canStartTransfer) 1f else 0.38f
        btnRenameDirectoryName.isEnabled = !isProtectedByPendingTransfer
        btnDeleteDirectory.isEnabled = !isProtectedByPendingTransfer
        btnRenameDirectoryName.alpha = if (isProtectedByPendingTransfer) 0.38f else 1f
        btnDeleteDirectory.alpha = if (isProtectedByPendingTransfer) 0.38f else 1f

        btnCopyDirectory.setOnClickListener {
            dialog.dismiss()
            beginFileTransfer(directory, FileTransferMode.COPY)
        }

        btnCutDirectory.setOnClickListener {
            dialog.dismiss()
            beginFileTransfer(directory, FileTransferMode.CUT)
        }

        btnRenameDirectoryName.setOnClickListener {
            dialog.dismiss()
            showRenameDirectoryDialog(directory)
        }

        btnDeleteDirectory.setOnClickListener {
            dialog.dismiss()
            confirmDeleteDirectory(directory)
        }

        dialog.showRoundedContent(content)
    }

    private fun updateChildDirectoryBookcases(directory: File, enabled: Boolean) {
        directoryLoadExecutor.execute {
            val childDirectories = try {
                if (!directory.isDirectory) {
                    null
                } else {
                    directory.listFiles()
                        ?.filter { !it.name.startsWith(".") && it.isDirectory }
                }
            } catch (_: SecurityException) {
                null
            }

            val changedCount = childDirectories?.let {
                AppSettings.setBookcaseDirectories(this, it, enabled)
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }

                when {
                    childDirectories == null -> {
                        showMessage(getString(R.string.directory_read_failed))
                    }

                    childDirectories.isEmpty() -> {
                        showMessage(getString(R.string.no_visible_child_directories))
                    }

                    else -> {
                        val updatedCount = changedCount ?: 0
                        if (updatedCount > 0) {
                            fileListAdapter.notifyDataSetChangedWhenIdle(listView)
                        }
                        showMessage(
                            getString(
                                if (enabled) {
                                    R.string.child_bookcases_added
                                } else {
                                    R.string.child_bookcases_removed
                                },
                                updatedCount
                            )
                        )
                    }
                }
            }
        }
    }

    private fun showRenameDirectoryDialog(directory: File) {
        if (!directory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        val input = EditText(this).apply {
            setSingleLine(true)
            setText(directory.name)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.rename_directory_name_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                renameDirectory(directory, input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showRounded()
    }

    private fun renameDirectory(directory: File, rawName: String) {
        if (containsPendingFileTransferSource(directory)) {
            showMessage(getString(R.string.file_transfer_source_in_use))
            return
        }
        val parent = directory.parentFile ?: run {
            showMessage(getString(R.string.rename_directory_name_failed))
            return
        }
        val trimmedName = rawName.trim()
        if (trimmedName.isEmpty()) {
            showMessage(getString(R.string.rename_directory_name_empty))
            return
        }
        if (trimmedName.any { it.code < 32 || it in INVALID_FILE_NAME_CHARS }) {
            showMessage(getString(R.string.rename_directory_name_invalid))
            return
        }
        if (trimmedName == "." || trimmedName == "..") {
            showMessage(getString(R.string.rename_directory_name_invalid))
            return
        }
        if (trimmedName == directory.name) {
            return
        }

        val targetDirectory = File(parent, trimmedName)
        if (targetDirectory.exists()) {
            showMessage(getString(R.string.rename_directory_name_exists))
            return
        }

        val wasFavorite = FavoritePathStore.isFavorite(this, directory)
        val renamed = directory.renameTo(targetDirectory)
        if (!renamed) {
            showMessage(getString(R.string.rename_directory_name_failed))
            return
        }

        if (wasFavorite) {
            FavoritePathStore.remove(this, directory)
            FavoritePathStore.record(this, targetDirectory)
        }
        FavoriteComicStore.moveDirectory(this, directory, targetDirectory)
        ReadingHistoryStore.moveDirectory(this, directory, targetDirectory)
        ReaderProgressStore.moveDirectory(this, directory, targetDirectory)
        AppSettings.renameBookcaseDirectory(this, directory, targetDirectory)

        invalidateDirectoryChildCount(parent)
        invalidateDirectoryChildCount(directory)
        showMessage(getString(R.string.rename_directory_name_success))
        loadCurrentDirectory(targetDirectory.absolutePath)
    }

    private fun isPendingFileTransferSource(file: File): Boolean =
        fileTransferController.isPendingTransferSource(file)

    private fun containsPendingFileTransferSource(directory: File): Boolean =
        fileTransferController.containsPendingTransferSource(directory)



    private fun restorePendingFileTransfer(savedInstanceState: Bundle?) {
        val state = savedInstanceState ?: return
        val sourcePath = state.getString(KEY_PENDING_FILE_TRANSFER_PATH)
            ?: return
        val modeName = state.getString(KEY_PENDING_FILE_TRANSFER_MODE)
            ?: return
        val mode = FileTransferMode.values().firstOrNull { it.name == modeName }
            ?: return
        val source = File(sourcePath)
        val isDirectory = state.getBoolean(KEY_PENDING_FILE_TRANSFER_IS_DIRECTORY)
        val sourceExists = if (isDirectory) source.isDirectory else source.isFile
        if (!sourceExists) {
            return
        }

        val transfer = PendingFileTransfer(sourcePath, mode, isDirectory)
        if (fileTransferController.restore(transfer, sourceExists)) {
            updateFileTransferToolbar()
        }
    }

    private fun beginFileTransfer(file: File, mode: FileTransferMode) {
        fileTransferWorkflowController.beginFileTransfer(file, mode)
    }

    private fun updateFileTransferToolbar() {
        val isActive = pendingFileTransfer != null
        val canUseLocalSourceActions = !isNasSourceSelected
        val normalToolbarButtons = arrayOf(
            btnFavoritePaths,
            btnFavoriteComics,
            btnReadingHistory,
            btnSort,
            btnClassify,
            btnSearch
        )
        normalToolbarButtons.forEach { button ->
            button.visibility = if (isActive) View.GONE else View.VISIBLE
            button.isEnabled = !isActive && canUseLocalSourceActions
            button.alpha = if (!isActive && canUseLocalSourceActions) 1f else 0.38f
        }
        val canNavigateBack = !isNasSourceSelected || currentNasRelativePath.isNotEmpty()
        btnBack.visibility = if (isActive) View.GONE else View.VISIBLE
        btnBack.isEnabled = !isActive && !nasDownloadInProgress && canNavigateBack &&
            !nasDirectoryLoadInProgress
        btnBack.alpha = if (btnBack.isEnabled) 1f else 0.38f

        sourceNasButton.isEnabled = !isActive && !nasDownloadInProgress
        sourceLocalButton.isEnabled = !isActive && !nasDownloadInProgress
        sourceSwitchTrack.alpha = if (isActive || nasDownloadInProgress) 0.45f else 1f

        btnHelp.isEnabled = !isActive && !nasDownloadInProgress
        btnOptions.isEnabled = !isActive && !nasDownloadInProgress
        btnHelp.alpha = if (isActive || nasDownloadInProgress) 0.38f else 1f
        btnOptions.alpha = if (isActive || nasDownloadInProgress) 0.38f else 1f

        btnPasteFile.visibility = if (isActive) View.VISIBLE else View.GONE
        btnCreateTransferDirectory.visibility = if (isActive) View.VISIBLE else View.GONE
        btnExitFileTransfer.visibility = if (isActive) View.VISIBLE else View.GONE
        btnTransferParent.visibility = if (isActive) View.VISIBLE else View.GONE
        val transfer = pendingFileTransfer
        if (transfer != null) {
            btnPasteFile.setImageResource(
                if (transfer.mode == FileTransferMode.COPY) {
                    R.drawable.png_file_copy_icon
                } else {
                    R.drawable.png_file_cut_icon
                }
            )
        }

        val canInteract = isActive && !isFileTransferInProgress
        btnPasteFile.isEnabled = canInteract
        btnCreateTransferDirectory.isEnabled = canInteract
        btnExitFileTransfer.isEnabled = canInteract
        btnTransferParent.isEnabled = canInteract
        listView.isEnabled = !isFileTransferInProgress && canUseLocalSourceActions
        nasDirectoryListView.isEnabled = !isFileTransferInProgress &&
            !nasDirectoryLoadInProgress && !nasDownloadInProgress
    }

    private fun selectFileSource(isNas: Boolean) {
        if (isNas == isNasSourceSelected || pendingFileTransfer != null) {
            return
        }

        val wasNas = isNasSourceSelected
        if (wasNas && !isNas) {
            nasDirectoryLoadGeneration.incrementAndGet()
            nasDirectoryLoadInProgress = false
        }
        val generation = ++sourceTransitionGeneration
        listView.animate().cancel()
        nasEmptyState.animate().cancel()
        listView.translationX = 0f
        listView.alpha = 1f
        nasEmptyState.translationX = 0f
        nasEmptyState.alpha = 1f
        listView.visibility = if (wasNas) View.GONE else View.VISIBLE
        nasEmptyState.visibility = if (wasNas) View.VISIBLE else View.GONE

        isNasSourceSelected = isNas
        updateNasCacheSummary()
        updateSourcePathDisplay()
        if (isNas) updateNasEmptyState()
        updateSourceSwitchAppearance(isNas)
        updateFileTransferToolbar()

        val width = findViewById<View>(R.id.fileListPanel).width.toFloat()
        if (width <= 0f) {
            listView.visibility = if (isNas) View.GONE else View.VISIBLE
            nasEmptyState.visibility = if (isNas) View.VISIBLE else View.GONE
            return
        }

        val outgoing = if (wasNas) nasEmptyState else listView
        val incoming = if (isNas) nasEmptyState else listView
        incoming.translationX = if (isNas) width else -width
        incoming.alpha = 0.96f
        incoming.visibility = View.VISIBLE

        outgoing.animate()
            .translationX(if (isNas) -width else width)
            .alpha(0.96f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (generation == sourceTransitionGeneration) {
                    outgoing.visibility = View.GONE
                    outgoing.translationX = 0f
                    outgoing.alpha = 1f
                }
            }
            .start()
        incoming.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(280L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun refreshNasFeatureAvailability() {
        val enabled = NasShareStore.isNasEnabled(this)
        sourceSwitchDock.visibility = if (enabled) View.VISIBLE else View.GONE

        val bottomPadding = if (enabled) sourceSwitchDock.layoutParams.height else 0
        listView.setPadding(
            listView.paddingLeft,
            listView.paddingTop,
            listView.paddingRight,
            bottomPadding
        )
        nasEmptyPlaceholder.setPadding(
            nasEmptyPlaceholder.paddingLeft,
            nasEmptyPlaceholder.paddingTop,
            nasEmptyPlaceholder.paddingRight,
            bottomPadding
        )
        nasBrowserContent.setPadding(
            nasBrowserContent.paddingLeft,
            nasBrowserContent.paddingTop,
            nasBrowserContent.paddingRight,
            bottomPadding
        )

        if (!enabled && isNasSourceSelected) {
            selectFileSource(isNas = false)
        }
    }

    private fun updateSourceSwitchAppearance(isNas: Boolean, animate: Boolean = true) {
        val activeColor = getColor(R.color.comiclab_text_on_accent)
        val inactiveColor = getColor(R.color.comiclab_text_secondary)
        val nasColor = if (isNas) activeColor else inactiveColor
        val localColor = if (isNas) inactiveColor else activeColor
        sourceNasLabel.setTextColor(nasColor)
        sourceNasIcon.setColorFilter(nasColor)
        sourceLocalLabel.setTextColor(localColor)
        sourceLocalIcon.setColorFilter(localColor)

        val targetTranslationX = if (isNas) 0f else resources.getDimension(
            R.dimen.file_source_switch_segment_width
        )
        sourceSwitchThumb.animate().cancel()
        if (animate) {
            sourceSwitchThumb.animate()
                .translationX(targetTranslationX)
                .setDuration(240L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            sourceSwitchThumb.translationX = targetTranslationX
        }
    }

    private fun updateSourcePathDisplay() {
        if (isNasSourceSelected) {
            val profile = selectedNasProfile
            if (profile == null) {
                etPath.setText(R.string.nas_not_connected_path)
            } else {
                val remotePathParts = currentNasRelativePath
                    .split('\\', '/')
                    .filter(String::isNotBlank)
                etPath.setText(
                    (listOf("NAS", profile.name) + remotePathParts)
                        .joinToString("/")
                )
            }
        } else if (Environment.isExternalStorageManager()) {
            etPath.setText(currentPath)
        } else {
            etPath.setText(R.string.storage_permission_required)
        }
    }

    private fun updateNasEmptyState() {
        val profiles = runCatching { NasShareStore.load(this) }.getOrNull()
        if (profiles == null) {
            availableNasProfiles = emptyList()
            selectedNasProfile = null
            showNasPlaceholder(
                R.string.nas_share_config_read_failed,
                R.string.nas_share_config_read_failed,
                R.string.nas_manage_shares
            )
            return
        }

        availableNasProfiles = profiles
        if (profiles.isEmpty()) {
            selectedNasProfile = null
            currentNasRelativePath = ""
            showNasPlaceholder(
                R.string.nas_not_connected_title,
                R.string.nas_not_connected_message,
                R.string.nas_manage_shares
            )
            updateSourcePathDisplay()
            return
        }

        val previousProfile = selectedNasProfile
        val activeShareId = runCatching { NasShareStore.activeShareId(this) }.getOrNull()
        val profile = profiles.firstOrNull { it.id == activeShareId }
        if (profile != null) {
            if (previousProfile == null ||
                previousProfile.host != profile.host ||
                previousProfile.shareName != profile.shareName
            ) {
                currentNasRelativePath = ""
            }
            selectedNasProfile = profile
            showNasBrowserContent(profiles)
            updateNasPathDisplay()
            loadNasDirectory()
            return
        }

        currentNasRelativePath = ""
        selectedNasProfile = null
        showNasPlaceholder(
            R.string.nas_no_active_share_title,
            R.string.nas_no_active_share_message,
            R.string.nas_manage_shares
        )
    }

    private fun showNasPlaceholder(titleResId: Int, messageResId: Int, actionResId: Int) {
        nasBrowserContent.visibility = View.GONE
        nasEmptyPlaceholder.visibility = View.VISIBLE
        nasEmptyTitle.setText(titleResId)
        nasEmptyMessage.setText(messageResId)
        btnNasManageShares.setText(actionResId)
        btnNasManageShares.visibility = View.VISIBLE
        btnNasSwitchShare.visibility = View.GONE
        nasDirectoryLoadInProgress = false
        updateSourcePathDisplay()
        updateFileTransferToolbar()
        updateNasCacheSummary()
    }

    private fun showNasBrowserContent(profiles: List<NasShareProfile>) {
        nasEmptyPlaceholder.visibility = View.GONE
        nasBrowserContent.visibility = View.VISIBLE
        btnNasSwitchShare.visibility = if (profiles.size > 1) View.VISIBLE else View.GONE
        updateNasCacheSummary()
    }

    private fun showNasProfilePicker(profiles: List<NasShareProfile>) {
        if (profiles.isEmpty()) {
            openNasShareSettings()
            return
        }
        if (profiles.size == 1) {
            selectNasProfile(profiles.single())
            return
        }
        if (isNasProfileChooserShowing || isFinishing || isDestroyed) {
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.nas_choose_share_title)
            .setItems(profiles.map(NasShareProfile::name).toTypedArray()) { _, index ->
                profiles.getOrNull(index)?.let(::selectNasProfile)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        isNasProfileChooserShowing = true
        dialog.setOnDismissListener {
            isNasProfileChooserShowing = false
        }
        dialog.show()
    }

    private fun selectNasProfile(profile: NasShareProfile) {
        try {
            NasShareStore.setActiveShare(this, profile.id)
        } catch (_: Exception) {
            showMessage(R.string.nas_share_enable_failed)
            return
        }
        val targetChanged = selectedNasProfile?.let {
            it.host != profile.host || it.shareName != profile.shareName
        } ?: true
        selectedNasProfile = profile
        if (targetChanged) {
            currentNasRelativePath = ""
        }
        showNasBrowserContent(availableNasProfiles)
        updateNasPathDisplay()
        loadNasDirectory()
    }

    private fun updateNasPathDisplay() {
        nasPathStateSink.text = currentNasRelativePath
        updateSourcePathDisplay()
    }

    private fun loadNasDirectory() {
        val profile = selectedNasProfile ?: return
        val requestedPath = currentNasRelativePath
        val generation = nasDirectoryLoadGeneration.incrementAndGet()
        nasDirectoryLoadInProgress = true
        nasDirectoryListView.visibility = View.GONE
        nasStatusPanel.visibility = View.GONE
        tvNasEmptyHint.visibility = View.GONE
        nasLoadingPanel.visibility = View.VISIBLE
        updateNasPathDisplay()
        updateFileTransferToolbar()

        nasDirectoryExecutor.execute {
            if (generation != nasDirectoryLoadGeneration.get() || isDestroyed) {
                return@execute
            }
            val result = try {
                val password = if (profile.username.isBlank()) {
                    ""
                } else {
                    try {
                        NasShareStore.rememberedPassword(profile)
                            ?.takeIf(String::isNotEmpty)
                            ?: throw NasSavedPasswordUnavailableException()
                    } catch (exception: NasSavedPasswordUnavailableException) {
                        throw exception
                    } catch (_: Exception) {
                        throw NasSavedPasswordUnavailableException()
                    }
                }
                NasDirectoryLoadResult.Loaded(
                    NasShareDirectoryBrowser.list(profile, password, requestedPath)
                )
            } catch (_: NasSavedPasswordUnavailableException) {
                NasDirectoryLoadResult.PasswordUnavailable
            } catch (_: Exception) {
                NasDirectoryLoadResult.Failed
            }

            runOnUiThread {
                if (isDestroyed || !isNasSourceSelected ||
                    generation != nasDirectoryLoadGeneration.get() ||
                    selectedNasProfile?.id != profile.id
                ) {
                    return@runOnUiThread
                }
                nasDirectoryLoadInProgress = false
                nasLoadingPanel.visibility = View.GONE
                when (result) {
                    is NasDirectoryLoadResult.Loaded -> {
                        tvNasEmptyHint.visibility = if (
                            requestedPath.isNotEmpty() && result.entries.isEmpty()
                        ) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                        val visibleEntries = buildList {
                            if (requestedPath.isNotEmpty()) {
                                add(
                                    NasDirectoryEntry(
                                        name = "..",
                                        relativePath = NasShareDirectoryBrowser.parentPath(requestedPath),
                                        isDirectory = true,
                                        sizeBytes = 0L,
                                        modifiedAt = 0L,
                                        isParent = true
                                    )
                                )
                            }
                            addAll(result.entries)
                        }
                        nasFileListAdapter.replaceItems(visibleEntries)
                        if (visibleEntries.isEmpty()) {
                            nasDirectoryListView.visibility = View.GONE
                            tvNasBrowserStatus.setText(R.string.nas_empty_directory)
                            btnNasRetry.visibility = View.GONE
                            nasStatusPanel.visibility = View.VISIBLE
                        } else {
                            nasStatusPanel.visibility = View.GONE
                            nasDirectoryListView.visibility = View.VISIBLE
                            nasDirectoryListView.setSelection(0)
                        }
                    }
                    NasDirectoryLoadResult.PasswordUnavailable -> {
                        showNasDirectoryError(
                            R.string.nas_directory_password_required,
                            canRetry = false
                        )
                    }
                    NasDirectoryLoadResult.Failed -> {
                        showNasDirectoryError(
                            R.string.nas_directory_load_failed,
                            canRetry = true
                        )
                    }
                }
                updateFileTransferToolbar()
            }
        }
    }

    private fun startNasArchiveDownload(entry: NasDirectoryEntry) {
        if (nasDownloadInProgress || entry.sizeBytes <= 0L) {
            showMessage(R.string.nas_download_failed)
            return
        }
        val profile = selectedNasProfile ?: run {
            showMessage(R.string.nas_directory_password_required)
            return
        }
        val password = if (profile.username.isBlank()) {
            ""
        } else {
            try {
                NasShareStore.rememberedPassword(profile)
                    ?.takeIf(String::isNotEmpty)
                    ?: throw NasSavedPasswordUnavailableException()
            } catch (_: Exception) {
                showMessage(R.string.nas_directory_password_required)
                return
            }
        }
        val cacheRoot = NasShareArchiveDownloader.cacheDirectory(cacheDir)
        val destination = try {
            NasShareArchiveDownloader.cacheFile(cacheRoot, profile, entry)
        } catch (exception: Exception) {
            android.util.Log.e("ComicLabNAS", "Invalid NAS archive path", exception)
            showMessage(R.string.nas_download_failed)
            return
        }
        if (destination.isFile && destination.length() == entry.sizeBytes) {
            openMangaPreview(destination)
            return
        }
        try {
            NasShareArchiveDownloader.checkAvailableSpace(cacheRoot, entry.sizeBytes)
        } catch (exception: NasShareArchiveDownloader.InsufficientStorageException) {
            showNasDownloadInsufficientSpace(exception.requiredBytes, exception.availableBytes)
            return
        } catch (exception: Exception) {
            android.util.Log.e("ComicLabNAS", "Could not prepare NAS cache", exception)
            showMessage(R.string.nas_download_failed)
            return
        }

        val cancelled = AtomicBoolean(false)
        val generation = ++nasDownloadGeneration
        nasDownloadCancellation = cancelled
        nasDownloadInProgress = true
        showNasDownloadProgress(entry, cancelled)
        updateFileTransferToolbar()
        nasDownloadExecutor.execute {
            val result = try {
                NasDownloadResult.Completed(
                    NasShareArchiveDownloader.download(
                        profile = profile,
                        password = password,
                        entry = entry,
                        destination = destination,
                        cancelled = cancelled
                    ) { downloadedBytes, totalBytes ->
                        reportNasDownloadProgress(
                            generation,
                            entry.name,
                            downloadedBytes,
                            totalBytes
                        )
                    }
                )
            } catch (exception: NasShareArchiveDownloader.InsufficientStorageException) {
                NasDownloadResult.InsufficientSpace(
                    exception.requiredBytes,
                    exception.availableBytes
                )
            } catch (_: NasShareArchiveDownloader.DownloadCancelledException) {
                NasDownloadResult.Cancelled
            } catch (exception: Exception) {
                NasDownloadResult.Failed(exception)
            }

            runOnUiThread {
                if (generation != nasDownloadGeneration) return@runOnUiThread
                nasDownloadInProgress = false
                nasDownloadCancellation = null
                dismissNasDownloadProgress()
                updateFileTransferToolbar()
                if (isDestroyed || isFinishing) return@runOnUiThread

                when (result) {
                    is NasDownloadResult.Completed -> {
                        if (selectedNasProfile?.id == profile.id) {
                            openMangaPreview(result.file)
                        }
                    }

                    is NasDownloadResult.InsufficientSpace -> showNasDownloadInsufficientSpace(
                        result.requiredBytes,
                        result.availableBytes
                    )

                    NasDownloadResult.Cancelled -> showMessage(R.string.nas_download_cancelled)

                    is NasDownloadResult.Failed -> {
                        android.util.Log.e(
                            "ComicLabNAS",
                            "NAS archive download failed: ${entry.relativePath}",
                            result.exception
                        )
                        showMessage(R.string.nas_download_failed)
                    }
                }
            }
        }
    }

    private fun showNasDownloadProgress(entry: NasDirectoryEntry, cancelled: AtomicBoolean) {
        val content = layoutInflater.inflate(R.layout.dialog_file_operation_progress, null)
        nasDownloadProgressStatus = content.findViewById(R.id.tvFileOperationProgressStatus)
        nasDownloadProgressBar = content.findViewById(R.id.progressFileOperation)
        nasDownloadProgressCount = content.findViewById(R.id.tvFileOperationProgressCount)
        nasDownloadProgressStatus?.text = getString(R.string.nas_download_connecting, entry.name)
        nasDownloadProgressBar?.isIndeterminate = true
        nasDownloadProgressCount?.text = getString(
            R.string.nas_download_progress_count,
            Formatter.formatShortFileSize(this, 0L),
            Formatter.formatShortFileSize(this, entry.sizeBytes)
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.nas_download_title)
            .setView(content)
            .setNegativeButton(R.string.nas_download_cancel, null)
            .setCancelable(false)
            .createRounded()
        nasDownloadProgressDialog = dialog
        nasDownloadLastProgressUpdateAt = 0L
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { button ->
            cancelled.set(true)
            button.isEnabled = false
            nasDownloadProgressStatus?.setText(R.string.nas_download_cancelling)
        }
    }

    private fun reportNasDownloadProgress(
        generation: Int,
        fileName: String,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        val now = SystemClock.uptimeMillis()
        val finished = totalBytes > 0L && downloadedBytes >= totalBytes
        if (!finished && now - nasDownloadLastProgressUpdateAt < NAS_DOWNLOAD_PROGRESS_UPDATE_INTERVAL_MS) {
            return
        }
        nasDownloadLastProgressUpdateAt = now
        runOnUiThread {
            if (generation != nasDownloadGeneration || isDestroyed || isFinishing ||
                nasDownloadProgressDialog?.isShowing != true ||
                nasDownloadCancellation?.get() == true
            ) {
                return@runOnUiThread
            }
            nasDownloadProgressStatus?.text = getString(R.string.nas_download_progress_status, fileName)
            val progressBar = nasDownloadProgressBar ?: return@runOnUiThread
            progressBar.isIndeterminate = totalBytes <= 0L
            if (totalBytes > 0L) {
                progressBar.progress = (
                    downloadedBytes.coerceAtLeast(0L).toDouble() / totalBytes * 100.0
                    ).toInt().coerceIn(0, 100)
            }
            nasDownloadProgressCount?.text = getString(
                R.string.nas_download_progress_count,
                Formatter.formatShortFileSize(this, downloadedBytes.coerceAtLeast(0L)),
                Formatter.formatShortFileSize(this, totalBytes.coerceAtLeast(0L))
            )
        }
    }

    private fun dismissNasDownloadProgress() {
        nasDownloadProgressDialog?.dismiss()
        nasDownloadProgressDialog = null
        nasDownloadProgressStatus = null
        nasDownloadProgressBar = null
        nasDownloadProgressCount = null
    }

    private fun showNasDownloadInsufficientSpace(requiredBytes: Long, availableBytes: Long) {
        showMessage(
            getString(
                R.string.nas_download_insufficient_space,
                Formatter.formatShortFileSize(this, requiredBytes),
                Formatter.formatShortFileSize(this, availableBytes)
            )
        )
    }

    private fun updateNasCacheSummary() {
        if (!::btnNasCacheManagement.isInitialized ||
            !::btnNasCacheManagementEmpty.isInitialized
        ) {
            return
        }
        val summary = try {
            NasShareArchiveDownloader.cacheSummary(cacheDir)
        } catch (exception: Exception) {
            android.util.Log.e("ComicLabNAS", "Could not read NAS cache summary", exception)
            NasShareArchiveDownloader.CacheSummary(0, 0L)
        }
        val hasCache = summary.fileCount > 0 || summary.totalBytes > 0L
        btnNasCacheManagement.text = if (hasCache) {
            getString(
                R.string.nas_cache_button_usage,
                Formatter.formatShortFileSize(this, summary.totalBytes)
            )
        } else {
            getString(R.string.nas_cache_button_empty)
        }
        btnNasCacheManagement.isEnabled = !nasCacheManagementInProgress && !nasDownloadInProgress
        btnNasCacheManagementEmpty.isEnabled = btnNasCacheManagement.isEnabled
        btnNasCacheManagement.visibility = if (isNasSourceSelected && selectedNasProfile != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        btnNasCacheManagementEmpty.visibility = if (
            isNasSourceSelected && selectedNasProfile == null && hasCache
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showNasCacheManagementDialog() {
        if (nasCacheManagementInProgress || nasDownloadInProgress) return
        val summary = try {
            NasShareArchiveDownloader.cacheSummary(cacheDir)
        } catch (exception: Exception) {
            android.util.Log.e("ComicLabNAS", "Could not read NAS cache summary", exception)
            showMessage(R.string.nas_cache_read_failed)
            return
        }
        val hasCache = summary.fileCount > 0 || summary.totalBytes > 0L
        val message = if (hasCache) {
            getString(
                R.string.nas_cache_summary,
                Formatter.formatShortFileSize(this, summary.totalBytes),
                summary.fileCount
            )
        } else {
            getString(R.string.nas_cache_empty_message)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.nas_cache_title)
            .setMessage(message)
        if (!hasCache) {
            builder.setPositiveButton(android.R.string.ok, null).showRounded()
            return
        }

        val dialog = builder
            .setPositiveButton(R.string.nas_cache_clear, null)
            .setNeutralButton(R.string.nas_cache_manage_archives, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .createRounded()
        nasCacheClearDialog = dialog
        dialog.setOnDismissListener {
            if (nasCacheClearDialog === dialog) {
                nasCacheClearDialog = null
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                if (nasCacheManagementInProgress) return@setOnClickListener
                dialog.dismiss()
                showNasCachedArchivePickerDialog()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (nasCacheManagementInProgress) return@setOnClickListener
                nasCacheManagementInProgress = true
                updateNasCacheSummary()
                dialog.setMessage(getString(R.string.nas_cache_clearing))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                nasCacheExecutor.execute {
                    val outcome = try {
                        NasCacheClearOutcome.Completed(
                            NasShareArchiveDownloader.clearCache(cacheDir)
                        )
                    } catch (exception: Exception) {
                        NasCacheClearOutcome.Failed(exception)
                    }
                    runOnUiThread {
                        nasCacheManagementInProgress = false
                        if (isDestroyed || isFinishing) {
                            if (dialog.isShowing) dialog.dismiss()
                            return@runOnUiThread
                        }
                        updateNasCacheSummary()
                        if (nasCacheClearDialog === dialog) nasCacheClearDialog = null
                        if (dialog.isShowing) dialog.dismiss()

                        when (outcome) {
                            is NasCacheClearOutcome.Completed -> {
                                val result = outcome.result
                                if (result.completed) {
                                    showMessage(
                                        getString(
                                            R.string.nas_cache_clear_success,
                                            Formatter.formatShortFileSize(this, result.bytesFreed)
                                        )
                                    )
                                } else if (result.bytesFreed > 0L) {
                                    showMessage(
                                        getString(
                                            R.string.nas_cache_clear_partial,
                                            Formatter.formatShortFileSize(this, result.bytesFreed),
                                            Formatter.formatShortFileSize(this, result.bytesRemaining)
                                        )
                                    )
                                } else {
                                    showMessage(
                                        getString(
                                            R.string.nas_cache_clear_failed,
                                            Formatter.formatShortFileSize(this, result.bytesRemaining)
                                        )
                                    )
                                }
                            }

                            is NasCacheClearOutcome.Failed -> {
                                android.util.Log.e(
                                    "ComicLabNAS",
                                    "Could not clear NAS cache",
                                    outcome.exception
                                )
                                showMessage(R.string.nas_cache_clear_error)
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showNasCachedArchivePickerDialog() {
        if (nasCacheManagementInProgress || nasDownloadInProgress || isDestroyed || isFinishing) {
            return
        }
        val archives = try {
            NasShareArchiveDownloader.cachedArchives(cacheDir)
        } catch (exception: Exception) {
            android.util.Log.e("ComicLabNAS", "Could not list cached NAS archives", exception)
            showMessage(R.string.nas_cache_read_failed)
            return
        }
        if (archives.isEmpty()) {
            updateNasCacheSummary()
            showMessage(R.string.nas_cache_empty_message)
            return
        }

        val duplicateNames = archives.groupingBy { it.name }.eachCount()
        val labels = archives.map { archive ->
            val duplicateIdentity = if ((duplicateNames[archive.name] ?: 0) > 1) {
                " · ${archive.cacheIdentity.take(8)}"
            } else {
                ""
            }
            "${archive.name} · ${Formatter.formatShortFileSize(this, archive.sizeBytes)}$duplicateIdentity"
        }
        var selectedArchive: NasShareArchiveDownloader.CachedArchive? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.nas_cache_archive_picker_title)
            .setItems(labels.toTypedArray()) { _, index ->
                selectedArchive = archives.getOrNull(index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .createRounded()
        nasCachedArchivePickerDialog = dialog
        dialog.setOnDismissListener {
            if (nasCachedArchivePickerDialog === dialog) {
                nasCachedArchivePickerDialog = null
            }
            selectedArchive?.let { archive ->
                window.decorView.post {
                    if (!isDestroyed && !isFinishing) {
                        showNasCachedArchiveActionDialog(archive)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showNasCachedArchiveActionDialog(
        archive: NasShareArchiveDownloader.CachedArchive
    ) {
        if (nasCacheManagementInProgress || nasDownloadInProgress || isDestroyed || isFinishing) {
            return
        }
        var selectedAction: Int? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(archive.name)
            .setItems(
                arrayOf(
                    getString(R.string.nas_cache_archive_read_action),
                    getString(R.string.nas_cache_archive_delete_action)
                )
            ) { _, index ->
                selectedAction = index
            }
            .setNegativeButton(android.R.string.cancel, null)
            .createRounded()
        nasCachedArchiveActionDialog = dialog
        dialog.setOnDismissListener {
            if (nasCachedArchiveActionDialog === dialog) {
                nasCachedArchiveActionDialog = null
            }
            selectedAction?.let { action ->
                window.decorView.post {
                    if (isDestroyed || isFinishing) return@post
                    when (action) {
                        0 -> {
                            if (archive.file.isFile && archive.file.length() > 0L) {
                                openMangaPreview(archive.file)
                            } else {
                                updateNasCacheSummary()
                                showMessage(R.string.nas_cache_archive_open_failed)
                            }
                        }

                        1 -> showNasCachedArchiveDeleteConfirmationDialog(archive)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showNasCachedArchiveDeleteConfirmationDialog(
        archive: NasShareArchiveDownloader.CachedArchive
    ) {
        if (nasCacheManagementInProgress || nasDownloadInProgress || isDestroyed || isFinishing) {
            return
        }
        val message = getString(
            R.string.nas_cache_delete_confirm_message,
            archive.name,
            Formatter.formatShortFileSize(this, archive.sizeBytes)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.nas_cache_delete_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.nas_cache_delete_action, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .createRounded()
        nasCacheDeleteConfirmDialog = dialog
        dialog.setOnDismissListener {
            if (nasCacheDeleteConfirmDialog === dialog) {
                nasCacheDeleteConfirmDialog = null
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (nasCacheManagementInProgress) return@setOnClickListener
                nasCacheManagementInProgress = true
                updateNasCacheSummary()
                dialog.setMessage(getString(R.string.nas_cache_deleting, archive.name))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                nasCacheExecutor.execute {
                    val outcome = try {
                        NasCachedArchiveDeleteOutcome.Completed(
                            NasShareArchiveDownloader.deleteCachedArchive(cacheDir, archive)
                        )
                    } catch (exception: Exception) {
                        NasCachedArchiveDeleteOutcome.Failed(exception)
                    }
                    runOnUiThread {
                        nasCacheManagementInProgress = false
                        if (isDestroyed || isFinishing) {
                            if (dialog.isShowing) dialog.dismiss()
                            return@runOnUiThread
                        }
                        updateNasCacheSummary()
                        if (nasCacheDeleteConfirmDialog === dialog) {
                            nasCacheDeleteConfirmDialog = null
                        }
                        if (dialog.isShowing) dialog.dismiss()
                        when (outcome) {
                            is NasCachedArchiveDeleteOutcome.Completed -> showMessage(
                                getString(
                                    R.string.nas_cache_delete_success,
                                    archive.name,
                                    Formatter.formatShortFileSize(this, outcome.bytesFreed)
                                )
                            )

                            is NasCachedArchiveDeleteOutcome.Failed -> {
                                android.util.Log.e(
                                    "ComicLabNAS",
                                    "Could not delete cached NAS archive: ${archive.name}",
                                    outcome.exception
                                )
                                showMessage(R.string.nas_cache_delete_error)
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showNasDirectoryError(messageResId: Int, canRetry: Boolean) {
        nasDirectoryListView.visibility = View.GONE
        tvNasEmptyHint.visibility = View.GONE
        tvNasBrowserStatus.setText(messageResId)
        btnNasRetry.visibility = if (canRetry) View.VISIBLE else View.GONE
        nasStatusPanel.visibility = View.VISIBLE
    }

    private fun clearNasListTouchState(view: View) {
        view.isPressed = false
        view.isSelected = false
        view.isActivated = false
        nasDirectoryListView.isPressed = false
        nasDirectoryListView.isSelected = false
    }

    private fun openNasShareSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SETTINGS_SECTION, SettingsActivity.SETTINGS_SECTION_NAS)
        )
    }

    private fun pastePendingFile() {
        fileTransferWorkflowController.pastePendingFile()
    }













    private fun startFileTransfer(
        transfer: PendingFileTransfer,
        destinationDirectory: File,
        targetName: String
    ) {
        fileTransferWorkflowController.startFileTransfer(transfer, destinationDirectory, targetName)
    }





    private fun exitPendingFileTransfer() {
        fileTransferWorkflowController.exitPendingFileTransfer()
    }

    internal fun showFileOperationProgress(): FileOperationProgressViews {
        val content = layoutInflater.inflate(R.layout.dialog_file_operation_progress, null)
        val views = FileOperationProgressViews(
            dialog = AlertDialog.Builder(this)
                .setTitle(R.string.file_operation_progress_title)
                .setView(content)
                .setCancelable(false)
                .createRounded(),
            status = content.findViewById(R.id.tvFileOperationProgressStatus),
            progressBar = content.findViewById(R.id.progressFileOperation),
            count = content.findViewById(R.id.tvFileOperationProgressCount)
        )
        views.progressBar.isIndeterminate = true
        views.dialog.show()
        return views
    }

    internal fun createFileOperationProgressReporter(
        views: FileOperationProgressViews
    ): (FileOperationProgress) -> Unit {
        var lastUpdateAt = 0L
        var lastPhase: FileOperationProgress.Phase? = null
        return { progress ->
            val now = SystemClock.uptimeMillis()
            val phaseChanged = progress.phase != lastPhase
            val operationComplete = progress.totalItems > 0 &&
                progress.completedItems >= progress.totalItems
            val timeToUpdate = now - lastUpdateAt >= PROGRESS_UI_UPDATE_INTERVAL_MS
            if (phaseChanged || operationComplete || timeToUpdate) {
                lastPhase = progress.phase
                lastUpdateAt = now
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && views.dialog.isShowing) {
                        updateFileOperationProgress(views, progress)
                    }
                }
            }
        }
    }

    private fun updateFileOperationProgress(
        views: FileOperationProgressViews,
        progress: FileOperationProgress
    ) {
        val itemName = progress.currentItemName.orEmpty()
        views.status.text = when (progress.phase) {
            FileOperationProgress.Phase.SCANNING -> if (itemName.isBlank()) {
                getString(R.string.file_operation_scanning)
            } else {
                getString(R.string.file_operation_scanning_item, itemName)
            }

            FileOperationProgress.Phase.COPYING -> if (itemName.isBlank()) {
                getString(R.string.file_operation_copying)
            } else {
                getString(R.string.file_operation_copying_item, itemName)
            }

            FileOperationProgress.Phase.DELETING_SOURCE -> if (itemName.isBlank()) {
                getString(R.string.file_operation_deleting_source)
            } else {
                getString(R.string.file_operation_deleting_source_item, itemName)
            }

            FileOperationProgress.Phase.DELETING -> if (itemName.isBlank()) {
                getString(R.string.file_operation_deleting)
            } else {
                getString(R.string.file_operation_deleting_item, itemName)
            }
        }

        val percentage = FileOperationProgressPolicy.percentage(progress)
        views.progressBar.isIndeterminate = percentage == null
        if (percentage != null) {
            views.progressBar.progress = percentage
        }
        views.count.text = when {
            progress.phase == FileOperationProgress.Phase.SCANNING -> {
                getString(R.string.file_operation_scanned_items, progress.completedItems)
            }

            progress.phase == FileOperationProgress.Phase.COPYING && progress.totalBytes > 0 -> {
                getString(
                    R.string.file_operation_copy_progress,
                    Formatter.formatShortFileSize(this, progress.completedBytes.coerceAtLeast(0)),
                    Formatter.formatShortFileSize(this, progress.totalBytes),
                    progress.completedItems,
                    progress.totalItems
                )
            }

            progress.totalItems > 0 -> getString(
                if (progress.phase == FileOperationProgress.Phase.COPYING) {
                    R.string.file_operation_item_progress
                } else {
                    R.string.file_operation_deleted_items
                },
                progress.completedItems,
                progress.totalItems
            )

            else -> getString(R.string.file_operation_indeterminate)
        }
    }

    internal fun dismissFileOperationProgress(views: FileOperationProgressViews) {
        if (views.dialog.isShowing) {
            views.dialog.dismiss()
        }
    }

    private fun showArchiveMenu(file: File) {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_archive_actions, null)
        val tvArchiveActionTitle = content.findViewById<TextView>(R.id.tvArchiveActionTitle)
        val btnCopyFileName = content.findViewById<TextView>(R.id.btnCopyFileName)
        val btnRenameFileName = content.findViewById<TextView>(R.id.btnRenameFileName)
        val btnCopyStartMarker = content.findViewById<TextView>(R.id.btnCopyStartMarker)
        val btnCopyFile = content.findViewById<TextView>(R.id.btnCopyFile)
        val btnCutFile = content.findViewById<TextView>(R.id.btnCutFile)
        val btnFavoriteComic = content.findViewById<TextView>(R.id.btnFavoriteComicAction)
        val btnRead = content.findViewById<TextView>(R.id.btnReadComic)
        val btnEnhance = content.findViewById<TextView>(R.id.btnEnhanceComic)
        val btnDeleteFile = content.findViewById<TextView>(R.id.btnDeleteFile)
        val isFavorite = FavoriteComicStore.isFavorite(this, file)

        tvArchiveActionTitle.text = file.name
        sizeBottomSheetActionIcons(
            btnRead,
            btnEnhance,
            btnFavoriteComic,
            btnCopyFileName,
            btnRenameFileName,
            btnCopyStartMarker,
            btnCopyFile,
            btnCutFile,
            btnDeleteFile
        )
        val canStartTransfer = pendingFileTransfer == null && !isFileTransferInProgress
        btnEnhance.visibility = if (ComicArchive.isSupportedArchive(file) &&
            !file.extension.equals("pdf", true)) View.VISIBLE else View.GONE
        val isPendingSource = pendingFileTransfer?.sourcePath == file.absolutePath
        btnCopyFile.isEnabled = canStartTransfer
        btnCutFile.isEnabled = canStartTransfer
        btnCopyFile.alpha = if (canStartTransfer) 1f else 0.38f
        btnCutFile.alpha = if (canStartTransfer) 1f else 0.38f
        btnRenameFileName.isEnabled = !isPendingSource
        btnDeleteFile.isEnabled = !isPendingSource
        btnRenameFileName.alpha = if (isPendingSource) 0.38f else 1f
        btnDeleteFile.alpha = if (isPendingSource) 0.38f else 1f
        btnFavoriteComic.text = getString(
            if (isFavorite) R.string.cancel_favorite else R.string.favorite_comic_action
        )

        btnCopyFileName.setOnClickListener {
            copyToClipboard(
                label = getString(R.string.copy_file_name),
                text = file.name,
                copiedMessage = getString(R.string.copied_file_name)
            )
            dialog.dismiss()
        }

        btnRenameFileName.setOnClickListener {
            dialog.dismiss()
            showRenameFileDialog(file)
        }

        btnCopyStartMarker.setOnClickListener {
            val marker = CommonFunc.extractStartMarker(
                file.name,
                AppSettings.getStartMarkerErrorTags(this)
            )
            if (marker.isEmpty()) {
                showMessage(getString(R.string.start_marker_not_found))
            } else {
                copyToClipboard(
                    label = getString(R.string.copy_start_marker),
                    text = marker,
                    copiedMessage = getString(R.string.copied_start_marker)
                )
            }
            dialog.dismiss()
        }

        btnFavoriteComic.setOnClickListener {
            if (!ReaderFileDetector.isSupported(file)) {
                showMessage(getString(R.string.unsupported_archive_format))
                dialog.dismiss()
                return@setOnClickListener
            }

            if (FavoriteComicStore.isFavorite(this, file)) {
                FavoriteComicStore.remove(this, file)
                showMessage(getString(R.string.favorite_comic_removed))
                notifyListChanged()
                dialog.dismiss()
                return@setOnClickListener
            }

            val result = FavoriteComicStore.record(this, file)
            when (result) {
                FavoriteComicStore.RecordResult.ADDED,
                FavoriteComicStore.RecordResult.ALREADY_EXISTS -> {
                    showMessage(getString(R.string.favorite_comic_added))
                    notifyListChanged()
                }

                FavoriteComicStore.RecordResult.LIMIT_REACHED -> {
                    showMessage(getString(R.string.favorite_comic_limit_reached))
                }

                FavoriteComicStore.RecordResult.INVALID -> {
                    showMessage(getString(R.string.unsupported_archive_format))
                }

                FavoriteComicStore.RecordResult.STORAGE_ERROR -> {
                    showMessage(getString(R.string.stored_collection_unreadable))
                }
            }
            dialog.dismiss()
        }

        btnRead.setOnClickListener {
            dialog.dismiss()
            openMangaPreview(file)
        }

        btnEnhance.setOnClickListener {
            dialog.dismiss()
            ArchiveEnhancementDialog.show(this, file)
        }

        btnCopyFile.setOnClickListener {
            dialog.dismiss()
            beginFileTransfer(file, FileTransferMode.COPY)
        }

        btnCutFile.setOnClickListener {
            dialog.dismiss()
            beginFileTransfer(file, FileTransferMode.CUT)
        }

        btnDeleteFile.setOnClickListener {
            dialog.dismiss()
            confirmDeleteFile(file)
        }

        dialog.showRoundedContent(content)
    }

    private fun confirmDeleteFile(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_file_title)
            .setMessage(R.string.delete_file_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                deleteFileFromBrowser(file)
            }
            .setNegativeButton(R.string.no, null)
            .showRounded()
    }

    private fun deleteFileFromBrowser(file: File) {
        if (isPendingFileTransferSource(file)) {
            showMessage(getString(R.string.file_transfer_source_in_use))
            return
        }
        if (!file.isFile) {
            showMessage(getString(R.string.message_invalid_file))
            loadCurrentDirectory(scrollStateToRestore = captureFileListScrollState())
            return
        }

        val scrollState = captureFileListScrollState()
        val progressViews = showFileOperationProgress()
        val reportProgress = createFileOperationProgressReporter(progressViews)
        directoryLoadExecutor.execute {
            reportProgress(
                FileOperationProgress(
                    phase = FileOperationProgress.Phase.DELETING,
                    currentItemName = file.name
                )
            )
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            if (deleted) {
                reportProgress(
                    FileOperationProgress(
                        phase = FileOperationProgress.Phase.DELETING,
                        completedItems = 1,
                        totalItems = 1,
                        currentItemName = file.name
                    )
                )
            }
            runOnUiThread {
                dismissFileOperationProgress(progressViews)
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                if (deleted) {
                    FavoriteComicStore.remove(this, file)
                    ReadingHistoryStore.remove(this, file)
                    invalidateDirectoryChildCount(File(currentPath))
                    showMessage(getString(R.string.delete_file_success))
                    loadCurrentDirectory(scrollStateToRestore = scrollState)
                } else {
                    showMessage(getString(R.string.delete_file_failed))
                }
            }
        }
    }

    private fun confirmDeleteDirectory(directory: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_directory_title)
            .setMessage(R.string.delete_directory_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                deleteDirectoryFromBrowser(directory)
            }
            .setNegativeButton(R.string.no, null)
            .showRounded()
    }

    private fun deleteDirectoryFromBrowser(directory: File) {
        if (!directory.isDirectory ||
            File(directory.absolutePath).absolutePath == File(STORAGE_ROOT_PATH).absolutePath
        ) {
            showMessage(getString(R.string.message_invalid_directory))
            loadCurrentDirectory(scrollStateToRestore = captureFileListScrollState())
            return
        }
        if (containsPendingFileTransferSource(directory)) {
            showMessage(getString(R.string.file_transfer_source_in_use))
            return
        }

        val scrollState = captureFileListScrollState()
        val progressViews = showFileOperationProgress()
        val reportProgress = createFileOperationProgressReporter(progressViews)
        directoryLoadExecutor.execute {
            val deleted = DirectoryFileTree.deleteRecursively(directory, reportProgress)
            runOnUiThread {
                dismissFileOperationProgress(progressViews)
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }
                if (deleted) {
                    FavoritePathStore.remove(this, directory)
                    AppSettings.removeBookcaseDirectory(this, directory)
                    invalidateDirectoryChildCount(File(currentPath))
                    invalidateDirectoryChildCount(directory)
                    showMessage(getString(R.string.delete_directory_success))
                    loadCurrentDirectory(scrollStateToRestore = scrollState)
                } else {
                    invalidateDirectoryChildCount(directory)
                    showMessage(getString(R.string.delete_directory_failed))
                }
            }
        }
    }

    private fun showRenameFileDialog(file: File) {
        if (!file.isFile) {
            showMessage(getString(R.string.message_invalid_file))
            return
        }

        val input = EditText(this).apply {
            setSingleLine(true)
            setText(file.nameWithoutExtension)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.rename_file_name_title)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                renameArchiveFile(file, input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showRounded()
    }

    private fun renameArchiveFile(file: File, rawName: String) {
        if (isPendingFileTransferSource(file)) {
            showMessage(getString(R.string.file_transfer_source_in_use))
            return
        }
        val parent = file.parentFile ?: run {
            showMessage(getString(R.string.rename_file_name_failed))
            return
        }
        val trimmedName = rawName.trim()
        if (trimmedName.isEmpty()) {
            showMessage(getString(R.string.rename_file_name_empty))
            return
        }
        if (trimmedName.any { it.code < 32 || it in INVALID_FILE_NAME_CHARS }) {
            showMessage(getString(R.string.rename_file_name_invalid))
            return
        }

        val extension = file.extension
        val newFileName = when {
            extension.isBlank() -> trimmedName
            trimmedName.endsWith(".$extension", ignoreCase = true) -> trimmedName
            else -> "$trimmedName.$extension"
        }
        if (newFileName == file.name) {
            return
        }

        val targetFile = File(parent, newFileName)
        if (targetFile.exists()) {
            showMessage(getString(R.string.rename_file_name_exists))
            return
        }

        val wasFavorite = FavoriteComicStore.isFavorite(this, file)
        val renamed = file.renameTo(targetFile)
        if (!renamed) {
            showMessage(getString(R.string.rename_file_name_failed))
            return
        }

        if (wasFavorite) {
            FavoriteComicStore.remove(this, file)
            FavoriteComicStore.record(this, targetFile)
        }
        ReadingHistoryStore.moveFile(this, file, targetFile)
        ReaderProgressStore.moveFile(this, file, targetFile)

        invalidateDirectoryChildCount(parent)
        showMessage(getString(R.string.rename_file_name_success))
        loadCurrentDirectory(targetFile.absolutePath)
    }

    private fun sizeBottomSheetActionIcons(vararg actions: TextView) {
        val iconSize = resources.getDimensionPixelSize(R.dimen.bottom_sheet_action_icon_size)
        actions.forEach { action ->
            val drawables = action.compoundDrawablesRelative
            val startDrawable = drawables[0] ?: return@forEach
            startDrawable.setBounds(0, 0, iconSize, iconSize)
            action.setCompoundDrawablesRelative(
                startDrawable,
                drawables[1],
                drawables[2],
                drawables[3]
            )
        }
    }

    private fun openMangaPreview(file: File) {
        when (ReaderFileDetector.typeOf(file)) {
            ReaderFileType.MOBI,
            ReaderFileType.EPUB,
            ReaderFileType.TXT -> {
                startActivity(
                    Intent(this, EbookPreviewActivity::class.java).apply {
                        putExtra(EbookPreviewActivity.EXTRA_BOOK_PATH, file.absolutePath)
                    }
                )
            }

            ReaderFileType.IMAGE_ARCHIVE,
            ReaderFileType.PDF -> {
                startActivity(
                    Intent(this, MangaPreviewActivity::class.java).apply {
                        putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
                    }
                )
            }

            null -> showMessage(getString(R.string.unsupported_archive_format))
        }
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        if (extension.isBlank()) {
            return "*/*"
        }

        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    private fun notifyListChanged() {
        fileListAdapter.notifyDataSetChanged()
    }

    private fun clearFileListTouchState(clickedView: View? = null) {
        if (clickedView == null) {
            resetFileListState()
            return
        }

        clickedView.postDelayed({
            resetFileListState(clickedView)
        }, CLEAR_CLICK_STATE_DELAY_MS)
    }

    private fun resetFileListState(clickedView: View? = null) {
        listView.clearChoices()
        listView.isPressed = false
        listView.isSelected = false
        listView.isActivated = false

        clickedView?.let { resetViewState(it) }
        for (index in 0 until listView.childCount) {
            resetViewState(listView.getChildAt(index))
        }
    }

    private fun resetViewState(view: View) {
        view.isPressed = false
        view.isSelected = false
        view.isActivated = false
    }

    private fun copyToClipboard(label: String, text: String, copiedMessage: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        showMessage(copiedMessage)
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(@StringRes messageResId: Int) {
        showMessage(getString(messageResId))
    }

    private fun setCurrentPath(path: String) {
        captureFileListScrollState()?.let(directoryScrollStateCache::save)
        currentPath = File(path).absolutePath
        saveCurrentPath()
    }

    private fun saveCurrentPath() {
        prefs.edit()
            .putString(KEY_CURRENT_PATH, currentPath)
            .apply()
    }

    private fun savedCurrentPath(): String {
        val originalStorageRoot = File(STORAGE_ROOT_PATH)
        val canonicalStorageRoot = runCatching { originalStorageRoot.canonicalFile }
            .getOrNull()
        val savedPath = prefs.getString(KEY_CURRENT_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.let { canonicalPathWithinStorageRoot(it.path) }
            ?.takeIf { it.isDirectory }
        if (savedPath == null || savedPath == canonicalStorageRoot) {
            return originalStorageRoot.absolutePath
        }
        return savedPath.absolutePath
    }

    private fun Bundle.readFileListScrollState(): FileListScrollState? {
        val directoryPath = getString(KEY_FILE_LIST_SCROLL_DIRECTORY_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val firstVisiblePosition = getInt(KEY_FILE_LIST_SCROLL_POSITION, -1)
        if (firstVisiblePosition < 0) {
            return null
        }

        return FileListScrollState(
            directoryPath = directoryPath,
            firstVisiblePosition = firstVisiblePosition,
            firstVisibleTop = getInt(KEY_FILE_LIST_SCROLL_TOP, 0),
            anchorPath = getString(KEY_FILE_LIST_SCROLL_ANCHOR_PATH),
            anchorIsParent = getBoolean(KEY_FILE_LIST_SCROLL_ANCHOR_IS_PARENT, false)
        )
    }

    @Suppress("DEPRECATION")
    private fun Bundle.readFileListScrollStates(): List<FileListScrollState> {
        return getParcelableArrayList<Bundle>(KEY_FILE_LIST_SCROLL_STATES)
            .orEmpty()
            .mapNotNull { it.readFileListScrollState() }
    }

    private fun FileListScrollState.writeTo(outState: Bundle) {
        outState.putString(KEY_FILE_LIST_SCROLL_DIRECTORY_PATH, directoryPath)
        outState.putInt(KEY_FILE_LIST_SCROLL_POSITION, firstVisiblePosition)
        outState.putInt(KEY_FILE_LIST_SCROLL_TOP, firstVisibleTop)
        outState.putString(KEY_FILE_LIST_SCROLL_ANCHOR_PATH, anchorPath)
        outState.putBoolean(KEY_FILE_LIST_SCROLL_ANCHOR_IS_PARENT, anchorIsParent)
    }

    companion object {
        private const val PREFS_NAME = "saf_prefs"
        const val EXTRA_CENTER_TARGET_PATH = "center_target_path"
        private const val KEY_STORAGE_PERMISSION_PROMPTED = "storage_permission_prompted"
        private const val KEY_CURRENT_PATH = "current_path"
        private const val KEY_FILE_LIST_SCROLL_DIRECTORY_PATH = "file_list_scroll_directory_path"
        private const val KEY_FILE_LIST_SCROLL_POSITION = "file_list_scroll_position"
        private const val KEY_FILE_LIST_SCROLL_TOP = "file_list_scroll_top"
        private const val KEY_FILE_LIST_SCROLL_ANCHOR_PATH = "file_list_scroll_anchor_path"
        private const val KEY_FILE_LIST_SCROLL_ANCHOR_IS_PARENT = "file_list_scroll_anchor_is_parent"
        private const val KEY_FILE_LIST_SCROLL_STATES = "file_list_scroll_states"
        private const val KEY_PENDING_FILE_TRANSFER_PATH = "pending_file_transfer_path"
        private const val KEY_PENDING_FILE_TRANSFER_MODE = "pending_file_transfer_mode"
        private const val KEY_PENDING_FILE_TRANSFER_IS_DIRECTORY = "pending_file_transfer_is_directory"
        private const val CLEAR_CLICK_STATE_DELAY_MS = 240L
        private const val PROGRESS_UI_UPDATE_INTERVAL_MS = 120L
        private const val NAS_DOWNLOAD_PROGRESS_UPDATE_INTERVAL_MS = 250L
        private val INVALID_FILE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        private const val MENU_CLASSIFY_BY_START_MARKER = 1
        private const val MENU_FIND_SIMILAR_DIRECTORY_NAMES = 2
        private const val MENU_MERGE_COMICS_NON_RECURSIVE = 3
        private const val MENU_MERGE_FILES_NON_RECURSIVE = 4
        private val STORAGE_ROOT_PATH: String
            get() {
                val root = Environment.getExternalStorageDirectory()
                return runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            }
    }
}
