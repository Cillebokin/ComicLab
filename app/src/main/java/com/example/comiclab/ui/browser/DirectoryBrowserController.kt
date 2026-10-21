package com.example.comiclab.ui.browser

import com.example.comiclab.core.diagnostics.ComicLabPerformance
import com.example.comiclab.core.archive.ArchiveReplacementRecovery
import com.example.comiclab.core.concurrent.LatestTaskDispatcher
import com.example.comiclab.ui.browser.model.FileItem
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/** Coordinates directory work using resources owned and shut down by the Activity. */
internal class DirectoryBrowserController(
    private val storageRootPath: String,
    private val loadDispatcher: LatestTaskDispatcher,
    private val childCountExecutor: Executor,
    private val loadGeneration: AtomicInteger,
    private val childCountCache: DirectoryChildCountCache,
    private val scrollStateCache: DirectoryScrollStateCache,
    private val pendingChildPaths: MutableSet<String>
) {
    sealed interface Result {
        data class Loaded(
            val resolvedPath: String,
            val items: List<FileItem>,
            val scrollState: FileListScrollState?,
            val archiveRecoveryComplete: Boolean = true
        ) : Result
        data object PermissionRequired : Result
    }

    data class ParentNavigation(
        val path: String,
        val pathToCenter: String?,
        val scrollState: FileListScrollState?
    )

    fun parentNavigation(currentPath: String): ParentNavigation {
        val current = File(currentPath)
        val parentPath = current.parentFile?.absolutePath ?: storageRootPath
        val state = scrollStateCache.get(parentPath)
        return ParentNavigation(parentPath, current.absolutePath.takeIf { state == null }, state)
    }

    fun load(
        generation: Int,
        requestedPath: String,
        hasPermission: Boolean,
        sortOrder: () -> DirectorySortOrder,
        scrollState: FileListScrollState?,
        isActive: () -> Boolean,
        postResult: (() -> Unit) -> Unit,
        onResult: (Result) -> Unit
    ) {
        if (!hasPermission) {
            onResult(Result.PermissionRequired)
            return
        }
        loadDispatcher.executeLatest {
            if (generation != loadGeneration.get() || !isActive()) return@executeLatest
            val directory = File(requestedPath).takeIf { it.isDirectory } ?: File(storageRootPath)
            val resolvedPath = directory.absolutePath
            val archiveRecoveryComplete = ArchiveReplacementRecovery.recoverDirectory(directory)
            val listStartedAtNanos = ComicLabPerformance.nowNanos()
            val listedFiles = ComicLabPerformance.trace("comiclab.directory.list_files") {
                try {
                    directory.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
                } catch (_: SecurityException) {
                    emptyList()
                }
            }
            ComicLabPerformance.record(
                event = "directory_list_files",
                startedAtNanos = listStartedAtNanos,
                fields = "children=${listedFiles.size} pathHash=${resolvedPath.hashCode()}"
            )
            if (generation != loadGeneration.get() || !isActive()) return@executeLatest
            val sortStartedAtNanos = ComicLabPerformance.nowNanos()
            val files = ComicLabPerformance.trace("comiclab.directory.sort") {
                try {
                    DirectorySortPolicy.sort(listedFiles, sortOrder())
                } catch (_: SecurityException) {
                    emptyList()
                }
            }
            ComicLabPerformance.record(
                event = "directory_sort",
                startedAtNanos = sortStartedAtNanos,
                fields = "children=${files.size} pathHash=${resolvedPath.hashCode()}"
            )
            if (generation != loadGeneration.get() || !isActive()) return@executeLatest
            val itemBuildStartedAtNanos = ComicLabPerformance.nowNanos()
            val items = ComicLabPerformance.trace("comiclab.directory.build_items") {
                mutableListOf<FileItem>().apply {
                    if (directory.absolutePath != File(storageRootPath).absolutePath) {
                        add(FileItem(isParent = true))
                    }
                    files.forEach { file ->
                        add(FileItem(file = file, childCount = if (file.isDirectory) {
                            childCountCache.get(file.absolutePath, file.lastModified())
                        } else null))
                    }
                }
            }
            ComicLabPerformance.record(
                event = "directory_build_items",
                startedAtNanos = itemBuildStartedAtNanos,
                fields = "rows=${items.size} pathHash=${resolvedPath.hashCode()}"
            )
            postResult {
                if (generation == loadGeneration.get()) {
                    onResult(Result.Loaded(resolvedPath, items, scrollState, archiveRecoveryComplete))
                }
            }
        }
    }

    fun requestChildCount(
        directory: File,
        isActive: () -> Boolean,
        currentPath: () -> String,
        postResult: (() -> Unit) -> Unit,
        onCount: (File, Int) -> Unit
    ) {
        if (!isActive()) return
        val directoryPath = directory.absolutePath
        if (!pendingChildPaths.add(directoryPath)) return
        try {
            childCountExecutor.execute {
                val lastModifiedBefore = directory.lastModified()
                val lookup = childCountCache.lookup(directoryPath, lastModifiedBefore)
                val childCount = lookup.childCount ?: try {
                    directory.listFiles()?.size
                } catch (_: SecurityException) {
                    null
                }
                val lastModifiedAfter = directory.lastModified()
                if (lookup.childCount == null && childCount != null &&
                    lastModifiedBefore == lastModifiedAfter
                ) {
                    childCountCache.putIfUnchangedSince(
                        directoryPath, lastModifiedAfter, childCount, lookup.generation
                    )
                }
                postResult {
                    pendingChildPaths.remove(directoryPath)
                    if (!isActive() || directory.parentFile?.absolutePath != currentPath()) {
                        return@postResult
                    }
                    if (childCount != null &&
                        (lastModifiedBefore != lastModifiedAfter ||
                            directory.lastModified() != lastModifiedAfter ||
                            childCountCache.generation() != lookup.generation)
                    ) {
                        requestChildCount(directory, isActive, currentPath, postResult, onCount)
                        return@postResult
                    }
                    onCount(directory, childCount ?: 0)
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingChildPaths.remove(directoryPath)
        }
    }
}
