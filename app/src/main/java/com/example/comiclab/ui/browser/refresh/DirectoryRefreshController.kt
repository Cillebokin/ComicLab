package com.example.comiclab.ui.browser.refresh

import android.os.Environment
import android.view.ViewTreeObserver
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.core.diagnostics.ComicLabPerformance
import com.example.comiclab.ui.browser.DirectorySortOrder
import com.example.comiclab.ui.browser.FileListScrollState
import com.example.comiclab.R
import com.example.comiclab.ui.browser.DirectoryBrowserController
import com.example.comiclab.ui.browser.FileListAdapter
import com.example.comiclab.ui.browser.dpToPx
import com.example.comiclab.ui.browser.model.FileItem
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns directory loading, refresh, first-draw measurement and list scroll restoration.
 * Moved out of MainActivity without behaviour change.
 */
internal class DirectoryRefreshController(
    private val activity: AppCompatActivity,
    private val listViewOrNull: () -> ListView?,
    private val pathInputProvider: () -> TextView,
    private val fileItems: MutableList<FileItem>,
    private val fileListAdapterProvider: () -> FileListAdapter,
    private val directoryBrowserController: DirectoryBrowserController,
    private val directoryLoadGeneration: AtomicInteger,
    private val sortOrderProvider: () -> DirectorySortOrder,
    private val currentPathProvider: () -> String,
    private val applyResolvedPath: (String) -> Unit,
    private val notifyListChanged: () -> Unit
) {
    private val listView: ListView
        get() = checkNotNull(listViewOrNull())

    private var pendingFirstPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    // 测试接口：返回最近一次恢复目录滚动时请求的 top 值，不参与运行时逻辑。
    private var requestedScrollTopRecord: Int? = null

    internal val requestedScrollTopForTest: Int?
        get() = requestedScrollTopRecord

    internal fun loadCurrentDirectory(
        pathToCenter: String? = null,
        scrollStateToRestore: FileListScrollState? = null
    ) {
        val generation = directoryLoadGeneration.incrementAndGet()
        val hasPermission = Environment.isExternalStorageManager()
        if (hasPermission) {
            removePendingFirstPreDrawListener()
        }
        val loadStartedAtNanos = if (hasPermission) ComicLabPerformance.nowNanos() else null
        directoryBrowserController.load(
            generation = generation,
            requestedPath = currentPathProvider(),
            hasPermission = hasPermission,
            sortOrder = sortOrderProvider,
            scrollState = scrollStateToRestore,
            isActive = { !activity.isFinishing && !activity.isDestroyed },
            postResult = { activity.runOnUiThread(it) }
        ) { result ->
            when (result) {
                DirectoryBrowserController.Result.PermissionRequired -> {
                    pathInputProvider().setText(R.string.storage_permission_required)
                    fileItems.clear()
                    notifyListChanged()
                }
                is DirectoryBrowserController.Result.Loaded -> {
                    if (!result.archiveRecoveryComplete) {
                        Toast.makeText(activity, R.string.archive_recovery_pending, Toast.LENGTH_LONG).show()
                    }
                    val resolvedPath = result.resolvedPath
                    val items = result.items
                    val applyStartedAtNanos = ComicLabPerformance.nowNanos()
                    applyResolvedPath(resolvedPath)
                    fileItems.clear()
                    fileItems.addAll(items)
                    pathInputProvider().setText(resolvedPath)
                    notifyListChanged()
                    if (pathToCenter == null) {
                        if (result.scrollState != null) {
                            restoreFileListScrollState(result.scrollState, resolvedPath)
                        } else {
                            scrollFileListToTop()
                        }
                    } else {
                        centerFileItemIfPresent(pathToCenter)
                    }
                    ComicLabPerformance.record(
                        event = "directory_ui_apply",
                        startedAtNanos = applyStartedAtNanos,
                        fields = "rows=${items.size} pathHash=${resolvedPath.hashCode()}"
                    )
                    watchDirectoryFirstPreDraw(
                        startedAtNanos = loadStartedAtNanos,
                        generation = generation,
                        pathHash = resolvedPath.hashCode(),
                        rowCount = items.size
                    )
                }
            }
        }
    }

    internal fun requestDirectoryChildCount(directory: File) {
        directoryBrowserController.requestChildCount(
            directory = directory,
            isActive = { !activity.isFinishing && !activity.isDestroyed },
            currentPath = { currentPathProvider() },
            postResult = { activity.runOnUiThread(it) }
        ) { countedDirectory, childCount ->
            fileListAdapterProvider().updateDirectoryChildCount(countedDirectory, childCount, listView)
        }
    }

    internal fun removePendingFirstPreDrawListener() {
        val listener = pendingFirstPreDrawListener ?: return
        pendingFirstPreDrawListener = null
        val view = listViewOrNull()
        if (view != null) {
            val observer = view.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnPreDrawListener(listener)
            }
        }
    }

    internal fun captureFileListScrollState(): FileListScrollState? {
        val firstVisiblePosition = listView.firstVisiblePosition
        if (firstVisiblePosition !in fileItems.indices) {
            return null
        }

        val firstVisibleItem = fileItems[firstVisiblePosition]
        return FileListScrollState(
            directoryPath = File(currentPathProvider()).absolutePath,
            firstVisiblePosition = firstVisiblePosition,
            firstVisibleTop = listView.getChildAt(0)?.top ?: 0,
            anchorPath = firstVisibleItem.file?.absolutePath,
            anchorIsParent = firstVisibleItem.isParent
        )
    }

    private fun watchDirectoryFirstPreDraw(
        startedAtNanos: Long?,
        generation: Int,
        pathHash: Int,
        rowCount: Int
    ) {
        if (startedAtNanos == null) {
            return
        }

        removePendingFirstPreDrawListener()
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                removePendingFirstPreDrawListener()
                if (generation == directoryLoadGeneration.get() && !activity.isFinishing && !activity.isDestroyed) {
                    ComicLabPerformance.record(
                        event = "directory_first_pre_draw",
                        startedAtNanos = startedAtNanos,
                        fields = "rows=$rowCount pathHash=$pathHash"
                    )
                }
                return true
            }
        }
        pendingFirstPreDrawListener = listener
        listView.viewTreeObserver.addOnPreDrawListener(listener)
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
            requestedScrollTopRecord = scrollState.firstVisibleTop
        }
    }

    private fun centerListPosition(position: Int) {
        val itemCount = fileItems.size
        val listHeight = listView.height
        if (position !in 0 until itemCount || listHeight <= 0) {
            return
        }

        val itemHeight = (listView.getChildAt(0)?.height ?: activity.dpToPx(FILE_ITEM_HEIGHT_DP))
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

    private companion object {
        private const val FILE_ITEM_HEIGHT_DP = 75
    }
}
