package com.example.comiclab

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
        INVALID
    }

    fun record(context: Context, directory: File): RecordResult {
        if (!directory.isDirectory) {
            return RecordResult.INVALID
        }

        val path = directory.absolutePath
        val storedItems = validStoredItems(readStoredItems(context))
        if (storedItems.any { it.path == path }) {
            saveStoredItems(context, storedItems.sortedBy { it.addedAt })
            return RecordResult.ALREADY_EXISTS
        }

        if (storedItems.size >= MAX_FAVORITE_PATH_COUNT) {
            saveStoredItems(context, storedItems.sortedBy { it.addedAt })
            return RecordResult.LIMIT_REACHED
        }

        val updatedItems = (storedItems + StoredItem(path, System.currentTimeMillis()))
            .sortedBy { it.addedAt }

        saveStoredItems(context, updatedItems)
        return RecordResult.ADDED
    }

    fun items(context: Context): List<Item> {
        val storedItems = readStoredItems(context)
        val validStoredItems = mutableListOf<StoredItem>()
        val visibleItems = storedItems.mapNotNull { storedItem ->
            val directory = File(storedItem.path)
            if (!directory.isDirectory) {
                return@mapNotNull null
            }

            validStoredItems.add(storedItem)
            Item(
                directory = directory,
                addedAt = storedItem.addedAt
            )
        }

        val sortedStoredItems = validStoredItems
            .sortedBy { it.addedAt }
            .take(MAX_FAVORITE_PATH_COUNT)
        if (validStoredItems.size != storedItems.size || validStoredItems != sortedStoredItems) {
            if (validStoredItems.isEmpty()) {
                clear(context)
            } else {
                saveStoredItems(context, sortedStoredItems)
            }
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
        val updatedItems = readStoredItems(context)
            .filterNot { it.path == path }

        if (updatedItems.isEmpty()) {
            clear(context)
        } else {
            saveStoredItems(context, updatedItems)
        }
    }

    private fun readStoredItems(context: Context): List<StoredItem> {
        val rawValue = prefs(context).getString(KEY_FAVORITE_PATHS, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(rawValue)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val path = obj.optString(FIELD_PATH).takeIf { it.isNotBlank() } ?: continue
                    add(
                        StoredItem(
                            path = path,
                            addedAt = obj.optLong(FIELD_ADDED_AT, 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun validStoredItems(items: List<StoredItem>): List<StoredItem> {
        return items.filter { item ->
            File(item.path).isDirectory
        }
    }

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
