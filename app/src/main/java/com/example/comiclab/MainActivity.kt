package com.example.comiclab

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.comiclab.ebook.ReaderFileDetector
import com.example.comiclab.ebook.ReaderFileType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var etPath: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnOptions: ImageButton
    private lateinit var btnSort: ImageButton
    private lateinit var btnClassify: ImageButton
    private lateinit var btnSearch: ImageButton
    private lateinit var btnReadingHistory: ImageButton
    private lateinit var btnFavoriteComics: ImageButton
    private lateinit var btnFavoritePaths: ImageButton
    private lateinit var readingHistoryScrim: View
    private lateinit var fileListAdapter: FileListAdapter

    private val fileItems = mutableListOf<FileItem>()
    private val directoryLoadExecutor = Executors.newSingleThreadExecutor()
    private val classifyExecutor = Executors.newSingleThreadExecutor()
    private val directoryLoadGeneration = AtomicInteger(0)
    private val classifyGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val showReadingHistoryScrimRunnable = Runnable {
        showReadingHistoryScrimNow()
    }
    private val hideReadingHistoryScrimRunnable = Runnable {
        hideReadingHistoryScrimNow()
    }

    private var readingHistoryPopupWindow: PopupWindow? = null
    private var readingHistoryAdapter: ReadingHistoryAdapter? = null
    private var favoriteComicsPopupWindow: PopupWindow? = null
    private var favoriteComicAdapter: FavoriteComicAdapter? = null
    private var favoritePathsPopupWindow: PopupWindow? = null
    private var favoritePathAdapter: FavoritePathAdapter? = null
    @Volatile
    private var isClassifyingComics = false
    @Volatile
    private var isBuildingDirectorySimilarityReport = false
    @Volatile
    private var isMergingComics = false
    @Volatile
    private var isMergingFiles = false
    private var browserInitialized = false
    private var skipNextResumeDirectoryReload = false
    private var pendingCenterTargetPath: String? = null
    private var currentPath = STORAGE_ROOT_PATH

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
        btnOptions = findViewById(R.id.btnOptions)
        btnSort = findViewById(R.id.btnSort)
        btnClassify = findViewById(R.id.btnClassify)
        btnSearch = findViewById(R.id.btnSearch)
        btnReadingHistory = findViewById(R.id.btnReadingHistory)
        btnFavoriteComics = findViewById(R.id.btnFavoriteComics)
        btnFavoritePaths = findViewById(R.id.btnFavoritePaths)
        readingHistoryScrim = findViewById(R.id.readingHistoryScrim)

        fileListAdapter = FileListAdapter(this, fileItems)
        listView.adapter = fileListAdapter
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleSystemBack()
            }
        })

        btnBack.setOnClickListener {
            goStorageRoot()
        }

        btnOptions.setOnClickListener {
            openSettings()
        }

        btnReadingHistory.setOnClickListener {
            showReadingHistoryPanel()
        }

        readingHistoryScrim.setOnClickListener {
            dismissSidePanels()
        }

        btnFavoriteComics.setOnClickListener {
            showFavoriteComicsPanel()
        }

        btnFavoritePaths.setOnClickListener {
            showFavoritePathsPanel()
        }

        btnSort.setOnClickListener {
            showSortDialog()
        }

        btnClassify.setOnClickListener {
            showClassifyMenu()
        }

        btnSearch.setOnClickListener {
            openSearch()
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
            dismissSidePanels()
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
        dismissSidePanels()
        clearFileListTouchState()
        saveCurrentPath()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureFileListScrollState()?.writeTo(outState)
    }

    override fun onDestroy() {
        directoryLoadGeneration.incrementAndGet()
        classifyGeneration.incrementAndGet()
        mainHandler.removeCallbacks(showReadingHistoryScrimRunnable)
        mainHandler.removeCallbacks(hideReadingHistoryScrimRunnable)
        directoryLoadExecutor.shutdownNow()
        classifyExecutor.shutdownNow()
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

    private fun showReadingHistoryPanel() {
        val rootView = findViewById<View>(R.id.main)
        val screenWidth = rootView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 3 / 4)
            .coerceAtLeast(dpToPx(MIN_READING_HISTORY_PANEL_WIDTH_DP))
        val content = layoutInflater.inflate(R.layout.panel_reading_history, null)
        val listReadingHistory = content.findViewById<RecyclerView>(R.id.listReadingHistory)
        val tvReadingHistoryEmpty = content.findViewById<TextView>(R.id.tvReadingHistoryEmpty)
        val btnClearReadingHistory = content.findViewById<ImageButton>(R.id.btnClearReadingHistory)
        val historyItems = ReadingHistoryStore.items(this).toMutableList()
        val historyAdapter = ReadingHistoryAdapter(
            context = this,
            items = historyItems,
            onItemClick = { item ->
                dismissReadingHistoryPanel()
                openMangaPreview(item.file)
            },
            onItemsEmptyChanged = { isEmpty ->
                listReadingHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
                tvReadingHistoryEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            }
        )

        listReadingHistory.layoutManager = LinearLayoutManager(this)
        listReadingHistory.addItemDecoration(
            InsetDividerItemDecoration(
                context = this,
                skipAdjacentViewTypes = setOf(READING_HISTORY_DATE_HEADER_VIEW_TYPE)
            )
        )
        listReadingHistory.adapter = historyAdapter
        listReadingHistory.visibility = if (historyItems.isEmpty()) View.GONE else View.VISIBLE
        tvReadingHistoryEmpty.visibility = if (historyItems.isEmpty()) View.VISIBLE else View.GONE
        btnClearReadingHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_reading_history_title)
                .setMessage(R.string.clear_reading_history_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    ReadingHistoryStore.clear(this)
                    historyAdapter.clearItems()
                }
                .setNegativeButton(R.string.no, null)
                .showRounded()
        }

        dismissSidePanels()
        scheduleShowReadingHistoryScrim()
        readingHistoryAdapter = historyAdapter
        readingHistoryPopupWindow = PopupWindow(
            content,
            panelWidth,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            animationStyle = R.style.Animation_ComicLab_ReadingHistoryPanel
            elevation = dpToPx(READING_HISTORY_PANEL_ELEVATION_DP).toFloat()
            setOnDismissListener {
                historyAdapter.close()
                if (readingHistoryAdapter === historyAdapter) {
                    readingHistoryAdapter = null
                    readingHistoryPopupWindow = null
                    scheduleHideReadingHistoryScrim()
                }
            }
            showAtLocation(rootView, Gravity.END or Gravity.TOP, 0, 0)
        }
    }

    private fun showFavoritePathsPanel() {
        val rootView = findViewById<View>(R.id.main)
        val screenWidth = rootView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 3 / 4)
            .coerceAtLeast(dpToPx(MIN_READING_HISTORY_PANEL_WIDTH_DP))
        val content = layoutInflater.inflate(R.layout.panel_favorite_paths, null)
        val listFavoritePaths = content.findViewById<RecyclerView>(R.id.listFavoritePaths)
        val tvFavoritePathsEmpty = content.findViewById<TextView>(R.id.tvFavoritePathsEmpty)
        val btnClearFavoritePaths = content.findViewById<ImageButton>(R.id.btnClearFavoritePaths)
        val favoriteItems = FavoritePathStore.items(this).toMutableList()
        val adapter = FavoritePathAdapter(
            context = this,
            items = favoriteItems,
            onItemClick = { item ->
                dismissFavoritePathsPanel()
                setCurrentPath(item.directory.absolutePath)
                loadCurrentDirectory()
            },
            onItemsEmptyChanged = { isEmpty ->
                listFavoritePaths.visibility = if (isEmpty) View.GONE else View.VISIBLE
                tvFavoritePathsEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            }
        )

        listFavoritePaths.layoutManager = LinearLayoutManager(this)
        listFavoritePaths.addItemDecoration(InsetDividerItemDecoration(this))
        listFavoritePaths.adapter = adapter
        listFavoritePaths.visibility = if (favoriteItems.isEmpty()) View.GONE else View.VISIBLE
        tvFavoritePathsEmpty.visibility = if (favoriteItems.isEmpty()) View.VISIBLE else View.GONE
        btnClearFavoritePaths.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_favorite_paths_title)
                .setMessage(R.string.clear_favorite_paths_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    FavoritePathStore.clear(this)
                    adapter.clearItems()
                    notifyListChanged()
                }
                .setNegativeButton(R.string.no, null)
                .showRounded()
        }

        dismissSidePanels()
        scheduleShowReadingHistoryScrim()
        favoritePathAdapter = adapter
        favoritePathsPopupWindow = PopupWindow(
            content,
            panelWidth,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            animationStyle = R.style.Animation_ComicLab_ReadingHistoryPanel
            elevation = dpToPx(READING_HISTORY_PANEL_ELEVATION_DP).toFloat()
            setOnDismissListener {
                if (favoritePathAdapter === adapter) {
                    favoritePathAdapter = null
                    favoritePathsPopupWindow = null
                    scheduleHideReadingHistoryScrim()
                    notifyListChanged()
                }
            }
            showAtLocation(rootView, Gravity.END or Gravity.TOP, 0, 0)
        }
    }

    private fun showFavoriteComicsPanel() {
        val rootView = findViewById<View>(R.id.main)
        val screenWidth = rootView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 3 / 4)
            .coerceAtLeast(dpToPx(MIN_READING_HISTORY_PANEL_WIDTH_DP))
        val content = layoutInflater.inflate(R.layout.panel_favorite_comics, null)
        val listFavoriteComics = content.findViewById<RecyclerView>(R.id.listFavoriteComics)
        val tvFavoriteComicsEmpty = content.findViewById<TextView>(R.id.tvFavoriteComicsEmpty)
        val btnClearFavoriteComics = content.findViewById<ImageButton>(R.id.btnClearFavoriteComics)
        val favoriteItems = FavoriteComicStore.items(this).toMutableList()
        val adapter = FavoriteComicAdapter(
            context = this,
            items = favoriteItems,
            onItemClick = { item ->
                dismissFavoriteComicsPanel()
                openMangaPreview(item.file)
            },
            onItemsEmptyChanged = { isEmpty ->
                listFavoriteComics.visibility = if (isEmpty) View.GONE else View.VISIBLE
                tvFavoriteComicsEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            }
        )

        listFavoriteComics.layoutManager = LinearLayoutManager(this)
        listFavoriteComics.addItemDecoration(InsetDividerItemDecoration(this))
        listFavoriteComics.adapter = adapter
        listFavoriteComics.visibility = if (favoriteItems.isEmpty()) View.GONE else View.VISIBLE
        tvFavoriteComicsEmpty.visibility = if (favoriteItems.isEmpty()) View.VISIBLE else View.GONE
        btnClearFavoriteComics.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_favorite_comics_title)
                .setMessage(R.string.clear_favorite_comics_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    FavoriteComicStore.clear(this)
                    adapter.clearItems()
                    notifyListChanged()
                }
                .setNegativeButton(R.string.no, null)
                .showRounded()
        }

        dismissSidePanels()
        scheduleShowReadingHistoryScrim()
        favoriteComicAdapter = adapter
        favoriteComicsPopupWindow = PopupWindow(
            content,
            panelWidth,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            animationStyle = R.style.Animation_ComicLab_ReadingHistoryPanel
            elevation = dpToPx(READING_HISTORY_PANEL_ELEVATION_DP).toFloat()
            setOnDismissListener {
                adapter.close()
                if (favoriteComicAdapter === adapter) {
                    favoriteComicAdapter = null
                    favoriteComicsPopupWindow = null
                    scheduleHideReadingHistoryScrim()
                    notifyListChanged()
                }
            }
            showAtLocation(rootView, Gravity.END or Gravity.TOP, 0, 0)
        }
    }

    private fun dismissSidePanels() {
        dismissReadingHistoryPanel()
        dismissFavoriteComicsPanel()
        dismissFavoritePathsPanel()
    }

    private fun dismissReadingHistoryPanel() {
        readingHistoryPopupWindow?.dismiss()
        readingHistoryPopupWindow = null
        readingHistoryAdapter?.close()
        readingHistoryAdapter = null
        scheduleHideReadingHistoryScrim()
    }

    private fun dismissFavoritePathsPanel() {
        favoritePathsPopupWindow?.dismiss()
        favoritePathsPopupWindow = null
        favoritePathAdapter = null
        scheduleHideReadingHistoryScrim()
    }

    private fun dismissFavoriteComicsPanel() {
        favoriteComicsPopupWindow?.dismiss()
        favoriteComicsPopupWindow = null
        favoriteComicAdapter?.close()
        favoriteComicAdapter = null
        scheduleHideReadingHistoryScrim()
    }

    private fun scheduleShowReadingHistoryScrim() {
        mainHandler.removeCallbacks(hideReadingHistoryScrimRunnable)
        mainHandler.removeCallbacks(showReadingHistoryScrimRunnable)
        mainHandler.postDelayed(
            showReadingHistoryScrimRunnable,
            READING_HISTORY_PANEL_ENTER_ANIMATION_MS
        )
    }

    private fun scheduleHideReadingHistoryScrim() {
        mainHandler.removeCallbacks(showReadingHistoryScrimRunnable)
        mainHandler.removeCallbacks(hideReadingHistoryScrimRunnable)
        mainHandler.postDelayed(
            hideReadingHistoryScrimRunnable,
            READING_HISTORY_PANEL_EXIT_ANIMATION_MS
        )
    }

    private fun showReadingHistoryScrimNow() {
        readingHistoryScrim.visibility = View.VISIBLE
    }

    private fun hideReadingHistoryScrimNow() {
        readingHistoryScrim.visibility = View.GONE
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

    private fun openSearch() {
        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        val intent = Intent(this, SearchActivity::class.java).apply {
            putExtra(SearchActivity.EXTRA_SEARCH_ROOT_PATH, currentPath)
        }
        startActivity(intent)
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

        val current = File(currentPath)
        val pathToCenter = current.absolutePath
        setCurrentPath(current.parentFile?.absolutePath ?: STORAGE_ROOT_PATH)
        loadCurrentDirectory(pathToCenter)
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
        if (!Environment.isExternalStorageManager() || isAtStorageRoot()) {
            finish()
            return
        }

        goParent()
    }

    private fun isAtStorageRoot(): Boolean {
        return File(currentPath).absolutePath == File(STORAGE_ROOT_PATH).absolutePath
    }

    private fun loadCurrentDirectory(
        pathToCenter: String? = null,
        scrollStateToRestore: FileListScrollState? = null
    ) {
        if (!Environment.isExternalStorageManager()) {
            etPath.setText(R.string.storage_permission_required)
            fileItems.clear()
            notifyListChanged()
            return
        }

        val requestedPath = currentPath
        val generation = directoryLoadGeneration.incrementAndGet()
        directoryLoadExecutor.execute {
            val directory = File(requestedPath).takeIf { it.isDirectory } ?: File(STORAGE_ROOT_PATH)
            val resolvedPath = directory.absolutePath

            val files = try {
                directory.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.let { sortFiles(it) }
                    ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }

            val items = mutableListOf<FileItem>()
            if (directory.absolutePath != File(STORAGE_ROOT_PATH).absolutePath) {
                items.add(FileItem(isParent = true))
            }
            files.forEach { file ->
                items.add(
                    FileItem(
                        file = file,
                        childCount = if (file.isDirectory) directoryChildCount(file) else null
                    )
                )
            }

            runOnUiThread {
                if (generation != directoryLoadGeneration.get()) {
                    return@runOnUiThread
                }

                currentPath = resolvedPath
                saveCurrentPath()
                fileItems.clear()
                fileItems.addAll(items)
                etPath.setText(resolvedPath)
                notifyListChanged()
                if (pathToCenter == null) {
                    if (scrollStateToRestore != null) {
                        restoreFileListScrollState(scrollStateToRestore, resolvedPath)
                    } else {
                        scrollFileListToTop()
                    }
                } else {
                    centerFileItemIfPresent(pathToCenter)
                }
            }
        }
    }

    private fun directoryChildCount(directory: File): Int {
        return try {
            directory.listFiles()?.size ?: 0
        } catch (_: SecurityException) {
            0
        }
    }

    private fun centerFileItemIfPresent(path: String) {
        val targetIndex = fileItems.indexOfFirst { item ->
            item.file?.absolutePath == path
        }
        if (targetIndex < 0) {
            return
        }

        listView.post {
            centerListPosition(targetIndex)
        }
    }

    private fun scrollFileListToTop() {
        listView.post {
            listView.setSelectionFromTop(0, 0)
        }
    }

    private fun captureFileListScrollState(): FileListScrollState? {
        val firstVisiblePosition = listView.firstVisiblePosition
        if (firstVisiblePosition !in fileItems.indices) {
            return null
        }

        val firstVisibleItem = fileItems[firstVisiblePosition]
        return FileListScrollState(
            directoryPath = File(currentPath).absolutePath,
            firstVisiblePosition = firstVisiblePosition,
            firstVisibleTop = listView.getChildAt(0)?.top ?: 0,
            anchorPath = firstVisibleItem.file?.absolutePath,
            anchorIsParent = firstVisibleItem.isParent
        )
    }

    private fun restoreFileListScrollState(
        scrollState: FileListScrollState,
        resolvedPath: String
    ) {
        if (File(scrollState.directoryPath).absolutePath != File(resolvedPath).absolutePath) {
            scrollFileListToTop()
            return
        }

        listView.post {
            if (fileItems.isEmpty()) {
                return@post
            }

            val anchorPosition = when {
                scrollState.anchorIsParent -> fileItems.indexOfFirst { it.isParent }
                !scrollState.anchorPath.isNullOrBlank() ->
                    fileItems.indexOfFirst { it.file?.absolutePath == scrollState.anchorPath }
                else -> -1
            }
            val targetPosition = anchorPosition
                .takeIf { it in fileItems.indices }
                ?: scrollState.firstVisiblePosition.coerceIn(0, fileItems.lastIndex)

            listView.setSelectionFromTop(targetPosition, scrollState.firstVisibleTop)
        }
    }

    private fun showSortDialog() {
        val sortModes = FileSortMode.values()
        val currentMode = currentSortMode()
        val items = sortModes.map { sortMode ->
            RoundedPopupMenuItem(
                label = getString(sortMode.labelResId),
                isSelected = sortMode == currentMode
            ) {
                prefs.edit()
                    .putString(KEY_SORT_MODE, sortMode.name)
                    .apply()
                loadCurrentDirectory()
            }
        }

        showRoundedPopupMenu(btnSort, items, widthDp = 208)
    }

    private fun showClassifyMenu() {
        val items = listOf(
            RoundedPopupMenuItem(getString(R.string.classify_comics)) {
                confirmClassifyCurrentDirectory()
            },
            RoundedPopupMenuItem(MENU_TITLE_FIND_SIMILAR_DIRECTORY_NAMES) {
                startFindSimilarDirectoryNames()
            },
            RoundedPopupMenuItem(MENU_TITLE_MERGE_COMICS_NON_RECURSIVE) {
                startMergeComicsNonRecursive()
            },
            RoundedPopupMenuItem(MENU_TITLE_MERGE_FILES_NON_RECURSIVE) {
                startMergeFilesNonRecursive()
            }
        )

        showRoundedPopupMenu(btnClassify, items, widthDp = 252)
    }

    private fun showRoundedPopupMenu(
        anchor: View,
        items: List<RoundedPopupMenuItem>,
        widthDp: Int
    ) {
        if (items.isEmpty()) {
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_file_picker_popup_panel)
            setPadding(
                dpToPx(ROUNDED_MENU_PADDING_HORIZONTAL_DP),
                dpToPx(ROUNDED_MENU_PADDING_VERTICAL_DP),
                dpToPx(ROUNDED_MENU_PADDING_HORIZONTAL_DP),
                dpToPx(ROUNDED_MENU_PADDING_VERTICAL_DP)
            )
        }

        var popupWindow: PopupWindow? = null
        items.forEach { item ->
            val row = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(ROUNDED_MENU_ITEM_HEIGHT_DP)
                )
                background = getDrawable(R.drawable.bg_file_picker_popup_item)
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
                setPadding(
                    dpToPx(ROUNDED_MENU_ITEM_PADDING_HORIZONTAL_DP),
                    0,
                    dpToPx(ROUNDED_MENU_ITEM_PADDING_HORIZONTAL_DP),
                    0
                )
                text = item.label
                textSize = 14f
                setTextColor(
                    getColor(
                        if (item.isSelected) {
                            R.color.comiclab_accent
                        } else {
                            R.color.comiclab_text_primary
                        }
                    )
                )
                if (item.isSelected) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                setOnClickListener {
                    popupWindow?.dismiss()
                    item.onClick()
                }
            }
            content.addView(row)
        }

        val popupWidthPx = dpToPx(widthDp)
        popupWindow = PopupWindow(
            content,
            popupWidthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = dpToPx(ROUNDED_MENU_ELEVATION_DP).toFloat()
            showAsDropDown(
                anchor,
                anchor.width - popupWidthPx,
                dpToPx(ROUNDED_MENU_VERTICAL_OFFSET_DP)
            )
        }
    }

    private fun confirmClassifyCurrentDirectory() {
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

        val rootDirectory = File(currentPath)
        if (!rootDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        AlertDialog.Builder(this)
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
        val content = layoutInflater.inflate(R.layout.dialog_classify_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvClassifyProgressStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressClassifyComics)
        val tvCount = content.findViewById<TextView>(R.id.tvClassifyProgressCount)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.classify_comics)
            .setView(content)
            .setCancelable(false)
            .createRounded()

        isClassifyingComics = true
        progressDialog.show()

        val errorTags = AppSettings.getStartMarkerErrorTags(this)
        runCatching {
            classifyExecutor.execute {
                val result = runCatching {
                    ComicClassifier.classify(rootDirectory, errorTags) { progress ->
                        runOnUiThread {
                            if (generation == classifyGeneration.get() && !isDestroyed) {
                                updateClassifyProgress(progress, progressBar, tvStatus, tvCount)
                            }
                        }
                    }
                }

                runOnUiThread {
                    if (generation != classifyGeneration.get() || isDestroyed) {
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
                    loadCurrentDirectory()
                }
            }
        }.onFailure {
            isClassifyingComics = false
            progressDialog.dismiss()
            showMessage(getString(R.string.classify_comics_failed))
        }
    }

    private fun startFindSimilarDirectoryNames() {
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

        val rootDirectory = File(currentPath)
        if (!rootDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        val generation = classifyGeneration.incrementAndGet()
        val rootPath = rootDirectory.absolutePath
        val scrollState = captureFileListScrollState()
        val content = layoutInflater.inflate(R.layout.dialog_classify_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvClassifyProgressStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressClassifyComics)
        val tvCount = content.findViewById<TextView>(R.id.tvClassifyProgressCount)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(MENU_TITLE_FIND_SIMILAR_DIRECTORY_NAMES)
            .setView(content)
            .setCancelable(false)
            .createRounded()

        isBuildingDirectorySimilarityReport = true
        progressBar.isIndeterminate = true
        tvStatus.text = MESSAGE_SCANNING_DIRECTORIES
        tvCount.text = ""
        progressDialog.show()

        runCatching {
            classifyExecutor.execute {
                val result = runCatching {
                    buildDirectorySimilarityReport(rootDirectory) { progress ->
                        runOnUiThread {
                            if (generation == classifyGeneration.get() && !isDestroyed) {
                                updateDirectorySimilarityProgress(progress, progressBar, tvStatus, tvCount)
                            }
                        }
                    }
                }

                runOnUiThread {
                    if (generation != classifyGeneration.get() || isDestroyed) {
                        return@runOnUiThread
                    }

                    isBuildingDirectorySimilarityReport = false
                    progressDialog.dismiss()
                    result
                        .onSuccess { report ->
                            showMessage(
                                "已生成报告：${report.outputFile.name}，相似组合 ${report.pairCount} 组"
                            )
                            if (File(currentPath).absolutePath == rootPath) {
                                loadCurrentDirectory(scrollStateToRestore = scrollState)
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

    private fun startMergeComicsNonRecursive() {
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

        val rootDirectory = File(currentPath)
        if (!rootDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        val generation = classifyGeneration.incrementAndGet()
        val rootPath = rootDirectory.absolutePath
        val scrollState = captureFileListScrollState()
        val content = layoutInflater.inflate(R.layout.dialog_classify_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvClassifyProgressStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressClassifyComics)
        val tvCount = content.findViewById<TextView>(R.id.tvClassifyProgressCount)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(MENU_TITLE_MERGE_COMICS_NON_RECURSIVE)
            .setView(content)
            .setCancelable(false)
            .createRounded()

        isMergingComics = true
        progressBar.isIndeterminate = true
        tvStatus.text = MESSAGE_SCANNING_COMIC_ARCHIVES
        tvCount.text = ""
        progressDialog.show()

        runCatching {
            classifyExecutor.execute {
                val result = runCatching {
                    mergeComicsNonRecursive(rootDirectory) { progress ->
                        runOnUiThread {
                            if (generation == classifyGeneration.get() && !isDestroyed) {
                                updateComicMergeProgress(progress, progressBar, tvStatus, tvCount)
                            }
                        }
                    }
                }

                runOnUiThread {
                    if (generation != classifyGeneration.get() || isDestroyed) {
                        return@runOnUiThread
                    }

                    isMergingComics = false
                    progressDialog.dismiss()
                    result
                        .onSuccess { mergeResult ->
                            handleComicMergeResult(mergeResult)
                            if (File(currentPath).absolutePath == rootPath) {
                                loadCurrentDirectory(scrollStateToRestore = scrollState)
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

    private fun startMergeFilesNonRecursive() {
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

        val rootDirectory = File(currentPath)
        if (!rootDirectory.isDirectory) {
            showMessage(getString(R.string.message_invalid_directory))
            return
        }

        val generation = classifyGeneration.incrementAndGet()
        val rootPath = rootDirectory.absolutePath
        val scrollState = captureFileListScrollState()
        val content = layoutInflater.inflate(R.layout.dialog_classify_progress, null)
        val tvStatus = content.findViewById<TextView>(R.id.tvClassifyProgressStatus)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressClassifyComics)
        val tvCount = content.findViewById<TextView>(R.id.tvClassifyProgressCount)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(MENU_TITLE_MERGE_FILES_NON_RECURSIVE)
            .setView(content)
            .setCancelable(false)
            .createRounded()

        isMergingFiles = true
        progressBar.isIndeterminate = true
        tvStatus.text = MESSAGE_SCANNING_IMAGE_DIRECTORIES
        tvCount.text = ""
        progressDialog.show()

        runCatching {
            classifyExecutor.execute {
                val result = runCatching {
                    mergeFilesNonRecursive(rootDirectory) { progress ->
                        runOnUiThread {
                            if (generation == classifyGeneration.get() && !isDestroyed) {
                                updateComicMergeProgress(progress, progressBar, tvStatus, tvCount)
                            }
                        }
                    }
                }

                runOnUiThread {
                    if (generation != classifyGeneration.get() || isDestroyed) {
                        return@runOnUiThread
                    }

                    isMergingFiles = false
                    progressDialog.dismiss()
                    result
                        .onSuccess { mergeResult ->
                            handleFileMergeResult(mergeResult)
                            if (File(currentPath).absolutePath == rootPath) {
                                loadCurrentDirectory(scrollStateToRestore = scrollState)
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

    private fun updateComicMergeProgress(
        progress: ComicMergeProgress,
        progressBar: ProgressBar,
        tvStatus: TextView,
        tvCount: TextView
    ) {
        tvStatus.text = progress.message
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

    private fun handleComicMergeResult(result: ComicMergeResult) {
        val outputDirectory = result.outputDirectory
        if (result.archiveCount <= 0) {
            showMessage(MESSAGE_MERGE_COMICS_NO_ARCHIVES)
            return
        }
        if (result.totalImageCount <= 0 || outputDirectory == null) {
            showMessage(MESSAGE_MERGE_COMICS_NO_IMAGES)
            return
        }

        showMessage(
            "整合完成：已复制 ${result.copiedCount} / ${result.totalImageCount}，" +
                "失败 ${result.failedCount}，输出：${outputDirectory.name}"
        )
    }

    private fun handleFileMergeResult(result: FileMergeResult) {
        val outputDirectory = result.outputDirectory
        if (result.folderCount <= 0) {
            showMessage(MESSAGE_MERGE_FILES_NO_FOLDERS)
            return
        }
        if (result.totalImageCount <= 0 || outputDirectory == null) {
            showMessage(MESSAGE_MERGE_FILES_NO_IMAGES)
            return
        }

        showMessage(
            "文件整合完成：已复制 ${result.copiedCount} / ${result.totalImageCount}，" +
                "失败 ${result.failedCount}，输出：${outputDirectory.name}"
        )
    }

    private fun updateDirectorySimilarityProgress(
        progress: DirectorySimilarityProgress,
        progressBar: ProgressBar,
        tvStatus: TextView,
        tvCount: TextView
    ) {
        tvStatus.text = progress.message
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

    private fun buildDirectorySimilarityReport(
        rootDirectory: File,
        onProgress: (DirectorySimilarityProgress) -> Unit
    ): DirectorySimilarityReport {
        onProgress(DirectorySimilarityProgress(MESSAGE_SCANNING_DIRECTORIES))
        val directories = scanDirectoriesForSimilarity(rootDirectory)
        onProgress(
            DirectorySimilarityProgress(
                message = MESSAGE_COMPARING_DIRECTORY_NAMES,
                completed = 0,
                total = directories.size
            )
        )

        val pairs = findSimilarDirectoryNamePairs(directories) { completed, total ->
            onProgress(
                DirectorySimilarityProgress(
                    message = MESSAGE_COMPARING_DIRECTORY_NAMES,
                    completed = completed,
                    total = total
                )
            )
        }
        val outputFile = createDirectorySimilarityReportFile(rootDirectory)
        outputFile.writeText(
            buildDirectorySimilarityReportText(rootDirectory, directories, pairs)
        )
        return DirectorySimilarityReport(
            outputFile = outputFile,
            directoryCount = directories.size,
            pairCount = pairs.size
        )
    }

    private fun mergeComicsNonRecursive(
        rootDirectory: File,
        onProgress: (ComicMergeProgress) -> Unit
    ): ComicMergeResult {
        onProgress(ComicMergeProgress(MESSAGE_SCANNING_COMIC_ARCHIVES))
        val archives = comicArchivesInCurrentDirectory(rootDirectory)
        if (archives.isEmpty()) {
            return ComicMergeResult(
                outputDirectory = null,
                archiveCount = 0,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            )
        }

        val sources = mutableListOf<ComicMergeSource>()
        archives.forEachIndexed { index, archive ->
            onProgress(
                ComicMergeProgress(
                    message = MESSAGE_READING_COMIC_ARCHIVES,
                    completed = index,
                    total = archives.size
                )
            )
            val entries = ComicArchive.imageEntries(archive)
            if (entries.isNotEmpty()) {
                sources.add(ComicMergeSource(archive, entries))
            }
        }
        onProgress(
            ComicMergeProgress(
                message = MESSAGE_READING_COMIC_ARCHIVES,
                completed = archives.size,
                total = archives.size
            )
        )

        val totalImageCount = sources.sumOf { it.entries.size }
        if (totalImageCount <= 0) {
            return ComicMergeResult(
                outputDirectory = null,
                archiveCount = archives.size,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            )
        }

        val outputDirectory = createComicMergeOutputDirectory(rootDirectory)
        if (!outputDirectory.mkdirs()) {
            error("Failed to create comic merge output directory: ${outputDirectory.absolutePath}")
        }

        val numberWidth = maxOf(MERGED_COMIC_MIN_FILE_NUMBER_WIDTH, totalImageCount.toString().length)
        var completedCount = 0
        var copiedCount = 0
        var failedCount = 0
        var nextOutputIndex = 1

        sources.forEach { source ->
            source.entries.forEach { entryName ->
                val outputName = buildMergedComicImageFileName(nextOutputIndex, numberWidth, entryName)
                val targetFile = File(outputDirectory, outputName)
                val copied = ComicArchive.extractImageEntryToFile(source.archive, entryName, targetFile)
                completedCount++

                if (copied) {
                    copiedCount++
                    nextOutputIndex++
                } else {
                    failedCount++
                    targetFile.delete()
                }

                onProgress(
                    ComicMergeProgress(
                        message = "正在整合：${source.archive.name}",
                        completed = completedCount,
                        total = totalImageCount
                    )
                )
            }
        }

        return ComicMergeResult(
            outputDirectory = outputDirectory,
            archiveCount = archives.size,
            totalImageCount = totalImageCount,
            copiedCount = copiedCount,
            failedCount = failedCount
        )
    }

    private fun mergeFilesNonRecursive(
        rootDirectory: File,
        onProgress: (ComicMergeProgress) -> Unit
    ): FileMergeResult {
        onProgress(ComicMergeProgress(MESSAGE_SCANNING_IMAGE_DIRECTORIES))
        val directories = imageSourceDirectoriesInCurrentDirectory(rootDirectory)
        if (directories.isEmpty()) {
            return FileMergeResult(
                outputDirectory = null,
                folderCount = 0,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            )
        }

        val sources = mutableListOf<FileMergeSource>()
        directories.forEachIndexed { index, directory ->
            onProgress(
                ComicMergeProgress(
                    message = MESSAGE_READING_IMAGE_DIRECTORIES,
                    completed = index,
                    total = directories.size
                )
            )
            val images = imageFilesInDirectory(directory)
            if (images.isNotEmpty()) {
                sources.add(FileMergeSource(directory, images))
            }
        }
        onProgress(
            ComicMergeProgress(
                message = MESSAGE_READING_IMAGE_DIRECTORIES,
                completed = directories.size,
                total = directories.size
            )
        )

        val totalImageCount = sources.sumOf { it.images.size }
        if (totalImageCount <= 0) {
            return FileMergeResult(
                outputDirectory = null,
                folderCount = directories.size,
                totalImageCount = 0,
                copiedCount = 0,
                failedCount = 0
            )
        }

        val outputDirectory = createFileMergeOutputDirectory(rootDirectory)
        if (!outputDirectory.mkdirs()) {
            error("Failed to create file merge output directory: ${outputDirectory.absolutePath}")
        }

        val numberWidth = maxOf(MERGED_COMIC_MIN_FILE_NUMBER_WIDTH, totalImageCount.toString().length)
        var completedCount = 0
        var copiedCount = 0
        var failedCount = 0
        var nextOutputIndex = 1

        sources.forEach { source ->
            source.images.forEach { imageFile ->
                val outputName = buildMergedComicImageFileName(nextOutputIndex, numberWidth, imageFile.name)
                val targetFile = File(outputDirectory, outputName)
                val copied = copyImageFileToMergedTarget(imageFile, targetFile)
                completedCount++

                if (copied) {
                    copiedCount++
                    nextOutputIndex++
                } else {
                    failedCount++
                    targetFile.delete()
                }

                onProgress(
                    ComicMergeProgress(
                        message = "正在整合：${source.directory.name}",
                        completed = completedCount,
                        total = totalImageCount
                    )
                )
            }
        }

        return FileMergeResult(
            outputDirectory = outputDirectory,
            folderCount = directories.size,
            totalImageCount = totalImageCount,
            copiedCount = copiedCount,
            failedCount = failedCount
        )
    }

    private fun comicArchivesInCurrentDirectory(rootDirectory: File): List<File> {
        return runCatching {
            rootDirectory.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        ComicArchive.isSupportedArchive(file) &&
                        !ComicArchive.isPdf(file)
                }
                ?.sortedWith { left, right -> compareNaturalNames(left.name, right.name) }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun imageSourceDirectoriesInCurrentDirectory(rootDirectory: File): List<File> {
        return runCatching {
            rootDirectory.listFiles()
                ?.filter { file ->
                    file.isDirectory &&
                        !file.name.startsWith(".") &&
                        !isGeneratedMergeDirectory(file.name)
                }
                ?.sortedWith { left, right -> compareNaturalNames(left.name, right.name) }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun imageFilesInDirectory(directory: File): List<File> {
        return runCatching {
            directory.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        file.extension.lowercase(Locale.ROOT) in MERGED_COMIC_IMAGE_EXTENSIONS
                }
                ?.sortedWith { left, right -> compareNaturalNames(left.name, right.name) }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun isGeneratedMergeDirectory(name: String): Boolean {
        return name.startsWith(MERGED_COMIC_DIRECTORY_PREFIX) ||
            name.startsWith(MERGED_FILE_DIRECTORY_PREFIX)
    }

    private fun createComicMergeOutputDirectory(rootDirectory: File): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        var outputDirectory = File(rootDirectory, "$MERGED_COMIC_DIRECTORY_PREFIX$timestamp")
        var index = 1
        while (outputDirectory.exists()) {
            outputDirectory = File(
                rootDirectory,
                "$MERGED_COMIC_DIRECTORY_PREFIX${timestamp}_${String.format(Locale.ROOT, "%03d", index)}"
            )
            index++
        }
        return outputDirectory
    }

    private fun createFileMergeOutputDirectory(rootDirectory: File): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        var outputDirectory = File(rootDirectory, "$MERGED_FILE_DIRECTORY_PREFIX$timestamp")
        var index = 1
        while (outputDirectory.exists()) {
            outputDirectory = File(
                rootDirectory,
                "$MERGED_FILE_DIRECTORY_PREFIX${timestamp}_${String.format(Locale.ROOT, "%03d", index)}"
            )
            index++
        }
        return outputDirectory
    }

    private fun copyImageFileToMergedTarget(sourceFile: File, targetFile: File): Boolean {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            return false
        }

        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        tempFile.delete()
        val copied = runCatching {
            sourceFile.copyTo(tempFile, overwrite = true, bufferSize = MERGED_FILE_COPY_BUFFER_SIZE)
            tempFile.isFile && tempFile.length() == sourceFile.length()
        }.getOrDefault(false)

        if (!copied) {
            tempFile.delete()
            return false
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.delete()
            return false
        }

        targetFile.setLastModified(sourceFile.lastModified())
        return true
    }

    private fun buildMergedComicImageFileName(index: Int, numberWidth: Int, entryName: String): String {
        val extension = entryName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it in MERGED_COMIC_IMAGE_EXTENSIONS }
            ?: MERGED_COMIC_DEFAULT_IMAGE_EXTENSION
        return "${index.toString().padStart(numberWidth, '0')}.$extension"
    }

    private fun compareNaturalNames(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0

        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]

            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftEnd = findNumberRunEnd(left, leftIndex)
                val rightEnd = findNumberRunEnd(right, rightIndex)
                val numberComparison = compareNumberRuns(left, leftIndex, leftEnd, right, rightIndex, rightEnd)
                if (numberComparison != 0) {
                    return numberComparison
                }
                leftIndex = leftEnd
                rightIndex = rightEnd
                continue
            }

            val charComparison = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
            if (charComparison != 0) {
                return charComparison
            }

            leftIndex++
            rightIndex++
        }

        if (leftIndex != left.length || rightIndex != right.length) {
            return (left.length - leftIndex).compareTo(right.length - rightIndex)
        }

        return left.compareTo(right)
    }

    private fun findNumberRunEnd(value: String, startIndex: Int): Int {
        var index = startIndex
        while (index < value.length && value[index].isDigit()) {
            index++
        }
        return index
    }

    private fun compareNumberRuns(
        left: String,
        leftStart: Int,
        leftEnd: Int,
        right: String,
        rightStart: Int,
        rightEnd: Int
    ): Int {
        val leftSignificantStart = findSignificantNumberStart(left, leftStart, leftEnd)
        val rightSignificantStart = findSignificantNumberStart(right, rightStart, rightEnd)
        val leftSignificantLength = leftEnd - leftSignificantStart
        val rightSignificantLength = rightEnd - rightSignificantStart

        if (leftSignificantLength != rightSignificantLength) {
            return leftSignificantLength.compareTo(rightSignificantLength)
        }

        for (offset in 0 until leftSignificantLength) {
            val digitComparison = left[leftSignificantStart + offset]
                .compareTo(right[rightSignificantStart + offset])
            if (digitComparison != 0) {
                return digitComparison
            }
        }

        return 0
    }

    private fun findSignificantNumberStart(value: String, startIndex: Int, endIndex: Int): Int {
        var index = startIndex
        while (index < endIndex - 1 && value[index] == '0') {
            index++
        }
        return index
    }

    private fun scanDirectoriesForSimilarity(rootDirectory: File): List<SimilarDirectoryInfo> {
        val result = mutableListOf<SimilarDirectoryInfo>()
        val pending = ArrayDeque<File>()
        val visited = mutableSetOf<String>()

        runCatching {
            rootDirectory.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedByDescending { it.name.lowercase(Locale.ROOT) }
                ?.forEach { pending.add(it) }
        }

        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()
            if (!directory.isDirectory || directory.name.startsWith(".")) {
                continue
            }

            val stablePath = runCatching { directory.canonicalPath }
                .getOrDefault(directory.absolutePath)
            if (!visited.add(stablePath)) {
                continue
            }

            result.add(
                SimilarDirectoryInfo(
                    name = directory.name,
                    path = directory.absolutePath
                )
            )

            val children = runCatching {
                directory.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedByDescending { it.name.lowercase(Locale.ROOT) }
                    .orEmpty()
            }.getOrDefault(emptyList())
            children.forEach { pending.add(it) }
        }

        return result.sortedBy { it.path.lowercase(Locale.ROOT) }
    }

    private fun findSimilarDirectoryNamePairs(
        directories: List<SimilarDirectoryInfo>,
        onProgress: (completed: Int, total: Int) -> Unit
    ): List<SimilarDirectoryNamePair> {
        val candidates = directories
            .mapNotNull { directory ->
                normalizeDirectoryNameForSimilarity(directory.name)
                    .takeIf { it.isNotBlank() }
                    ?.let { normalizedName ->
                        SimilarDirectoryNameCandidate(directory, normalizedName)
                    }
            }

        if (candidates.size < 2) {
            onProgress(candidates.size, candidates.size)
            return emptyList()
        }

        val pairs = mutableListOf<SimilarDirectoryNamePair>()
        for (leftIndex in 0 until candidates.lastIndex) {
            val left = candidates[leftIndex]
            for (rightIndex in (leftIndex + 1)..candidates.lastIndex) {
                val right = candidates[rightIndex]
                val maxLength = maxOf(left.normalizedName.length, right.normalizedName.length)
                val minLength = minOf(left.normalizedName.length, right.normalizedName.length)
                if (maxLength <= 0 ||
                    minLength.toDouble() / maxLength.toDouble() < DIRECTORY_NAME_SIMILARITY_THRESHOLD
                ) {
                    continue
                }

                val similarity = directoryNameSimilarity(left.normalizedName, right.normalizedName)
                if (similarity >= DIRECTORY_NAME_SIMILARITY_THRESHOLD) {
                    pairs.add(
                        SimilarDirectoryNamePair(
                            left = left.directory,
                            right = right.directory,
                            similarity = similarity
                        )
                    )
                }
            }

            if (leftIndex % DIRECTORY_SIMILARITY_PROGRESS_ROW_INTERVAL == 0 ||
                leftIndex == candidates.lastIndex - 1
            ) {
                onProgress(leftIndex + 1, candidates.size)
            }
        }
        onProgress(candidates.size, candidates.size)

        return pairs.sortedWith(
            compareByDescending<SimilarDirectoryNamePair> { it.similarity }
                .thenBy { it.left.name.lowercase(Locale.ROOT) }
                .thenBy { it.right.name.lowercase(Locale.ROOT) }
                .thenBy { it.left.path.lowercase(Locale.ROOT) }
                .thenBy { it.right.path.lowercase(Locale.ROOT) }
        )
    }

    private fun buildDirectorySimilarityReportText(
        rootDirectory: File,
        directories: List<SimilarDirectoryInfo>,
        pairs: List<SimilarDirectoryNamePair>
    ): String {
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("ComicLab 命名接近的文件夹路径报告")
            appendLine("生成时间：$generatedAt")
            appendLine("扫描根目录：${rootDirectory.absolutePath}")
            appendLine(
                "比较规则：仅使用文件夹名计算归一化 Levenshtein 相似度，阈值 >= " +
                    similarityPercent(DIRECTORY_NAME_SIMILARITY_THRESHOLD)
            )
            appendLine("扫描文件夹数：${directories.size}")
            appendLine("命名接近组合数：${pairs.size}")
            appendLine()

            if (pairs.isEmpty()) {
                appendLine("没有找到达到阈值的命名接近文件夹。")
                return@buildString
            }

            pairs.forEachIndexed { index, pair ->
                appendLine("[${String.format(Locale.ROOT, "%03d", index + 1)}] 相似度：${similarityPercent(pair.similarity)}")
                appendLine("名称 A：${pair.left.name}")
                appendLine("路径 A：${pair.left.path}")
                appendLine("名称 B：${pair.right.name}")
                appendLine("路径 B：${pair.right.path}")
                appendLine()
            }
        }
    }

    private fun createDirectorySimilarityReportFile(rootDirectory: File): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        var outputFile = File(
            rootDirectory,
            "$DIRECTORY_SIMILARITY_REPORT_PREFIX$timestamp$DIRECTORY_SIMILARITY_REPORT_EXTENSION"
        )
        var index = 1
        while (outputFile.exists()) {
            outputFile = File(
                rootDirectory,
                "$DIRECTORY_SIMILARITY_REPORT_PREFIX${timestamp}_" +
                    "${String.format(Locale.ROOT, "%03d", index)}$DIRECTORY_SIMILARITY_REPORT_EXTENSION"
            )
            index++
        }
        return outputFile
    }

    private fun normalizeDirectoryNameForSimilarity(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .filterNot { char ->
                char.isWhitespace() || char in DIRECTORY_NAME_SIMILARITY_IGNORED_CHARS
            }
    }

    private fun directoryNameSimilarity(left: String, right: String): Double {
        if (left == right) {
            return 1.0
        }
        val maxLength = maxOf(left.length, right.length)
        if (maxLength <= 0) {
            return 0.0
        }
        val distance = levenshteinDistance(left, right)
        return (1.0 - (distance.toDouble() / maxLength.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) {
            return 0
        }
        if (left.isEmpty()) {
            return right.length
        }
        if (right.isEmpty()) {
            return left.length
        }

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (leftIndex in 1..left.length) {
            current[0] = leftIndex
            for (rightIndex in 1..right.length) {
                val cost = if (left[leftIndex - 1] == right[rightIndex - 1]) 0 else 1
                current[rightIndex] = minOf(
                    current[rightIndex - 1] + 1,
                    previous[rightIndex] + 1,
                    previous[rightIndex - 1] + cost
                )
            }

            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }

    private fun similarityPercent(value: Double): String {
        return String.format(Locale.getDefault(), "%.2f%%", value * 100.0)
    }

    private fun sortFiles(files: List<File>): List<File> {
        val mode = currentSortMode()
        return files.sortedWith(Comparator { left, right ->
            val typeComparison = left.fileTypeOrder().compareTo(right.fileTypeOrder())
            if (typeComparison != 0) {
                return@Comparator typeComparison
            }

            when (mode) {
                FileSortMode.NAME_ASC -> compareFileNames(left, right)
                FileSortMode.NAME_DESC -> compareFileNames(right, left)
                FileSortMode.MODIFIED_DESC -> compareByModifiedTime(right, left)
                FileSortMode.MODIFIED_ASC -> compareByModifiedTime(left, right)
                FileSortMode.SIZE_DESC -> compareBySize(right, left)
                FileSortMode.SIZE_ASC -> compareBySize(left, right)
            }
        })
    }

    private fun compareFileNames(left: File, right: File): Int {
        return left.name.lowercase().compareTo(right.name.lowercase())
            .takeIf { it != 0 }
            ?: left.name.compareTo(right.name)
    }

    private fun compareByModifiedTime(left: File, right: File): Int {
        return left.lastModified().compareTo(right.lastModified())
            .takeIf { it != 0 }
            ?: compareFileNames(left, right)
    }

    private fun compareBySize(left: File, right: File): Int {
        return left.fileSortSize().compareTo(right.fileSortSize())
            .takeIf { it != 0 }
            ?: compareFileNames(left, right)
    }

    private fun File.fileTypeOrder(): Int {
        return if (isDirectory) 0 else 1
    }

    private fun File.fileSortSize(): Long {
        return if (isFile) length() else 0L
    }

    private fun currentSortMode(): FileSortMode {
        val savedMode = prefs.getString(KEY_SORT_MODE, null)
        return FileSortMode.values().firstOrNull { it.name == savedMode }
            ?: FileSortMode.NAME_ASC
    }

    private fun centerListPosition(position: Int) {
        val itemCount = fileItems.size
        val listHeight = listView.height
        if (position !in 0 until itemCount || listHeight <= 0) {
            return
        }

        val itemHeight = (listView.getChildAt(0)?.height ?: dpToPx(FILE_ITEM_HEIGHT_DP))
            .coerceAtLeast(1)
        val rowHeight = itemHeight + listView.dividerHeight.coerceAtLeast(0)
        val contentHeight = itemCount * rowHeight
        val maxScrollTop = (contentHeight - listHeight).coerceAtLeast(0)
        val desiredScrollTop = position * rowHeight - (listHeight - itemHeight) / 2
        val scrollTop = desiredScrollTop.coerceIn(0, maxScrollTop)
        val firstVisiblePosition = scrollTop / rowHeight
        val firstVisibleTop = -(scrollTop % rowHeight)

        listView.setSelectionFromTop(firstVisiblePosition, firstVisibleTop)
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
        val btnCopyPathName = content.findViewById<TextView>(R.id.btnCopyPathName)
        val btnRenameDirectoryName = content.findViewById<TextView>(R.id.btnRenameDirectoryName)
        val btnDeleteDirectory = content.findViewById<TextView>(R.id.btnDeleteDirectory)
        val isFavorite = FavoritePathStore.isFavorite(this, directory)

        tvDirectoryActionTitle.text = directory.name
        sizeBottomSheetActionIcons(
            btnFavoritePath,
            btnCopyPathName,
            btnRenameDirectoryName,
            btnDeleteDirectory
        )
        btnFavoritePath.text = getString(
            if (isFavorite) R.string.cancel_favorite else R.string.favorite_path_action
        )

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

        showMessage(getString(R.string.rename_directory_name_success))
        loadCurrentDirectory(targetDirectory.absolutePath)
    }

    private fun showArchiveMenu(file: File) {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_archive_actions, null)
        val tvArchiveActionTitle = content.findViewById<TextView>(R.id.tvArchiveActionTitle)
        val btnCopyFileName = content.findViewById<TextView>(R.id.btnCopyFileName)
        val btnRenameFileName = content.findViewById<TextView>(R.id.btnRenameFileName)
        val btnCopyStartMarker = content.findViewById<TextView>(R.id.btnCopyStartMarker)
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
            btnDeleteFile
        )
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
        if (!file.isFile) {
            showMessage(getString(R.string.message_invalid_file))
            loadCurrentDirectory(scrollStateToRestore = captureFileListScrollState())
            return
        }

        val scrollState = captureFileListScrollState()
        directoryLoadExecutor.execute {
            val deleted = runCatching { file.delete() }.getOrDefault(false)
            runOnUiThread {
                if (deleted) {
                    FavoriteComicStore.remove(this, file)
                    ReadingHistoryStore.remove(this, file)
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

        val scrollState = captureFileListScrollState()
        directoryLoadExecutor.execute {
            val deleted = runCatching { directory.deleteRecursively() }.getOrDefault(false)
            runOnUiThread {
                if (deleted) {
                    FavoritePathStore.remove(this, directory)
                    showMessage(getString(R.string.delete_directory_success))
                    loadCurrentDirectory(scrollStateToRestore = scrollState)
                } else {
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
            ReaderFileType.EPUB -> {
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

    private fun setCurrentPath(path: String) {
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

    private data class FileListScrollState(
        val directoryPath: String,
        val firstVisiblePosition: Int,
        val firstVisibleTop: Int,
        val anchorPath: String?,
        val anchorIsParent: Boolean
    ) {
        fun writeTo(outState: Bundle) {
            outState.putString(KEY_FILE_LIST_SCROLL_DIRECTORY_PATH, directoryPath)
            outState.putInt(KEY_FILE_LIST_SCROLL_POSITION, firstVisiblePosition)
            outState.putInt(KEY_FILE_LIST_SCROLL_TOP, firstVisibleTop)
            outState.putString(KEY_FILE_LIST_SCROLL_ANCHOR_PATH, anchorPath)
            outState.putBoolean(KEY_FILE_LIST_SCROLL_ANCHOR_IS_PARENT, anchorIsParent)
        }
    }

    private data class RoundedPopupMenuItem(
        val label: String,
        val isSelected: Boolean = false,
        val onClick: () -> Unit
    )

    private data class DirectorySimilarityProgress(
        val message: String,
        val completed: Int = 0,
        val total: Int = 0
    )

    private data class DirectorySimilarityReport(
        val outputFile: File,
        val directoryCount: Int,
        val pairCount: Int
    )

    private data class ComicMergeProgress(
        val message: String,
        val completed: Int = 0,
        val total: Int = 0
    )

    private data class ComicMergeResult(
        val outputDirectory: File?,
        val archiveCount: Int,
        val totalImageCount: Int,
        val copiedCount: Int,
        val failedCount: Int
    )

    private data class ComicMergeSource(
        val archive: File,
        val entries: List<String>
    )

    private data class FileMergeResult(
        val outputDirectory: File?,
        val folderCount: Int,
        val totalImageCount: Int,
        val copiedCount: Int,
        val failedCount: Int
    )

    private data class FileMergeSource(
        val directory: File,
        val images: List<File>
    )

    private data class SimilarDirectoryInfo(
        val name: String,
        val path: String
    )

    private data class SimilarDirectoryNameCandidate(
        val directory: SimilarDirectoryInfo,
        val normalizedName: String
    )

    private data class SimilarDirectoryNamePair(
        val left: SimilarDirectoryInfo,
        val right: SimilarDirectoryInfo,
        val similarity: Double
    )

    companion object {
        private const val PREFS_NAME = "saf_prefs"
        const val EXTRA_CENTER_TARGET_PATH = "center_target_path"
        private const val KEY_STORAGE_PERMISSION_PROMPTED = "storage_permission_prompted"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_CURRENT_PATH = "current_path"
        private const val KEY_FILE_LIST_SCROLL_DIRECTORY_PATH = "file_list_scroll_directory_path"
        private const val KEY_FILE_LIST_SCROLL_POSITION = "file_list_scroll_position"
        private const val KEY_FILE_LIST_SCROLL_TOP = "file_list_scroll_top"
        private const val KEY_FILE_LIST_SCROLL_ANCHOR_PATH = "file_list_scroll_anchor_path"
        private const val KEY_FILE_LIST_SCROLL_ANCHOR_IS_PARENT = "file_list_scroll_anchor_is_parent"
        private const val CLEAR_CLICK_STATE_DELAY_MS = 240L
        private const val FILE_ITEM_HEIGHT_DP = 75
        private val INVALID_FILE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        private const val ROUNDED_MENU_PADDING_HORIZONTAL_DP = 6
        private const val ROUNDED_MENU_PADDING_VERTICAL_DP = 6
        private const val ROUNDED_MENU_ITEM_HEIGHT_DP = 44
        private const val ROUNDED_MENU_ITEM_PADDING_HORIZONTAL_DP = 14
        private const val ROUNDED_MENU_VERTICAL_OFFSET_DP = 8
        private const val ROUNDED_MENU_ELEVATION_DP = 8
        private const val MIN_READING_HISTORY_PANEL_WIDTH_DP = 180
        private const val READING_HISTORY_PANEL_ELEVATION_DP = 8
        private const val READING_HISTORY_PANEL_ENTER_ANIMATION_MS = 180L
        private const val READING_HISTORY_PANEL_EXIT_ANIMATION_MS = 150L
        private const val READING_HISTORY_DATE_HEADER_VIEW_TYPE = 0
        private const val MENU_CLASSIFY_BY_START_MARKER = 1
        private const val MENU_FIND_SIMILAR_DIRECTORY_NAMES = 2
        private const val MENU_MERGE_COMICS_NON_RECURSIVE = 3
        private const val MENU_MERGE_FILES_NON_RECURSIVE = 4
        private const val DIRECTORY_NAME_SIMILARITY_THRESHOLD = 0.78
        private const val DIRECTORY_SIMILARITY_PROGRESS_ROW_INTERVAL = 25
        private const val DIRECTORY_SIMILARITY_REPORT_PREFIX = "ComicLab_similar_directory_names_"
        private const val DIRECTORY_SIMILARITY_REPORT_EXTENSION = ".txt"
        private const val MERGED_COMIC_DIRECTORY_PREFIX = "ComicLab_merged_"
        private const val MERGED_FILE_DIRECTORY_PREFIX = "ComicLab_file_merged_"
        private const val MERGED_COMIC_MIN_FILE_NUMBER_WIDTH = 4
        private const val MERGED_COMIC_DEFAULT_IMAGE_EXTENSION = "jpg"
        private const val MERGED_FILE_COPY_BUFFER_SIZE = 1024 * 1024
        private const val MENU_TITLE_FIND_SIMILAR_DIRECTORY_NAMES = "查找相似文件夹"
        private const val MENU_TITLE_MERGE_COMICS_NON_RECURSIVE = "漫画整合（不递归）"
        private const val MENU_TITLE_MERGE_FILES_NON_RECURSIVE = "文件整合（不递归）"
        private const val MESSAGE_FINDING_SIMILAR_DIRECTORY_NAMES = "正在查找命名接近的路径"
        private const val MESSAGE_MERGING_COMICS = "正在整合漫画"
        private const val MESSAGE_MERGING_FILES = "正在整合文件"
        private const val MESSAGE_SCANNING_DIRECTORIES = "正在扫描文件夹..."
        private const val MESSAGE_COMPARING_DIRECTORY_NAMES = "正在比较文件夹名称..."
        private const val MESSAGE_SCANNING_COMIC_ARCHIVES = "正在扫描当前路径下的漫画压缩包..."
        private const val MESSAGE_READING_COMIC_ARCHIVES = "正在读取压缩包图片列表..."
        private const val MESSAGE_SCANNING_IMAGE_DIRECTORIES = "正在扫描当前路径下的图片文件夹..."
        private const val MESSAGE_READING_IMAGE_DIRECTORIES = "正在读取文件夹图片列表..."
        private const val MESSAGE_DIRECTORY_SIMILARITY_REPORT_FAILED = "生成命名接近路径报告失败"
        private const val MESSAGE_MERGE_COMICS_FAILED = "漫画整合失败"
        private const val MESSAGE_MERGE_COMICS_NO_ARCHIVES = "当前路径下没有找到漫画压缩包"
        private const val MESSAGE_MERGE_COMICS_NO_IMAGES = "漫画压缩包中没有找到可整合的图片"
        private const val MESSAGE_MERGE_FILES_FAILED = "文件整合失败"
        private const val MESSAGE_MERGE_FILES_NO_FOLDERS = "当前路径下没有找到可整合的文件夹"
        private const val MESSAGE_MERGE_FILES_NO_IMAGES = "文件夹中没有找到可整合的图片"
        private val MERGED_COMIC_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        private val DIRECTORY_NAME_SIMILARITY_IGNORED_CHARS = setOf(
            '_',
            '-',
            '.',
            '·',
            '[',
            ']',
            '(',
            ')',
            '（',
            '）',
            '【',
            '】'
        )
        private val STORAGE_ROOT_PATH = Environment.getExternalStorageDirectory().absolutePath
    }

    private enum class FileSortMode(val labelResId: Int) {
        NAME_ASC(R.string.sort_by_name_asc),
        NAME_DESC(R.string.sort_by_name_desc),
        MODIFIED_DESC(R.string.sort_by_modified_desc),
        MODIFIED_ASC(R.string.sort_by_modified_asc),
        SIZE_DESC(R.string.sort_by_size_desc),
        SIZE_ASC(R.string.sort_by_size_asc)
    }
}
