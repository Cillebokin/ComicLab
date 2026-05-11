package com.example.comiclab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FavoriteComicStore {

    data class Item(
        val file: File,
        val addedAt: Long,
        val fileSize: Long,
        val modifiedAt: Long
    )

    enum class RecordResult {
        ADDED,
        ALREADY_EXISTS,
        LIMIT_REACHED,
        INVALID
    }

    fun record(context: Context, file: File): RecordResult {
        if (!file.isFile || !ComicArchive.isSupportedArchive(file)) {
            return RecordResult.INVALID
        }

        val path = file.absolutePath
        val storedItems = validStoredItems(readStoredItems(context))
        if (storedItems.any { it.path == path }) {
            saveStoredItems(context, storedItems.sortedBy { it.addedAt })
            return RecordResult.ALREADY_EXISTS
        }

        if (storedItems.size >= MAX_FAVORITE_COMIC_COUNT) {
            saveStoredItems(context, storedItems.sortedBy { it.addedAt })
            return RecordResult.LIMIT_REACHED
        }

        val updatedItems = (
            storedItems + StoredItem(
                path = path,
                addedAt = System.currentTimeMillis(),
                fileSize = file.length(),
                modifiedAt = file.lastModified()
            )
            )
            .sortedBy { it.addedAt }

        saveStoredItems(context, updatedItems)
        return RecordResult.ADDED
    }

    fun items(context: Context): List<Item> {
        val storedItems = readStoredItems(context)
        val validStoredItems = mutableListOf<StoredItem>()
        val visibleItems = storedItems.mapNotNull { storedItem ->
            val file = File(storedItem.path)
            if (!file.isFile || !ComicArchive.isSupportedArchive(file)) {
                return@mapNotNull null
            }

            validStoredItems.add(storedItem)
            Item(
                file = file,
                addedAt = storedItem.addedAt,
                fileSize = storedItem.fileSize.takeIf { it > 0L } ?: file.length(),
                modifiedAt = storedItem.modifiedAt.takeIf { it > 0L } ?: file.lastModified()
            )
        }

        val sortedStoredItems = validStoredItems
            .sortedBy { it.addedAt }
            .take(MAX_FAVORITE_COMIC_COUNT)
        if (validStoredItems.size != storedItems.size || validStoredItems != sortedStoredItems) {
            if (validStoredItems.isEmpty()) {
                clear(context)
            } else {
                saveStoredItems(context, sortedStoredItems)
            }
        }

        return visibleItems
            .sortedBy { it.addedAt }
            .take(MAX_FAVORITE_COMIC_COUNT)
    }

    fun isFavorite(context: Context, file: File): Boolean {
        return file.isFile &&
            ComicArchive.isSupportedArchive(file) &&
            favoriteFilePaths(context).contains(file.absolutePath)
    }

    fun favoriteFilePaths(context: Context): Set<String> {
        return items(context)
            .map { it.file.absolutePath }
            .toSet()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_FAVORITE_COMICS)
            .apply()
    }

    fun remove(context: Context, file: File) {
        val path = file.absolutePath
        val updatedItems = readStoredItems(context)
            .filterNot { it.path == path }

        if (updatedItems.isEmpty()) {
            clear(context)
        } else {
            saveStoredItems(context, updatedItems)
        }
    }

    private fun readStoredItems(context: Context): List<StoredItem> {
        val rawValue = prefs(context).getString(KEY_FAVORITE_COMICS, null)
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
                            addedAt = obj.optLong(FIELD_ADDED_AT, 0L),
                            fileSize = obj.optLong(FIELD_FILE_SIZE, 0L),
                            modifiedAt = obj.optLong(FIELD_MODIFIED_AT, 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveStoredItems(context: Context, items: List<StoredItem>) {
        val array = JSONArray()
        items.take(MAX_FAVORITE_COMIC_COUNT).forEach { item ->
            array.put(
                JSONObject()
                    .put(FIELD_PATH, item.path)
                    .put(FIELD_ADDED_AT, item.addedAt)
                    .put(FIELD_FILE_SIZE, item.fileSize)
                    .put(FIELD_MODIFIED_AT, item.modifiedAt)
            )
        }

        prefs(context).edit()
            .putString(KEY_FAVORITE_COMICS, array.toString())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun validStoredItems(items: List<StoredItem>): List<StoredItem> {
        return items.filter { item ->
            val file = File(item.path)
            file.isFile && ComicArchive.isSupportedArchive(file)
        }
    }

    private data class StoredItem(
        val path: String,
        val addedAt: Long,
        val fileSize: Long,
        val modifiedAt: Long
    )

    private const val PREFS_NAME = "favorite_comic_prefs"
    private const val KEY_FAVORITE_COMICS = "favorite_comics"
    private const val FIELD_PATH = "path"
    private const val FIELD_ADDED_AT = "addedAt"
    private const val FIELD_FILE_SIZE = "fileSize"
    private const val FIELD_MODIFIED_AT = "modifiedAt"
    private const val MAX_FAVORITE_COMIC_COUNT = 100
}
