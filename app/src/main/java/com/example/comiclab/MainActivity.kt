package com.example.comiclab

import com.example.comiclab.ui.browser.organize.ComicOrganizeController
import com.example.comiclab.ui.browser.refresh.DirectoryRefreshController
import com.example.comiclab.ui.browser.toolbar.RoundedPopupMenuItem
import com.example.comiclab.ui.browser.toolbar.SortAndSearchController
import com.example.comiclab.ui.browser.toolbar.showRoundedPopupMenu
import com.example.comiclab.ui.browser.panels.SidePanelController
import com.example.comiclab.ui.common.SystemBars

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
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
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
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.ebook.ReaderFileDetector
import com.example.comiclab.ebook.ReaderFileType
import com.example.comiclab.ui.browser.FileListAdapter
import com.example.comiclab.ui.browser.DirectoryBrowserController
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.ui.browser.transfer.FileTransferController
import com.example.comiclab.ui.browser.transfer.FileTransferMetadataUpdatePolicy
import com.example.comiclab.ui.browser.transfer.FileTransferMode
import com.example.comiclab.ui.browser.transfer.PendingFileTransfer
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
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private data class FileOperationProgressViews(
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
    private lateinit var fileListAdapter: FileListAdapter

    private val fileItems = mutableListOf<FileItem>()
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
    private val directoryLoadGeneration = AtomicInteger(0)
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
        pathInputProvider = { etPath },
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
    private var currentPath = STORAGE_ROOT_PATH
    private val fileTransferController = FileTransferController()
    private val pendingFileTransfer: PendingFileTransfer?
        get() = fileTransferController.pendingTransfer
    private val isFileTransferInProgress: Boolean
        get() = fileTransferController.isTransferInProgress

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
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
        clearFileListTouchState()
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
        directoryRefreshController.removePendingFirstPreDrawListener()
        classifyGeneration.incrementAndGet()
        directoryLoadExecutor.shutdownNow()
        directoryChildCountExecutor.shutdownNow()
        pendingDirectoryChildCountPaths.clear()
        classifyExecutor.shutdownNow()
        fileTransferExecutor.shutdownNow()
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
        browserInitialized = true
        openDirectoryContainingTarget(targetPath)
        return true
    }

    private fun openDirectoryContainingTarget(targetPath: String) {
        val storageRoot = File(STORAGE_ROOT_PATH)
        val target = File(targetPath)
        val targetAbsolutePath = target.absolutePath
        if (targetAbsolutePath == storageRoot.absolutePath) {
            setCurrentPath(storageRoot.absolutePath)
            loadCurrentDirectory()
            return
        }

        val parentDirectory = target.parentFile
            ?.takeIf { it.isDirectory }
            ?: target.takeIf { it.isDirectory }
            ?: storageRoot
        setCurrentPath(parentDirectory.absolutePath)
        loadCurrentDirectory(targetAbsolutePath)
    }

    private fun goParent() {
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

    // 模拟目录剪切完成的回调路径，与原测试的调用顺序一致：先显示进度，再处理结果。
    private var progressViewsForTest: FileOperationProgressViews? = null

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

    private fun isPendingFileTransferSource(file: File): Boolean {
        val transfer = pendingFileTransfer ?: return false
        val source = File(transfer.sourcePath)
        return if (transfer.isDirectory) {
            isSamePathOrDescendant(file, source)
        } else {
            file.absoluteFile.toPath().normalize() == source.absoluteFile.toPath().normalize()
        }
    }

    private fun containsPendingFileTransferSource(directory: File): Boolean {
        val transfer = pendingFileTransfer ?: return false
        val source = File(transfer.sourcePath)
        return isSamePathOrDescendant(source, directory) ||
            (transfer.isDirectory && isSamePathOrDescendant(directory, source))
    }

    private fun isSamePathOrDescendant(candidate: File, directory: File): Boolean {
        return candidate.absoluteFile.toPath().normalize()
            .startsWith(directory.absoluteFile.toPath().normalize())
    }

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
        if (pendingFileTransfer != null || isFileTransferInProgress) {
            return
        }
        if (!file.isFile && !file.isDirectory) {
            showMessage(getString(R.string.file_transfer_source_missing))
            return
        }

        val transfer = PendingFileTransfer(file.absolutePath, mode, file.isDirectory)
        if (fileTransferController.begin(transfer)) {
            updateFileTransferToolbar()
        }
    }

    private fun updateFileTransferToolbar() {
        val isActive = pendingFileTransfer != null
        val normalToolbarButtons = arrayOf(
            btnFavoritePaths,
            btnFavoriteComics,
            btnReadingHistory,
            btnSort,
            btnClassify,
            btnSearch,
            btnBack
        )
        normalToolbarButtons.forEach { button ->
            button.visibility = if (isActive) View.GONE else View.VISIBLE
        }

        btnHelp.isEnabled = !isActive
        btnOptions.isEnabled = !isActive
        btnHelp.alpha = if (isActive) 0.38f else 1f
        btnOptions.alpha = if (isActive) 0.38f else 1f

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
        listView.isEnabled = !isFileTransferInProgress
    }

    private fun pastePendingFile() {
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

        val input = EditText(this).apply {
            setSingleLine(true)
            setText(
                DirectoryTransferOperations.suggestedName(
                    sourceDirectory.name,
                    destinationDirectory
                )
            )
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
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

        val input = EditText(this).apply {
            setSingleLine(true)
            setText(FileTransferOperations.suggestedName(sourceFile.name, destinationDirectory))
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
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

    private fun showFileOperationProgress(): FileOperationProgressViews {
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

    private fun createFileOperationProgressReporter(
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

    private fun dismissFileOperationProgress(views: FileOperationProgressViews) {
        if (views.dialog.isShowing) {
            views.dialog.dismiss()
        }
    }

    private fun startFileTransfer(
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
            FavoriteComicStore.isFavorite(this, sourceFile)
        val wasInReadingHistory = transfer.mode == FileTransferMode.CUT &&
            ReadingHistoryStore.items(this).any { it.file.absolutePath == sourceFile.absolutePath }
        if (!fileTransferController.startPaste(transfer)) {
            return
        }
        updateFileTransferToolbar()
        val progressViews = showFileOperationProgress()
        val reportProgress = createFileOperationProgressReporter(progressViews)
        fileTransferExecutor.execute {
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
                fileTransferController.finishPaste(
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
        if (!fileTransferController.startPaste(transfer)) {
            return
        }
        updateFileTransferToolbar()
        val progressViews = showFileOperationProgress()
        val reportProgress = createFileOperationProgressReporter(progressViews)
        fileTransferExecutor.execute {
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
        fileTransferController.finishPaste(transfer, result)
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

    private fun exitPendingFileTransfer() {
        if (fileTransferController.exit()) updateFileTransferToolbar()
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
        val btnDeleteFile = content.findViewById<TextView>(R.id.btnDeleteFile)
        val isFavorite = FavoriteComicStore.isFavorite(this, file)

        tvArchiveActionTitle.text = file.name
        sizeBottomSheetActionIcons(
            btnRead,
            btnFavoriteComic,
            btnCopyFileName,
            btnRenameFileName,
            btnCopyStartMarker,
            btnCopyFile,
            btnCutFile,
            btnDeleteFile
        )
        val canStartTransfer = pendingFileTransfer == null && !isFileTransferInProgress
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
            }
            dialog.dismiss()
        }

        btnRead.setOnClickListener {
            dialog.dismiss()
            openMangaPreview(file)
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
        val savedPath = prefs.getString(KEY_CURRENT_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?.absolutePath
        return savedPath ?: STORAGE_ROOT_PATH
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
        private const val PROGRESS_UI_UPDATE_INTERVAL_MS = 120L
        private const val CLEAR_CLICK_STATE_DELAY_MS = 240L
        private val INVALID_FILE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        private const val MENU_CLASSIFY_BY_START_MARKER = 1
        private const val MENU_FIND_SIMILAR_DIRECTORY_NAMES = 2
        private const val MENU_MERGE_COMICS_NON_RECURSIVE = 3
        private const val MENU_MERGE_FILES_NON_RECURSIVE = 4
        private val STORAGE_ROOT_PATH = Environment.getExternalStorageDirectory().absolutePath
    }
}
