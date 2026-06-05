package com.example.comiclab

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
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
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SearchActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var inputSearchKeyword: EditText
    private lateinit var btnRunSearch: ImageButton
    private lateinit var checkSearchDirectories: CheckBox
    private lateinit var checkSearchFiles: CheckBox
    private lateinit var tvSearchRoot: TextView
    private lateinit var tvSearchStatus: TextView
    private lateinit var progressSearch: ProgressBar
    private lateinit var listSearchResults: ListView
    private lateinit var searchResultAdapter: SearchResultAdapter

    private val searchResults = mutableListOf<File>()
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var destroyed = false
    private var searchRootPath = Environment.getExternalStorageDirectory().absolutePath

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
        tvSearchStatus = findViewById(R.id.tvSearchStatus)
        progressSearch = findViewById(R.id.progressSearch)
        listSearchResults = findViewById(R.id.listSearchResults)

        searchRootPath = intent.getStringExtra(EXTRA_SEARCH_ROOT_PATH)
            ?.takeIf { it.isNotBlank() }
            ?: Environment.getExternalStorageDirectory().absolutePath

        searchResultAdapter = SearchResultAdapter(this, searchResults)
        listSearchResults.adapter = searchResultAdapter

        tvSearchRoot.text = getString(R.string.search_root, searchRootPath)
        tvSearchStatus.text = getString(R.string.search_idle)
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

        listSearchResults.setOnItemClickListener { _, view, position, _ ->
            val result = searchResults.getOrNull(position) ?: return@setOnItemClickListener
            view.isPressed = true
            mainHandler.postDelayed({
                view.isPressed = false
                openResultInFileBrowser(result)
            }, SEARCH_CLICK_STATE_DELAY_MS)
        }
    }

    override fun onDestroy() {
        destroyed = true
        searchGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        searchExecutor.shutdownNow()
        searchResultAdapter.close()
        super.onDestroy()
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
        val root = File(searchRootPath).takeIf { it.isDirectory }
            ?: Environment.getExternalStorageDirectory()
        val generation = searchGeneration.incrementAndGet()
        searchResults.clear()
        searchResultAdapter.notifyDataSetChanged()
        tvSearchStatus.text = getString(R.string.searching)
        progressSearch.visibility = View.VISIBLE

        searchExecutor.execute {
            val results = searchFiles(root, keyword, includeDirectories, includeFiles, generation)
            runOnUiThread {
                if (destroyed || generation != searchGeneration.get()) {
                    return@runOnUiThread
                }

                progressSearch.visibility = View.GONE
                searchResults.clear()
                searchResults.addAll(results)
                searchResultAdapter.notifyDataSetChanged()
                tvSearchStatus.text = if (results.isEmpty()) {
                    getString(R.string.search_no_results)
                } else {
                    getString(R.string.search_results_count, results.size)
                }
            }
        }
    }

    private fun searchFiles(
        root: File,
        keyword: String,
        includeDirectories: Boolean,
        includeFiles: Boolean,
        generation: Int
    ): List<File> {
        val normalizedKeyword = keyword.lowercase(Locale.ROOT)
        val pending = ArrayDeque<File>()
        val visitedDirectories = mutableSetOf<String>()
        val results = mutableListOf<File>()
        pending.add(root)

        while (pending.isNotEmpty() &&
            !destroyed &&
            generation == searchGeneration.get()
        ) {
            val directory = pending.removeFirst()
            if (!directory.isDirectory || !visitedDirectories.add(directory.stablePath())) {
                continue
            }

            val children = runCatching {
                directory.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?.sortedWith(compareBy<File> { if (it.isDirectory) 0 else 1 }
                        .thenBy { it.name.lowercase(Locale.ROOT) })
                    .orEmpty()
            }.getOrDefault(emptyList())

            children.forEach { child ->
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

    private fun File.stablePath(): String {
        return runCatching { canonicalPath }.getOrDefault(absolutePath)
    }

    private fun openResultInFileBrowser(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, R.string.message_invalid_file, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_CENTER_TARGET_PATH, file.absolutePath)
        }
        startActivity(intent)
        finish()
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java) ?: return
        inputMethodManager.hideSoftInputFromWindow(inputSearchKeyword.windowToken, 0)
        inputSearchKeyword.clearFocus()
    }

    companion object {
        const val EXTRA_SEARCH_ROOT_PATH = "search_root_path"
        private const val SEARCH_CLICK_STATE_DELAY_MS = 160L
    }
}
