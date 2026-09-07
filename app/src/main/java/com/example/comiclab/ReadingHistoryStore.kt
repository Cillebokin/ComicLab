package com.example.comiclab

import android.content.Context
import com.example.comiclab.ebook.ReaderFileDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ReadingHistoryStore {

    data class Item(
        val file: File,
        val lastReadAt: Long,
        val fileSize: Long,
        val modifiedAt: Long
    )

    fun record(context: Context, file: File) {
        if (!ReaderFileDetector.isSupported(file)) {
            return
        }

        val path = file.absolutePath
        val updatedItems = mutableListOf(
            StoredItem(
                path = path,
                lastReadAt = System.currentTimeMillis(),
                fileSize = file.length(),
                modifiedAt = file.lastModified()
            )
        )
        readStoredItems(context)
            .asSequence()
            .filterNot { it.path == path }
            .take(MAX_HISTORY_COUNT - 1)
            .forEach { updatedItems.add(it) }

        saveStoredItems(context, updatedItems)
    }

    fun items(context: Context): List<Item> {
        val storedItems = readStoredItems(context)
        val validStoredItems = mutableListOf<StoredItem>()
        val visibleItems = storedItems.mapNotNull { storedItem ->
            val file = File(storedItem.path)
            if (!file.isFile || !ReaderFileDetector.isSupported(file)) {
                return@mapNotNull null
            }

            validStoredItems.add(storedItem)
            Item(
                file = file,
                lastReadAt = storedItem.lastReadAt,
                fileSize = storedItem.fileSize.takeIf { it > 0L } ?: file.length(),
                modifiedAt = storedItem.modifiedAt.takeIf { it > 0L } ?: file.lastModified()
            )
        }

        if (validStoredItems.size != storedItems.size) {
            if (validStoredItems.isEmpty()) {
                clear(context)
            } else {
                saveStoredItems(context, validStoredItems)
            }
        }

        return visibleItems
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_READING_HISTORY)
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
        val rawValue = prefs(context).getString(KEY_READING_HISTORY, null)
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
                            lastReadAt = obj.optLong(FIELD_LAST_READ_AT, 0L),
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
        items.take(MAX_HISTORY_COUNT).forEach { item ->
            array.put(
                JSONObject()
                    .put(FIELD_PATH, item.path)
                    .put(FIELD_LAST_READ_AT, item.lastReadAt)
                    .put(FIELD_FILE_SIZE, item.fileSize)
                    .put(FIELD_MODIFIED_AT, item.modifiedAt)
            )
        }

        prefs(context).edit()
            .putString(KEY_READING_HISTORY, array.toString())
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private data class StoredItem(
        val path: String,
        val lastReadAt: Long,
        val fileSize: Long,
        val modifiedAt: Long
    )

    private const val PREFS_NAME = "reading_history_prefs"
    private const val KEY_READING_HISTORY = "reading_history"
    private const val FIELD_PATH = "path"
    private const val FIELD_LAST_READ_AT = "lastReadAt"
    private const val FIELD_FILE_SIZE = "fileSize"
    private const val FIELD_MODIFIED_AT = "modifiedAt"
    private const val MAX_HISTORY_COUNT = 30
}
