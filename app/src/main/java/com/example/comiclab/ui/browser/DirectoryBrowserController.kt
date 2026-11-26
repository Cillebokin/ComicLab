package com.example.comiclab.ui.browser

import android.content.Context
import com.example.comiclab.core.diagnostics.ComicLabPerformance
import com.example.comiclab.core.archive.ArchiveReplacementRecovery
import com.example.comiclab.core.concurrent.LatestTaskDispatcher
import com.example.comiclab.core.store.BookcaseFolderPaths
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.ui.privacy.PrivacyAccessGuard.PRIVACY_VIRTUAL_ROOT_PATH
import com.example.comiclab.ui.privacy.PrivacySessionManager
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
    private val pendingChildPaths: MutableSet<ChildCountRequest>,
    private val context: Context,
    private val privacyScrollStateCache: DirectoryScrollStateCache = DirectoryScrollStateCache()
) {
    data class ChildCountRequest(val directoryPath: String, val generation: Int, val epoch: Long?)

    private val canonicalRoot by lazy { PrivacyStore.resolveCanonicalPath(storageRootPath) }

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
        if (currentPath == PRIVACY_VIRTUAL_ROOT_PATH) return ParentNavigation(currentPath, null, null)
        val current = File(currentPath)
        val inPrivacySpace = PrivacySessionManager.isInPrivacySpace
        val parent = current.parentFile
        val parentPath = if (inPrivacySpace && (parent == null || !PrivacyStore.isUnderPrivacyDirectory(context, parent))) {
            PRIVACY_VIRTUAL_ROOT_PATH
        } else {
            parent?.absolutePath ?: storageRootPath
        }
        val state = (if (inPrivacySpace) privacyScrollStateCache else scrollStateCache).get(parentPath)
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
        val epoch = privacyEpoch()
        val inPrivacySpace = epoch != null
        val marks = currentMarks()
        loadDispatcher.executeLatest {
            fun isCurrent() = generation == loadGeneration.get() && isActive() && matchesSession(epoch)
            fun reload() = load(generation, requestedPath, hasPermission, sortOrder, scrollState, isActive, postResult, onResult)
            if (!isCurrent()) return@executeLatest
            if (PrivacyStore.privacyDirectories(context) != marks) {
                postResult { if (isCurrent()) reload() }
                return@executeLatest
            }
            val directory = resolveDirectory(requestedPath, inPrivacySpace, marks)
            val resolvedPath = directory?.absolutePath ?: if (inPrivacySpace) PRIVACY_VIRTUAL_ROOT_PATH else storageRootPath
            val archiveRecoveryComplete = directory == null || ArchiveReplacementRecovery.recoverDirectory(directory)
            val listStartedAtNanos = ComicLabPerformance.nowNanos()
            val listedFiles = ComicLabPerformance.trace("comiclab.directory.list_files") {
                try {
                    if (resolvedPath == PRIVACY_VIRTUAL_ROOT_PATH) {
                        PrivacyStore.visibleRootDirectories(marks).filter { canonicalLocalPath(it) != null }
                    } else {
                        directory?.listFiles()?.filter { isVisible(it, inPrivacySpace, marks) }.orEmpty()
                    }
                } catch (_: SecurityException) {
                    emptyList()
                }
            }
            ComicLabPerformance.record(
                event = "directory_list_files",
                startedAtNanos = listStartedAtNanos,
                fields = "children=${listedFiles.size} pathHash=${resolvedPath.hashCode()}"
            )
            if (!isCurrent()) return@executeLatest
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
            if (!isCurrent()) return@executeLatest
            val itemBuildStartedAtNanos = ComicLabPerformance.nowNanos()
            val items = ComicLabPerformance.trace("comiclab.directory.build_items") {
                mutableListOf<FileItem>().apply {
                    if (directory != null && resolvedPath != storageRootPath) {
                        add(FileItem(isParent = true))
                    }
                    files.forEach { file ->
                        add(FileItem(file = file, childCount = if (file.isDirectory) {
                            childCountCache.get(file.absolutePath, file.lastModified(), inPrivacySpace)
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
                if (!isCurrent()) return@postResult
                val latestMarks = currentMarks()
                if (latestMarks != marks || resolveDirectory(requestedPath, inPrivacySpace, latestMarks) != directory) {
                    reload()
                    return@postResult
                }
                // 后台已按本次标记过滤。虚拟根没有真实父目录的时间戳可核对，另检查标记目录是否仍存在。
                val visible = if (resolvedPath == PRIVACY_VIRTUAL_ROOT_PATH) {
                    items.filter { it.file?.isDirectory == true }
                } else items
                onResult(Result.Loaded(resolvedPath, visible, scrollState, archiveRecoveryComplete))
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
        val request = ChildCountRequest(directoryPath, loadGeneration.get(), privacyEpoch())
        val inPrivacySpace = request.epoch != null
        val parentPath = currentPath()
        val marks = currentMarks()
        if (!canCount(directory, parentPath, inPrivacySpace, marks) || !pendingChildPaths.add(request)) return
        val cacheGeneration = childCountCache.generation()
        try {
            childCountExecutor.execute {
                if (!matchesSession(request.epoch) || loadGeneration.get() != request.generation || !isActive()) {
                    pendingChildPaths.remove(request)
                    return@execute
                }
                val lastModifiedBefore = directory.lastModified()
                val lookup = childCountCache.lookup(directoryPath, lastModifiedBefore, inPrivacySpace)
                val childCount = lookup.childCount ?: try {
                    directory.listFiles()?.count { isVisible(it, inPrivacySpace, marks) }
                } catch (_: SecurityException) {
                    null
                }
                val lastModifiedAfter = directory.lastModified()
                if (lookup.childCount == null && childCount != null &&
                    lastModifiedBefore == lastModifiedAfter && lookup.generation == cacheGeneration
                ) {
                    childCountCache.putIfUnchangedSince(
                        directoryPath, lastModifiedAfter, childCount, lookup.generation, inPrivacySpace
                    )
                }
                postResult {
                    pendingChildPaths.remove(request)
                    if (!isActive() || loadGeneration.get() != request.generation ||
                        !matchesSession(request.epoch) || currentPath() != parentPath
                    ) {
                        return@postResult
                    }
                    val latestMarks = currentMarks()
                    if (!canCount(directory, parentPath, inPrivacySpace, latestMarks)) return@postResult
                    if (latestMarks != marks || (childCount != null &&
                        (lastModifiedBefore != lastModifiedAfter ||
                            directory.lastModified() != lastModifiedAfter ||
                            childCountCache.generation() != lookup.generation))
                    ) {
                        requestChildCount(directory, isActive, currentPath, postResult, onCount)
                        return@postResult
                    }
                    onCount(directory, childCount ?: 0)
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingChildPaths.remove(request)
        }
    }

    private fun privacyEpoch(): Long? = PrivacySessionManager.sessionEpoch.takeIf { PrivacySessionManager.isInPrivacySpace }

    private fun matchesSession(epoch: Long?): Boolean = if (epoch == null) {
        !PrivacySessionManager.isInPrivacySpace
    } else {
        PrivacySessionManager.isCurrentSession(epoch)
    }

    private fun currentMarks(): Set<String> = PrivacyStore.privacyDirectories(context).also(childCountCache::updatePrivacyMarks)

    private fun resolveDirectory(path: String, inPrivacySpace: Boolean, marks: Set<String>): File? {
        val requested = canonicalLocalPath(File(path))?.takeIf { it.isDirectory && isPrivate(it.path, marks) == inPrivacySpace }
        if (requested != null && requested.path != canonicalRoot) return requested
        return if (inPrivacySpace) null else File(storageRootPath).takeIf {
            val root = canonicalRoot
            root != null && !isPrivate(root, marks) && it.isDirectory
        }
    }

    private fun canonicalLocalPath(file: File): File? {
        val root = canonicalRoot ?: return null
        val path = PrivacyStore.resolveCanonicalPath(file) ?: return null
        return File(path).takeIf { BookcaseFolderPaths.isPathInDirectory(path, root) }
    }

    private fun isVisible(file: File, inPrivacySpace: Boolean, marks: Set<String>): Boolean {
        if (file.name.startsWith('.')) return false
        val target = canonicalLocalPath(file) ?: return false
        return isPrivate(target.path, marks) == inPrivacySpace
    }

    private fun isPrivate(path: String, marks: Set<String>): Boolean =
        marks.any { BookcaseFolderPaths.isPathInDirectory(path, it) }

    private fun canCount(directory: File, parentPath: String, inPrivacySpace: Boolean, marks: Set<String>): Boolean {
        if (parentPath == PRIVACY_VIRTUAL_ROOT_PATH) {
            val target = canonicalLocalPath(directory) ?: return false
            return inPrivacySpace && target.isDirectory && PrivacyStore.isOutermostDirectory(target.path, marks)
        }
        return directory.parentFile?.absolutePath == parentPath && isVisible(directory, inPrivacySpace, marks)
    }
}
