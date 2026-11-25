package com.example.comiclab.ui.privacy

import android.content.Context
import android.os.Environment
import com.example.comiclab.core.nas.NasCachedArchiveStore
import com.example.comiclab.core.nas.NasShareArchiveDownloader
import com.example.comiclab.core.store.PrivacyStore
import java.io.File

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

    private fun canAccessCanonicalTarget(context: Context, target: File): Boolean =
        !PrivacyStore.isCanonicalPathUnderPrivacyDirectory(context, target.path) || PrivacySessionManager.isUnlocked

    private fun isNormalDirectory(context: Context, directory: File): Boolean =
        directory.isDirectory && !PrivacyStore.isCanonicalPathUnderPrivacyDirectory(context, directory.path)

    private fun localRoot(): File? = canonicalFile(Environment.getExternalStorageDirectory())

    private fun canonicalFile(file: File): File? = PrivacyStore.resolveCanonicalPath(file)?.let(::File)

    private fun isUnderRoot(file: File, root: File): Boolean =
        file.toPath().startsWith(root.toPath())
}
