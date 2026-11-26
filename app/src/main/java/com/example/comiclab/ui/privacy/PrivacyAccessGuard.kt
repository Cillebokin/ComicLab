package com.example.comiclab.ui.privacy

import android.content.Context
import android.os.Environment
import com.example.comiclab.R
import com.example.comiclab.core.store.BookcaseFolderPaths
import com.example.comiclab.core.nas.NasCachedArchiveStore
import com.example.comiclab.core.nas.NasShareArchiveDownloader
import com.example.comiclab.core.store.PrivacyStore
import java.io.File
import java.nio.file.Files

internal object PrivacyAccessGuard {
    const val PRIVACY_VIRTUAL_ROOT_PATH = "__comiclab_privacy_root__"

    fun canAccessPath(context: Context, path: String): Boolean {
        if (path == PRIVACY_VIRTUAL_ROOT_PATH) return PrivacySessionManager.isUnlocked
        val target = resolveLocalPath(File(path)) ?: return false
        return canAccessCanonicalTarget(context, target)
    }

    fun canAccessFile(context: Context, file: File): Boolean {
        val target = canonicalFile(file) ?: return false
        val root = localRoot()
        if (root != null && isUnderRoot(target, root)) return canAccessCanonicalTarget(context, target)

        val cacheDirectory = NasShareArchiveDownloader.cacheDirectory(context.cacheDir)
        val cacheRoot = canonicalFile(cacheDirectory) ?: return false
        if (!isUnderRoot(target, cacheRoot)) return false
        val relativePath = cacheRoot.toPath().relativize(target.toPath()).toString()
        return NasCachedArchiveStore.isCacheFile(context, File(cacheDirectory, relativePath)) &&
            canAccessCanonicalTarget(context, target)
    }

    fun canMarkDirectory(directory: File): Boolean =
        resolveLocalPath(directory, allowRoot = false)?.isDirectory == true

    fun resolveNormalRestoreDirectory(context: Context, path: String?): File? {
        val root = localRoot() ?: return null
        val target = path?.let { canonicalFile(File(it)) }
        if (target != null && isUnderRoot(target, root) && isNormalDirectory(context, target)) {
            return target
        }
        return root.takeIf { isNormalDirectory(context, it) }
    }

    fun resolveLocalPath(file: File, allowRoot: Boolean = true): File? {
        val root = localRoot() ?: return null
        val target = canonicalFile(file) ?: return null
        return target.takeIf { isUnderRoot(it, root) && (allowRoot || it != root) }
    }

    fun resolveLocalPathInMode(context: Context, file: File, inPrivacySpace: Boolean): File? =
        resolveLocalPath(file)?.takeIf {
            PrivacyStore.isCanonicalPathUnderPrivacyDirectory(context, it.path) == inPrivacySpace
        }

    fun directoryChangeRestriction(context: Context, directory: File, inPrivacySpace: Boolean, deleting: Boolean = false): Int? {
        val target = resolveLocalPath(directory, allowRoot = false) ?: return R.string.privacy_operation_denied
        if (Files.isSymbolicLink(directory.toPath())) return R.string.privacy_operation_denied
        val marks = PrivacyStore.privacyDirectories(context)
        if (marks.any { BookcaseFolderPaths.isPathInDirectory(target.path, it) } != inPrivacySpace) {
            return R.string.privacy_operation_denied
        }
        val containsMark = marks.any { BookcaseFolderPaths.isPathInDirectory(it, target.path) }
        return when {
            !inPrivacySpace && containsMark -> R.string.privacy_parent_operation_denied
            inPrivacySpace && !deleting && PrivacyStore.isOutermostDirectory(target.path, marks) -> R.string.privacy_top_level_operation_denied
            inPrivacySpace && !deleting && containsMark -> R.string.privacy_independent_mark_operation_denied
            else -> null
        }
    }

    fun allowsLocalChild(context: Context, directory: File, child: File, inPrivacySpace: Boolean): Boolean {
        val parent = resolveLocalPathInMode(context, directory, inPrivacySpace) ?: return false
        return resolveLocalPathInMode(context, child, inPrivacySpace)?.parentFile == parent
    }

    private fun canAccessCanonicalTarget(context: Context, target: File): Boolean =
        !PrivacyStore.isCanonicalPathUnderPrivacyDirectory(context, target.path) || PrivacySessionManager.isUnlocked

    private fun isNormalDirectory(context: Context, directory: File): Boolean =
        directory.isDirectory && !PrivacyStore.isCanonicalPathUnderPrivacyDirectory(context, directory.path)

    private fun localRoot(): File? = canonicalFile(Environment.getExternalStorageDirectory())

    private fun canonicalFile(file: File): File? = PrivacyStore.resolveCanonicalPath(file)?.let(::File)

    private fun isUnderRoot(file: File, root: File): Boolean =
        file.toPath().startsWith(root.toPath())
}
