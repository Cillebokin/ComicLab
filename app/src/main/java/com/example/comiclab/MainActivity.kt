package com.example.comiclab

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
    private lateinit var btnSearch: ImageButton
    private lateinit var fileListAdapter: FileListAdapter

    private val fileItems = mutableListOf<FileItem>()
    private val directoryLoadExecutor = Executors.newSingleThreadExecutor()
    private val directoryLoadGeneration = AtomicInteger(0)

    private var browserInitialized = false
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
        btnSearch = findViewById(R.id.btnSearch)

        fileListAdapter = FileListAdapter(this, fileItems)
        listView.adapter = fileListAdapter
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleSystemBack()
            }
        })

        btnBack.setOnClickListener {
            goParent()
        }

        btnOptions.setOnClickListener {
            openSettings()
        }

        btnSort.setOnClickListener {
            showSortDialog()
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

        if (!showStoragePermissionNoticeIfNeeded()) {
            initializeBrowserIfPermitted()
        }
    }

    override fun onResume() {
        super.onResume()
        clearFileListTouchState()
        if (prefs.getBoolean(KEY_STORAGE_PERMISSION_PROMPTED, false)) {
            initializeBrowserIfPermitted()
        }
    }

    override fun onPause() {
        clearFileListTouchState()
        saveCurrentPath()
        super.onPause()
    }

    override fun onDestroy() {
        directoryLoadGeneration.incrementAndGet()
        directoryLoadExecutor.shutdownNow()
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

    private fun initializeBrowserIfPermitted() {
        if (!Environment.isExternalStorageManager()) {
            etPath.setText(R.string.storage_permission_required)
            fileItems.clear()
            notifyListChanged()
            return
        }

        if (browserInitialized) {
            loadCurrentDirectory()
            return
        }

        browserInitialized = true
        currentPath = savedCurrentPath()
        loadCurrentDirectory()
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

    private fun loadCurrentDirectory(pathToCenter: String? = null) {
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
                pathToCenter?.let { centerFileItemIfPresent(it) }
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

    private fun showArchiveMenu(file: File) {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.bottom_sheet_archive_actions, null)
        val btnCopyFileName = content.findViewById<TextView>(R.id.btnCopyFileName)
        val btnRead = content.findViewById<TextView>(R.id.btnReadComic)

        btnCopyFileName.setOnClickListener {
            copyToClipboard(
                label = getString(R.string.copy_file_name),
                text = file.name,
                copiedMessage = getString(R.string.copied_file_name)
            )
            dialog.dismiss()
        }

        btnRead.setOnClickListener {
            dialog.dismiss()
            openMangaPreview(file)
        }

        dialog.setContentView(content)
        dialog.show()
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

    companion object {
        private const val PREFS_NAME = "saf_prefs"
        private const val KEY_STORAGE_PERMISSION_PROMPTED = "storage_permission_prompted"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_CURRENT_PATH = "current_path"
        private const val CLEAR_CLICK_STATE_DELAY_MS = 120L
        private const val FILE_ITEM_HEIGHT_DP = 75
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
