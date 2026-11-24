package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs

internal object PrivacyStore {
    private const val PREFS_NAME = "privacy_prefs"
    private const val KEY_PRIVACY_DIRECTORIES = "privacy_directories"

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ComicLabPrivacyStore").apply { isDaemon = true }
    }

    fun resolveCanonicalPath(path: String): String? = resolveCanonicalPath(File(path))

    fun resolveCanonicalPath(file: File): String? {
        if (!file.isAbsolute || '\u0000' in file.path) return null
        return try {
            file.canonicalPath
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun addPrivacyDirectory(context: Context, directory: File): Boolean {
        val path = resolveCanonicalPath(directory) ?: return false
        val canonicalDirectory = File(path)
        if (!canonicalDirectory.isDirectory || canonicalDirectory.parentFile == null) return false
        return updatePaths(context) { it + path } != null
    }

    fun removePrivacyDirectory(context: Context, directory: File): Boolean {
        val path = resolveCanonicalPath(directory) ?: return false
        return updatePaths(context) { it - path } != null
    }

    fun removeDirectorySubtree(context: Context, directory: File): Int {
        val path = resolveCanonicalPath(directory) ?: return 0
        return updatePaths(context) { BookcaseFolderPaths.underDirectory(it, path) } ?: 0
    }

    fun privacyDirectories(context: Context): Set<String> = synchronized(lock) {
        readPaths(PrefsGateway.open(context, PREFS_NAME)).toSet()
    }

    fun visibleRootDirectories(context: Context): List<File> {
        val paths = privacyDirectories(context)
        return paths.asSequence()
            .filter { path -> paths.none { it != path && BookcaseFolderPaths.isPathInDirectory(path, it) } }
            .sorted()
            .map(::File)
            .filter(File::isDirectory)
            .toList()
    }

    fun isPrivacyDirectoryMarked(context: Context, path: String): Boolean {
        val canonicalPath = resolveCanonicalPath(path) ?: return false
        return canonicalPath in privacyDirectories(context)
    }

    fun isUnderPrivacyDirectory(context: Context, path: String): Boolean =
        isUnderPrivacyDirectory(context, File(path))

    fun isUnderPrivacyDirectory(context: Context, file: File): Boolean {
        val path = resolveCanonicalPath(file) ?: return true
        return privacyDirectories(context).any { BookcaseFolderPaths.isPathInDirectory(path, it) }
    }

    // 两个子树查询用于拦截文件操作，路径无法确认时也需要拦截。
    fun containsPrivacyDescendant(context: Context, directoryPath: String): Boolean {
        val path = resolveCanonicalPath(directoryPath) ?: return true
        return privacyDirectories(context).any { it != path && BookcaseFolderPaths.isPathInDirectory(it, path) }
    }

    fun hasOwnOrDescendantPrivacyMark(context: Context, directoryPath: String): Boolean {
        val path = resolveCanonicalPath(directoryPath) ?: return true
        return privacyDirectories(context).any { BookcaseFolderPaths.isPathInDirectory(it, path) }
    }

    private fun updatePaths(context: Context, change: (Set<String>) -> Set<String>): Int? {
        val write = writer.submit<Int?> {
            synchronized(lock) {
                val preferences = PrefsGateway.open(context, PREFS_NAME)
                val previous = readPaths(preferences)
                val next = change(previous)
                if (previous == next) return@synchronized 0

                if (!preferences.edit().putStringSet(KEY_PRIVACY_DIRECTORIES, next).commit()) {
                    // commit() 可能已更新内存，返回前同步尝试恢复旧标记。
                    preferences.edit().putStringSet(KEY_PRIVACY_DIRECTORIES, previous).commit()
                    return@synchronized null
                }
                abs(next.size - previous.size)
            }
        }

        // 调用方取消等待时仍需拿到落盘结果，避免把一次成功写入误报为失败。
        var interrupted = false
        try {
            while (true) {
                try {
                    return write.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun readPaths(preferences: SharedPreferences): Set<String> =
        preferences.getStringSet(KEY_PRIVACY_DIRECTORIES, null).orEmpty()
}
