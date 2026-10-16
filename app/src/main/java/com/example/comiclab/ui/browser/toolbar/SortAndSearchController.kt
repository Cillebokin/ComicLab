package com.example.comiclab.ui.browser.toolbar

import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.DirectorySortOrder
import com.example.comiclab.R
import com.example.comiclab.SearchActivity

/**
 * Owns the sort menu and the search entry of the directory browser.
 * Moved out of MainActivity without behaviour change.
 */
internal class SortAndSearchController(
    private val activity: AppCompatActivity,
    private val prefsProvider: () -> SharedPreferences,
    private val sortAnchorProvider: () -> View,
    private val currentPathProvider: () -> String,
    private val openManageAllFilesAccessSettings: () -> Unit,
    private val reloadCurrentDirectory: () -> Unit
) {
    internal fun showSortDialog() {
        val sortModes = FileSortMode.values()
        val currentMode = currentSortMode()
        val items = sortModes.map { sortMode ->
            RoundedPopupMenuItem(
                label = activity.getString(sortMode.labelResId),
                isSelected = sortMode == currentMode
            ) {
                prefsProvider().edit()
                    .putString(KEY_SORT_MODE, sortMode.name)
                    .apply()
                reloadCurrentDirectory()
            }
        }

        activity.showRoundedPopupMenu(sortAnchorProvider(), items, widthDp = 208)
    }

    internal fun openSearch() {
        if (!Environment.isExternalStorageManager()) {
            openManageAllFilesAccessSettings()
            return
        }

        val intent = Intent(activity, SearchActivity::class.java).apply {
            putExtra(SearchActivity.EXTRA_SEARCH_ROOT_PATH, currentPathProvider())
        }
        activity.startActivity(intent)
    }

    internal fun currentDirectorySortOrder(): DirectorySortOrder {
        return when (currentSortMode()) {
            FileSortMode.NAME_ASC -> DirectorySortOrder.NAME_ASC
            FileSortMode.NAME_DESC -> DirectorySortOrder.NAME_DESC
            FileSortMode.MODIFIED_ASC -> DirectorySortOrder.MODIFIED_ASC
            FileSortMode.MODIFIED_DESC -> DirectorySortOrder.MODIFIED_DESC
            FileSortMode.SIZE_ASC -> DirectorySortOrder.SIZE_ASC
            FileSortMode.SIZE_DESC -> DirectorySortOrder.SIZE_DESC
        }
    }

    private fun currentSortMode(): FileSortMode {
        val savedMode = prefsProvider().getString(KEY_SORT_MODE, null)
        return FileSortMode.values().firstOrNull { it.name == savedMode }
            ?: FileSortMode.NAME_ASC
    }

    private enum class FileSortMode(val labelResId: Int) {
        NAME_ASC(R.string.sort_by_name_asc),
        NAME_DESC(R.string.sort_by_name_desc),
        MODIFIED_DESC(R.string.sort_by_modified_desc),
        MODIFIED_ASC(R.string.sort_by_modified_asc),
        SIZE_DESC(R.string.sort_by_size_desc),
        SIZE_ASC(R.string.sort_by_size_asc)
    }

    private companion object {
        private const val KEY_SORT_MODE = "sort_mode"
    }
}
