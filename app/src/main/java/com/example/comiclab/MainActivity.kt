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
import java.io.File
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
    private var browserInitialized = false
    private var skipNextResumeDirectoryReload = false
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
            findViewById<View>(R.id.statusBarBackground)
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
            confirmClassifyCurrentDirectory()
        }

        btnSearch.setOnClickListener {
            loadCurrentDirectory()
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
            } else if (ComicArchive.isArchive(file)) {
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

        val restoredScrollState = savedInstanceState?.readFileListScrollState()
        if (!showStoragePermissionNoticeIfNeeded()) {
            initializeBrowserIfPermitted(restoredScrollState)
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
            .show()

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
        val btnClearReadingHistory = content.findViewById<Button>(R.id.btnClearReadingHistory)
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
                .show()
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
        val btnClearFavoritePaths = content.findViewById<Button>(R.id.btnClearFavoritePaths)
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
                .show()
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
        val btnClearFavoriteComics = content.findViewById<Button>(R.id.btnClearFavoriteComics)
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
                .show()
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

        if (browserInitialized) {
            loadCurrentDirectory(scrollStateToRestore = scrollStateToRestore)
            return
        }

        browserInitialized = true
        currentPath = savedCurrentPath()
        loadCurrentDirectory(scrollStateToRestore = scrollStateToRestore)
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
        val labels = sortModes.map { getString(it.labelResId) }.toTypedArray()
        val currentMode = currentSortMode()
        val checkedItem = sortModes.indexOf(currentMode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.sort)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                val selectedMode = sortModes.getOrNull(which) ?: return@setSingleChoiceItems
                prefs.edit()
                    .putString(KEY_SORT_MODE, selectedMode.name)
                    .apply()
                dialog.dismiss()
                loadCurrentDirectory()
            }
            .show()
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
            .show()
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
            .create()

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
        val isFavorite = FavoritePathStore.isFavorite(this, directory)

        tvDirectoryActionTitle.text = directory.name
        sizeBottomSheetActionIcons(btnFavoritePath, btnCopyPathName, btnRenameDirectoryName)
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

        dialog.setContentView(content)
        dialog.show()
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
            .show()
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
        val isFavorite = FavoriteComicStore.isFavorite(this, file)

        tvArchiveActionTitle.text = file.name
        sizeBottomSheetActionIcons(
            btnRead,
            btnFavoriteComic,
            btnCopyFileName,
            btnRenameFileName,
            btnCopyStartMarker
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
            if (!ComicArchive.isSupportedArchive(file)) {
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

        dialog.setContentView(content)
        dialog.show()
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
            .show()
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
        if (!ComicArchive.isSupportedArchive(file)) {
            showMessage(getString(R.string.unsupported_archive_format))
            return
        }

        val intent = Intent(this, MangaPreviewActivity::class.java).apply {
            putExtra(MangaPreviewActivity.EXTRA_ARCHIVE_PATH, file.absolutePath)
        }
        startActivity(intent)
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

    companion object {
        private const val PREFS_NAME = "saf_prefs"
        private const val KEY_STORAGE_PERMISSION_PROMPTED = "storage_permission_prompted"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_CURRENT_PATH = "current_path"
        private const val KEY_FILE_LIST_SCROLL_DIRECTORY_PATH = "file_list_scroll_directory_path"
        private const val KEY_FILE_LIST_SCROLL_POSITION = "file_list_scroll_position"
        private const val KEY_FILE_LIST_SCROLL_TOP = "file_list_scroll_top"
        private const val KEY_FILE_LIST_SCROLL_ANCHOR_PATH = "file_list_scroll_anchor_path"
        private const val KEY_FILE_LIST_SCROLL_ANCHOR_IS_PARENT = "file_list_scroll_anchor_is_parent"
        private const val CLEAR_CLICK_STATE_DELAY_MS = 120L
        private const val FILE_ITEM_HEIGHT_DP = 75
        private val INVALID_FILE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        private const val MIN_READING_HISTORY_PANEL_WIDTH_DP = 180
        private const val READING_HISTORY_PANEL_ELEVATION_DP = 8
        private const val READING_HISTORY_PANEL_ENTER_ANIMATION_MS = 180L
        private const val READING_HISTORY_PANEL_EXIT_ANIMATION_MS = 150L
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
