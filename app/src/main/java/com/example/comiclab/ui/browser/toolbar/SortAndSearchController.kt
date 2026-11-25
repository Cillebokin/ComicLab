package com.example.comiclab.ui.browser.toolbar

import android.content.Intent
import android.content.SharedPreferences
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.ui.browser.DirectorySortOrder
import com.example.comiclab.ui.browser.DirectorySortPolicy
import com.example.comiclab.R
import com.example.comiclab.SearchActivity
import com.example.comiclab.ui.privacy.PrivacyAccessGuard
import com.example.comiclab.ui.privacy.PrivacyAccessGuard.PRIVACY_VIRTUAL_ROOT_PATH
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File

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

        val path = currentPathProvider()
        val privateMode = PrivacySessionManager.isInPrivacySpace
        val mode = when {
            privateMode && path == PRIVACY_VIRTUAL_ROOT_PATH -> SearchActivity.Mode.PRIVACY_VIRTUAL_ROOT
            PrivacyAccessGuard.resolveLocalPathInMode(activity, File(path), privateMode)?.isDirectory != true -> return
            privateMode -> SearchActivity.Mode.PRIVACY_SUBTREE
            else -> SearchActivity.Mode.NORMAL
        }
        val intent = Intent(activity, SearchActivity::class.java).apply {
            putExtra(SearchActivity.EXTRA_SEARCH_ROOT_PATH, path)
            putExtra(SearchActivity.EXTRA_PRIVACY_SEARCH_MODE, mode.name)
            if (privateMode) putExtra(SearchActivity.EXTRA_PRIVACY_SEARCH_EPOCH, PrivacySessionManager.sessionEpoch)
        }
        activity.startActivity(intent)
    }

    internal fun currentDirectorySortOrder(): DirectorySortOrder {
        return DirectorySortPolicy.savedOrder(prefsProvider())
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
