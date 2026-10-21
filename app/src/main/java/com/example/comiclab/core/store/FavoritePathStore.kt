package com.example.comiclab.core.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FavoritePathStore {

    data class Item(
        val directory: File,
        val addedAt: Long
    )

    enum class RecordResult {
        ADDED,
        ALREADY_EXISTS,
        LIMIT_REACHED,
        INVALID,
        STORAGE_ERROR
    }

    fun record(context: Context, directory: File): RecordResult {
        if (!directory.isDirectory) {
            return RecordResult.INVALID
        }

        val path = directory.absolutePath
        val storedItems = readStoredItems(context) ?: return RecordResult.STORAGE_ERROR
        if (storedItems.any { it.path == path }) {
            return RecordResult.ALREADY_EXISTS
        }

        if (storedItems.size >= MAX_FAVORITE_PATH_COUNT) {
            return RecordResult.LIMIT_REACHED
        }

        val updatedItems = (storedItems + StoredItem(path, System.currentTimeMillis()))
            .sortedBy { it.addedAt }

        saveStoredItems(context, updatedItems)
        return RecordResult.ADDED
    }

    /** 返回本次导入中最终保留的唯一路径数，不计重复项与容量淘汰项。 */
    fun merge(context: Context, items: Collection<Item>): Int {
        val storedItems = checkNotNull(readStoredItems(context)) { "无法读取目录收藏，已保留原数据" }
        val mergedItems = LinkedHashMap<String, StoredItem>()
        storedItems.forEach { item ->
            mergedItems[item.path] = item
        }

        val importedPaths = mutableSetOf<String>()
        items.forEach { item ->
            val directory = item.directory
            if (!directory.isDirectory) {
                return@forEach
            }

            val path = directory.absolutePath
            mergedItems[path] = StoredItem(
                path = path,
                addedAt = item.addedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            importedPaths.add(path)
        }

        val retainedItems = mergedItems.values.sortedBy { it.addedAt }.take(MAX_FAVORITE_PATH_COUNT)
        if (retainedItems.isEmpty()) {
            clear(context)
        } else {
            saveStoredItems(context, retainedItems)
        }
        return retainedItems.count { it.path in importedPaths }
    }

    internal fun validateReadable(context: Context) {
        checkNotNull(readStoredItems(context)) { "无法读取目录收藏，已保留原数据" }
    }

    fun items(context: Context): List<Item> {
        val storedItems = readStoredItems(context) ?: return emptyList()
        val visibleItems = storedItems.mapNotNull { storedItem ->
            val directory = File(storedItem.path)
            if (!directory.isDirectory) {
                return@mapNotNull null
            }

            Item(
                directory = directory,
                addedAt = storedItem.addedAt
            )
        }

        return visibleItems
            .sortedBy { it.addedAt }
            .take(MAX_FAVORITE_PATH_COUNT)
    }

    fun isFavorite(context: Context, directory: File): Boolean {
        return directory.isDirectory &&
            favoriteDirectoryPaths(context).contains(directory.absolutePath)
    }

    fun favoriteDirectoryPaths(context: Context): Set<String> {
        return items(context)
            .map { it.directory.absolutePath }
            .toSet()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_FAVORITE_PATHS)
            .apply()
    }

    fun remove(context: Context, directory: File) {
        val path = directory.absolutePath
        val updatedItems = (readStoredItems(context) ?: return)
            .filterNot { it.path == path }

        if (updatedItems.isEmpty()) {
            clear(context)
        } else {
            saveStoredItems(context, updatedItems)
        }
    }

    fun moveDirectory(context: Context, oldDirectory: File, newDirectory: File) {
        val storedItems = readStoredItems(context) ?: return
        val oldPath = oldDirectory.absolutePath
        val newPath = newDirectory.absolutePath
        val movedItems = LinkedHashMap<String, StoredItem>()
        storedItems.forEach { item ->
            val movedPath = BookcaseFolderPaths.remapPath(item.path, oldPath, newPath)
            val movedItem = item.copy(path = movedPath)
            val current = movedItems[movedPath]
            if (current == null || movedItem.addedAt < current.addedAt) {
                movedItems[movedPath] = movedItem
            }
        }

        if (movedItems.isEmpty()) {
            clear(context)
        } else {
            saveStoredItems(context, movedItems.values.sortedBy { it.addedAt })
        }
    }

    private fun readStoredItems(context: Context): List<StoredItem>? =
        readStoredCollection(context, PREFS_NAME, KEY_FAVORITE_PATHS) { obj ->
            StoredItem(
                path = obj.storedPath(),
                addedAt = obj.optLong(FIELD_ADDED_AT, 0L)
            )
        }

    private fun saveStoredItems(context: Context, items: List<StoredItem>) {
        val array = JSONArray()
        items.take(MAX_FAVORITE_PATH_COUNT).forEach { item ->
            array.put(
                JSONObject()
                    .put(FIELD_PATH, item.path)
                    .put(FIELD_ADDED_AT, item.addedAt)
            )
        }

        prefs(context).edit()
            .putString(KEY_FAVORITE_PATHS, array.toString())
            .apply()
    }

    private fun prefs(context: Context) =
        PrefsGateway.open(context, PREFS_NAME)

    private data class StoredItem(
        val path: String,
        val addedAt: Long
    )

    private const val PREFS_NAME = "favorite_path_prefs"
    private const val KEY_FAVORITE_PATHS = "favorite_paths"
    private const val FIELD_PATH = "path"
    private const val FIELD_ADDED_AT = "addedAt"
    private const val MAX_FAVORITE_PATH_COUNT = 20
}
