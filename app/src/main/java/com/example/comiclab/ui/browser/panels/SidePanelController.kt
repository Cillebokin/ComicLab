package com.example.comiclab.ui.browser.panels

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.InsetDividerItemDecoration
import com.example.comiclab.R
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.showRounded
import com.example.comiclab.ui.browser.dpToPx
import java.io.File

/**
 * Owns the three right-side panels (reading history, favourite paths, favourite comics)
 * and the scrim behind them. Moved out of MainActivity without behaviour change.
 */
internal class SidePanelController(
    private val activity: AppCompatActivity,
    private val scrimProvider: () -> View,
    private val onOpenMangaPreview: (File) -> Unit,
    private val onJumpToDirectory: (String) -> Unit,
    private val onNavigateToDirectory: (String) -> Unit,
    private val onListChanged: () -> Unit
) {
    private val readingHistoryScrim: View
        get() = scrimProvider()

    private var readingHistoryPopupWindow: PopupWindow? = null
    private var readingHistoryAdapter: ReadingHistoryAdapter? = null
    private var favoriteComicsPopupWindow: PopupWindow? = null
    private var favoriteComicAdapter: FavoriteComicAdapter? = null
    private var favoritePathsPopupWindow: PopupWindow? = null
    private var favoritePathAdapter: FavoritePathAdapter? = null
    private var isReadingHistoryScrimFadingOut = false

    internal fun showReadingHistoryPanel() {
        val rootView = activity.findViewById<View>(R.id.main)
        val screenWidth = rootView.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 3 / 4)
            .coerceAtLeast(activity.dpToPx(MIN_READING_HISTORY_PANEL_WIDTH_DP))
        val content = activity.layoutInflater.inflate(R.layout.panel_reading_history, null)
        val listReadingHistory = content.findViewById<RecyclerView>(R.id.listReadingHistory)
        val tvReadingHistoryEmpty = content.findViewById<TextView>(R.id.tvReadingHistoryEmpty)
        val btnClearReadingHistory = content.findViewById<ImageButton>(R.id.btnClearReadingHistory)
        val historyItems = ReadingHistoryStore.items(activity).toMutableList()
        val historyAdapter = ReadingHistoryAdapter(
            context = activity,
            items = historyItems,
            onItemClick = { item ->
                dismissReadingHistoryPanel()
                onOpenMangaPreview(item.file)
            },
            onJumpToPath = { item ->
                dismissReadingHistoryPanel()
                onJumpToDirectory(item.file.absolutePath)
            },
            onItemsEmptyChanged = { isEmpty ->
                listReadingHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
                tvReadingHistoryEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            }
        )

        listReadingHistory.layoutManager = LinearLayoutManager(activity)
        listReadingHistory.addItemDecoration(
            InsetDividerItemDecoration(
                context = activity,
                skipAdjacentViewTypes = setOf(READING_HISTORY_DATE_HEADER_VIEW_TYPE)
            )
        )
        listReadingHistory.adapter = historyAdapter
        listReadingHistory.visibility = if (historyItems.isEmpty()) View.GONE else View.VISIBLE
        tvReadingHistoryEmpty.visibility = if (historyItems.isEmpty()) View.VISIBLE else View.GONE
        btnClearReadingHistory.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.clear_reading_history_title)
                .setMessage(R.string.clear_reading_history_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    ReadingHistoryStore.clear(activity)
                    historyAdapter.clearItems()
                }
                .setNegativeButton(R.string.no, null)
                .showRounded()
        }

        dismissSidePanels()
        showReadingHistoryScrim()
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
            elevation = activity.dpToPx(READING_HISTORY_PANEL_ELEVATION_DP).toFloat()
            setOnDismissListener {
                historyAdapter.close()
                if (readingHistoryAdapter === historyAdapter) {
                    readingHistoryAdapter = null
                    readingHistoryPopupWindow = null
                    hideReadingHistoryScrim()
                }
            }
            showAtLocation(rootView, Gravity.END or Gravity.TOP, 0, 0)
        }
    }

    internal fun showFavoritePathsPanel() {
        val rootView = activity.findViewById<View>(R.id.main)
        val screenWidth = rootView.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 3 / 4)
            .coerceAtLeast(activity.dpToPx(MIN_READING_HISTORY_PANEL_WIDTH_DP))
        val content = activity.layoutInflater.inflate(R.layout.panel_favorite_paths, null)
        val listFavoritePaths = content.findViewById<RecyclerView>(R.id.listFavoritePaths)
        val tvFavoritePathsEmpty = content.findViewById<TextView>(R.id.tvFavoritePathsEmpty)
        val btnClearFavoritePaths = content.findViewById<ImageButton>(R.id.btnClearFavoritePaths)
        val favoriteItems = FavoritePathStore.items(activity).toMutableList()
        val adapter = FavoritePathAdapter(
            context = activity,
            items = favoriteItems,
            onItemClick = { item ->
                dismissFavoritePathsPanel()
                onNavigateToDirectory(item.directory.absolutePath)
            },
            onItemsEmptyChanged = { isEmpty ->
                listFavoritePaths.visibility = if (isEmpty) View.GONE else View.VISIBLE
                tvFavoritePathsEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            }
        )

        listFavoritePaths.layoutManager = LinearLayoutManager(activity)
        listFavoritePaths.addItemDecoration(InsetDividerItemDecoration(activity))
        listFavoritePaths.adapter = adapter
        listFavoritePaths.visibility = if (favoriteItems.isEmpty()) View.GONE else View.VISIBLE
        tvFavoritePathsEmpty.visibility = if (favoriteItems.isEmpty()) View.VISIBLE else View.GONE
        btnClearFavoritePaths.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.clear_favorite_paths_title)
                .setMessage(R.string.clear_favorite_paths_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    FavoritePathStore.clear(activity)
                    adapter.clearItems()
                    onListChanged()
                }
                .setNegativeButton(R.string.no, null)
                .showRounded()
        }

        dismissSidePanels()
        showReadingHistoryScrim()
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
            elevation = activity.dpToPx(READING_HISTORY_PANEL_ELEVATION_DP).toFloat()
            setOnDismissListener {
                if (favoritePathAdapter === adapter) {
                    favoritePathAdapter = null
                    favoritePathsPopupWindow = null
                    hideReadingHistoryScrim()
                    onListChanged()
                }
            }
            showAtLocation(rootView, Gravity.END or Gravity.TOP, 0, 0)
        }
    }

    internal fun showFavoriteComicsPanel() {
        val rootView = activity.findViewById<View>(R.id.main)
        val screenWidth = rootView.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        val panelWidth = (screenWidth * 3 / 4)
            .coerceAtLeast(activity.dpToPx(MIN_READING_HISTORY_PANEL_WIDTH_DP))
        val content = activity.layoutInflater.inflate(R.layout.panel_favorite_comics, null)
        val listFavoriteComics = content.findViewById<RecyclerView>(R.id.listFavoriteComics)
        val tvFavoriteComicsEmpty = content.findViewById<TextView>(R.id.tvFavoriteComicsEmpty)
        val btnClearFavoriteComics = content.findViewById<ImageButton>(R.id.btnClearFavoriteComics)
        val favoriteItems = FavoriteComicStore.items(activity).toMutableList()
        val adapter = FavoriteComicAdapter(
            context = activity,
            items = favoriteItems,
            onItemClick = { item ->
                dismissFavoriteComicsPanel()
                onOpenMangaPreview(item.file)
            },
            onJumpToPath = { item ->
                dismissFavoriteComicsPanel()
                onJumpToDirectory(item.file.absolutePath)
            },
            onItemsEmptyChanged = { isEmpty ->
                listFavoriteComics.visibility = if (isEmpty) View.GONE else View.VISIBLE
                tvFavoriteComicsEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            }
        )

        listFavoriteComics.layoutManager = LinearLayoutManager(activity)
        listFavoriteComics.addItemDecoration(InsetDividerItemDecoration(activity))
        listFavoriteComics.adapter = adapter
        listFavoriteComics.visibility = if (favoriteItems.isEmpty()) View.GONE else View.VISIBLE
        tvFavoriteComicsEmpty.visibility = if (favoriteItems.isEmpty()) View.VISIBLE else View.GONE
        btnClearFavoriteComics.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.clear_favorite_comics_title)
                .setMessage(R.string.clear_favorite_comics_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    FavoriteComicStore.clear(activity)
                    adapter.clearItems()
                    onListChanged()
                }
                .setNegativeButton(R.string.no, null)
                .showRounded()
        }

        dismissSidePanels()
        showReadingHistoryScrim()
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
            elevation = activity.dpToPx(READING_HISTORY_PANEL_ELEVATION_DP).toFloat()
            setOnDismissListener {
                adapter.close()
                if (favoriteComicAdapter === adapter) {
                    favoriteComicAdapter = null
                    favoriteComicsPopupWindow = null
                    hideReadingHistoryScrim()
                    onListChanged()
                }
            }
            showAtLocation(rootView, Gravity.END or Gravity.TOP, 0, 0)
        }
    }

    internal fun dismissSidePanels() {
        dismissReadingHistoryPanel()
        dismissFavoriteComicsPanel()
        dismissFavoritePathsPanel()
    }

    private fun dismissReadingHistoryPanel() {
        readingHistoryPopupWindow?.dismiss()
        readingHistoryPopupWindow = null
        readingHistoryAdapter?.close()
        readingHistoryAdapter = null
        hideReadingHistoryScrim()
    }

    private fun dismissFavoritePathsPanel() {
        favoritePathsPopupWindow?.dismiss()
        favoritePathsPopupWindow = null
        favoritePathAdapter = null
        hideReadingHistoryScrim()
    }

    private fun dismissFavoriteComicsPanel() {
        favoriteComicsPopupWindow?.dismiss()
        favoriteComicsPopupWindow = null
        favoriteComicAdapter?.close()
        favoriteComicAdapter = null
        hideReadingHistoryScrim()
    }

    private fun showReadingHistoryScrim() {
        readingHistoryScrim.animate().cancel()
        isReadingHistoryScrimFadingOut = false
        if (readingHistoryScrim.visibility != View.VISIBLE) {
            readingHistoryScrim.alpha = 0f
            readingHistoryScrim.visibility = View.VISIBLE
        }
        readingHistoryScrim.animate()
            .alpha(1f)
            .setDuration(READING_HISTORY_PANEL_ENTER_ANIMATION_MS)
            .start()
    }

    private fun hideReadingHistoryScrim() {
        if (readingHistoryScrim.visibility != View.VISIBLE || isReadingHistoryScrimFadingOut) {
            return
        }
        isReadingHistoryScrimFadingOut = true
        readingHistoryScrim.animate().cancel()
        readingHistoryScrim.animate()
            .alpha(0f)
            .setDuration(READING_HISTORY_PANEL_EXIT_ANIMATION_MS)
            .withEndAction {
                readingHistoryScrim.visibility = View.GONE
                isReadingHistoryScrimFadingOut = false
            }
            .start()
    }

    companion object {
        private const val MIN_READING_HISTORY_PANEL_WIDTH_DP = 180
        private const val READING_HISTORY_PANEL_ELEVATION_DP = 8
        private const val READING_HISTORY_PANEL_ENTER_ANIMATION_MS = 180L
        private const val READING_HISTORY_PANEL_EXIT_ANIMATION_MS = 150L
        private const val READING_HISTORY_DATE_HEADER_VIEW_TYPE = 0
    }
}
