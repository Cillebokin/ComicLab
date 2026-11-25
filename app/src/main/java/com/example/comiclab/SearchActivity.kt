package com.example.comiclab

import com.example.comiclab.ui.common.SystemBars

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.ui.browser.SearchResultAdapter
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.privacy.PrivacyAccessGuard
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SearchActivity : AppCompatActivity() {

    internal enum class Mode { NORMAL, PRIVACY_VIRTUAL_ROOT, PRIVACY_SUBTREE }

    private lateinit var btnBack: ImageButton
    private lateinit var inputSearchKeyword: EditText
    private lateinit var btnRunSearch: ImageButton
    private lateinit var checkSearchDirectories: CheckBox
    private lateinit var checkSearchFiles: CheckBox
    private lateinit var tvSearchRoot: TextView
    private lateinit var layoutSearchStatus: View
    private lateinit var tvSearchStatus: TextView
    private lateinit var progressSearch: ProgressBar
    private lateinit var listSearchResults: ListView
    private lateinit var searchResultAdapter: SearchResultAdapter

    private val searchResults = mutableListOf<File>()
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)

    @Volatile
    private var destroyed = false
    private var searchRootPath = Environment.getExternalStorageDirectory().absolutePath
    private var searchMode = Mode.NORMAL
    private var searchEpoch = Long.MIN_VALUE
    private val isPrivateSearch: Boolean get() = searchMode != Mode.NORMAL
    private val lockListener = PrivacySessionManager.Listener { if (isPrivateSearch) closeSearch() }
    private var markListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground),
            statusBarColorResId = R.color.comiclab_file_picker_background,
            lightStatusBars = true
        )

        btnBack = findViewById(R.id.btnBack)
        inputSearchKeyword = findViewById(R.id.inputSearchKeyword)
        btnRunSearch = findViewById(R.id.btnRunSearch)
        checkSearchDirectories = findViewById(R.id.checkSearchDirectories)
        checkSearchFiles = findViewById(R.id.checkSearchFiles)
        tvSearchRoot = findViewById(R.id.tvSearchRoot)
        layoutSearchStatus = findViewById(R.id.layoutSearchStatus)
        tvSearchStatus = findViewById(R.id.tvSearchStatus)
        progressSearch = findViewById(R.id.progressSearch)
        listSearchResults = findViewById(R.id.listSearchResults)

        searchRootPath = intent.getStringExtra(EXTRA_SEARCH_ROOT_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: Environment.getExternalStorageDirectory().absolutePath

        val requestedMode = intent.getStringExtra(EXTRA_PRIVACY_SEARCH_MODE)
        searchMode = Mode.entries.firstOrNull { it.name == (requestedMode ?: Mode.NORMAL.name) } ?: run {
            finish()
            return
        }
        searchEpoch = intent.getLongExtra(EXTRA_PRIVACY_SEARCH_EPOCH, Long.MIN_VALUE)
        val root = singleSearchRoot()
        if (!hasValidScope(root)) {
            closeSearch()
            return
        }
        if (isPrivateSearch) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            inputSearchKeyword.isSaveEnabled = false
        }
        installResultAdapter()
        PrivacySessionManager.addListener(lockListener)
        markListener = PrivacyStore.registerDirectoryChangeListener(this) {
            runOnUiThread { if (!destroyed && !isFinishing) revalidateResults(resetAdapter = true) }
        }

        val rootLabel = root?.path ?: getString(R.string.privacy_browser_title)
        tvSearchRoot.text = getString(R.string.search_root, rootLabel)
        progressSearch.visibility = View.GONE

        btnBack.setOnClickListener {
            finish()
        }

        btnRunSearch.setOnClickListener {
            runSearch()
        }

        inputSearchKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }

        listSearchResults.setOnItemClickListener { _, _, position, _ ->
            val result = searchResults.getOrNull(position) ?: return@setOnItemClickListener
            openResultInFileBrowser(result)
        }
    }

    override fun onDestroy() {
        destroyed = true
        searchGeneration.incrementAndGet()
        searchExecutor.shutdownNow()
        PrivacySessionManager.removeListener(lockListener)
        markListener?.let { PrivacyStore.unregisterDirectoryChangeListener(this, it) }
        if (::searchResultAdapter.isInitialized) searchResultAdapter.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && ::searchResultAdapter.isInitialized) revalidateResults()
    }

    private fun runSearch() {
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_SHORT).show()
            return
        }

        val keyword = inputSearchKeyword.text?.toString()?.trim().orEmpty()
        if (keyword.isBlank()) {
            Toast.makeText(this, R.string.search_empty_keyword, Toast.LENGTH_SHORT).show()
            return
        }
        val includeDirectories = checkSearchDirectories.isChecked
        val includeFiles = checkSearchFiles.isChecked
        if (!includeDirectories && !includeFiles) {
            Toast.makeText(this, R.string.search_no_selected_types, Toast.LENGTH_SHORT).show()
            return
        }

        hideKeyboard()
        val root = singleSearchRoot()
        if (!hasValidScope(root)) {
            closeSearch()
            return
        }
        val generation = searchGeneration.incrementAndGet()
        searchResults.clear()
        searchResultAdapter.notifyDataSetChanged()
        layoutSearchStatus.visibility = View.VISIBLE
        tvSearchStatus.text = getString(R.string.searching)
        progressSearch.visibility = View.VISIBLE

        searchExecutor.execute {
            val results = searchFiles(root, keyword, includeDirectories, includeFiles, generation)
            runOnUiThread {
                if (destroyed || isFinishing || generation != searchGeneration.get()) {
                    return@runOnUiThread
                }
                val latestRoot = singleSearchRoot()
                if (!hasValidScope(latestRoot) || root != latestRoot) {
                    closeSearch()
                    return@runOnUiThread
                }

                progressSearch.visibility = View.GONE
                searchResults.clear()
                searchResults.addAll(results.filter { allowedResult(it, root) != null })
                searchResultAdapter.notifyDataSetChanged()
                updateResultStatus()
            }
        }
    }

    private fun searchFiles(
        root: File?,
        keyword: String,
        includeDirectories: Boolean,
        includeFiles: Boolean,
        generation: Int
    ): List<File> {
        val normalizedKeyword = keyword.lowercase(Locale.ROOT)
        val pending = ArrayDeque<File>()
        val visitedDirectories = mutableSetOf<String>()
        val results = mutableListOf<File>()
        if (root != null) {
            pending.add(root)
        } else if (searchMode == Mode.PRIVACY_VIRTUAL_ROOT) {
            for (directory in PrivacyStore.visibleRootDirectories(this)) {
                if (allowedResult(directory, null)?.isDirectory != true) continue
                pending.add(directory)
                if (includeDirectories && directory.name.lowercase(Locale.ROOT).contains(normalizedKeyword)) {
                    results.add(directory)
                }
            }
        }

        while (pending.isNotEmpty() &&
            !destroyed &&
            generation == searchGeneration.get() && matchesSearchMode()
        ) {
            val directory = pending.removeFirst()
            val target = allowedResult(directory, root)
            if (target?.isDirectory != true || !visitedDirectories.add(target.path)) {
                continue
            }

            val children = runCatching {
                target.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.sortedWith(compareBy<File> { if (it.isDirectory) 0 else 1 }
                        .thenBy { it.name.lowercase(Locale.ROOT) })
                    .orEmpty()
            }.getOrDefault(emptyList())

            children.forEach { child ->
                if (allowedResult(child, root) == null) return@forEach
                val isSearchableType = if (child.isDirectory) includeDirectories else includeFiles
                if (isSearchableType &&
                    child.name.lowercase(Locale.ROOT).contains(normalizedKeyword)
                ) {
                    results.add(child)
                }
                if (child.isDirectory) {
                    pending.add(child)
                }
            }
        }

        return results.sortedWith(
            compareBy<File> { if (it.isDirectory) 0 else 1 }
                .thenBy { it.absolutePath.lowercase(Locale.ROOT) }
        )
    }

    private fun openResultInFileBrowser(file: File) {
        val root = singleSearchRoot()
        if (!hasValidScope(root)) {
            closeSearch()
            return
        }
        val target = allowedResult(file, root)
        if (target?.exists() != true) {
            revalidateResults()
            Toast.makeText(this, R.string.message_invalid_file, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_CENTER_TARGET_PATH, target.path)
            if (isPrivateSearch) putExtra(MainActivity.EXTRA_CENTER_TARGET_PRIVACY_EPOCH, searchEpoch)
        }
        startActivity(intent)
        finish()
    }

    private fun installResultAdapter() {
        if (::searchResultAdapter.isInitialized) searchResultAdapter.close()
        searchResultAdapter = SearchResultAdapter(
            this, searchResults,
            canShowFile = { file ->
                val root = singleSearchRoot()
                hasValidScope(root) && allowedResult(file, root) != null
            },
            onCollectionCoverClick = ::openResultInFileBrowser
        )
        listSearchResults.adapter = searchResultAdapter
    }

    private fun matchesSearchMode(): Boolean = if (isPrivateSearch) {
        PrivacySessionManager.isCurrentSession(searchEpoch)
    } else !PrivacySessionManager.isInPrivacySpace

    private fun singleSearchRoot(): File? = if (searchMode == Mode.PRIVACY_VIRTUAL_ROOT) null else
        PrivacyAccessGuard.resolveLocalPathInMode(this, File(searchRootPath), isPrivateSearch)?.takeIf(File::isDirectory)

    private fun hasValidScope(root: File?): Boolean = Environment.isExternalStorageManager() && matchesSearchMode() &&
        (searchMode == Mode.PRIVACY_VIRTUAL_ROOT || root != null)

    private fun allowedResult(file: File, root: File?): File? {
        if (!matchesSearchMode()) return null
        return PrivacyAccessGuard.resolveLocalPathInMode(this, file, isPrivateSearch)?.takeIf {
            root == null || it.toPath().startsWith(root.toPath())
        }
    }

    private fun revalidateResults(resetAdapter: Boolean = false) {
        val root = singleSearchRoot()
        if (!hasValidScope(root)) {
            closeSearch()
        } else {
            val removed = searchResults.removeAll { allowedResult(it, root) == null || !it.exists() }
            if (resetAdapter) installResultAdapter()
            else if (removed) searchResultAdapter.notifyDataSetChanged()
            if (removed && progressSearch.visibility != View.VISIBLE) updateResultStatus()
        }
    }

    private fun updateResultStatus() {
        tvSearchStatus.text = if (searchResults.isEmpty()) getString(R.string.search_no_results)
            else getString(R.string.search_results_count, searchResults.size)
    }

    private fun closeSearch() {
        searchGeneration.incrementAndGet()
        listSearchResults.visibility = View.INVISIBLE
        inputSearchKeyword.text.clear()
        tvSearchRoot.text = ""
        tvSearchStatus.text = ""
        searchResults.clear()
        if (::searchResultAdapter.isInitialized) {
            searchResultAdapter.close()
            searchResultAdapter.notifyDataSetChanged()
        }
        finish()
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java) ?: return
        inputMethodManager.hideSoftInputFromWindow(inputSearchKeyword.windowToken, 0)
        inputSearchKeyword.clearFocus()
    }

    companion object {
        const val EXTRA_SEARCH_ROOT_PATH = "search_root_path"
        const val EXTRA_PRIVACY_SEARCH_MODE = "privacy_search_mode"
        internal const val EXTRA_PRIVACY_SEARCH_EPOCH = "privacy_search_epoch"
    }
}
