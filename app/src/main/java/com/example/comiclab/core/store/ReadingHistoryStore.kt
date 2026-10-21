package com.example.comiclab.core.store

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

        val storedItems = readStoredItems(context) ?: return

        val path = file.absolutePath
        val updatedItems = mutableListOf(
            StoredItem(
                path = path,
                lastReadAt = System.currentTimeMillis(),
                fileSize = file.length(),
                modifiedAt = file.lastModified()
            )
        )
        storedItems
            .asSequence()
            .filterNot { it.path == path }
            .take(MAX_HISTORY_COUNT - 1)
            .forEach { updatedItems.add(it) }

        saveStoredItems(context, updatedItems)
    }

    /** 仅统计被采用且留在最终窗口中的导入记录，同路径计一次。 */
    fun merge(context: Context, items: Collection<Item>): Int {
        val storedItems = checkNotNull(readStoredItems(context)) { "无法读取阅读历史，已保留原数据" }
        val mergedItems = LinkedHashMap<String, StoredItem>()
        storedItems.forEach { item ->
            mergedItems[item.path] = item
        }

        val importedPaths = mutableSetOf<String>()
        items.forEach { item ->
            val file = item.file
            if (!file.isFile || !ReaderFileDetector.isSupported(file)) {
                return@forEach
            }

            val path = file.absolutePath
            val importedItem = StoredItem(
                path = path,
                lastReadAt = item.lastReadAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                fileSize = item.fileSize.takeIf { it > 0L } ?: file.length(),
                modifiedAt = item.modifiedAt.takeIf { it > 0L } ?: file.lastModified()
            )
            val currentItem = mergedItems[path]
            if (currentItem == null || importedItem.lastReadAt >= currentItem.lastReadAt) {
                mergedItems[path] = importedItem
                importedPaths.add(path)
            }
        }

        val retainedItems = mergedItems.values.sortedByDescending { it.lastReadAt }.take(MAX_HISTORY_COUNT)
        if (retainedItems.isEmpty()) {
            clear(context)
        } else {
            saveStoredItems(
                context,
                retainedItems
            )
        }
        return retainedItems.count { it.path in importedPaths }
    }

    internal fun validateReadable(context: Context) {
        checkNotNull(readStoredItems(context)) { "无法读取阅读历史，已保留原数据" }
    }

    fun items(context: Context): List<Item> {
        val storedItems = readStoredItems(context) ?: return emptyList()
        val visibleItems = storedItems.mapNotNull { storedItem ->
            val file = File(storedItem.path)
            if (!file.isFile || !ReaderFileDetector.isSupported(file)) {
                return@mapNotNull null
            }

            Item(
                file = file,
                lastReadAt = storedItem.lastReadAt,
                fileSize = storedItem.fileSize.takeIf { it > 0L } ?: file.length(),
                modifiedAt = storedItem.modifiedAt.takeIf { it > 0L } ?: file.lastModified()
            )
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
        val updatedItems = (readStoredItems(context) ?: return)
            .filterNot { it.path == path }

        if (updatedItems.isEmpty()) {
            clear(context)
        } else {
            saveStoredItems(context, updatedItems)
        }
    }

    /**
     * 重命名单个文件后迁移历史记录。路径完全匹配时 remapPath 返回新路径，
     * 冲突时保留阅读时间较新的记录（决策 D3）。
     */
    fun moveFile(context: Context, oldFile: File, newFile: File) {
        moveDirectory(context, oldFile, newFile)
    }

    fun moveDirectory(context: Context, oldDirectory: File, newDirectory: File) {
        val storedItems = readStoredItems(context) ?: return
        val movedItems = LinkedHashMap<String, StoredItem>()
        storedItems.forEach { item ->
            val movedPath = BookcaseFolderPaths.remapPath(
                item.path,
                oldDirectory.absolutePath,
                newDirectory.absolutePath
            )
            val targetFile = if (movedPath != item.path) File(movedPath) else null
            val movedItem = item.copy(
                path = movedPath,
                fileSize = targetFile?.takeIf { it.isFile }?.length() ?: item.fileSize,
                modifiedAt = targetFile?.takeIf { it.isFile }?.lastModified() ?: item.modifiedAt
            )
            val current = movedItems[movedPath]
            if (current == null || movedItem.lastReadAt > current.lastReadAt) {
                movedItems[movedPath] = movedItem
            }
        }

        if (movedItems.isEmpty()) {
            clear(context)
        } else {
            saveStoredItems(
                context,
                movedItems.values.sortedByDescending { it.lastReadAt }
            )
        }
    }

    private fun readStoredItems(context: Context): List<StoredItem>? =
        readStoredCollection(context, PREFS_NAME, KEY_READING_HISTORY) { obj ->
            StoredItem(
                path = obj.storedPath(),
                lastReadAt = obj.optLong(FIELD_LAST_READ_AT, 0L),
                fileSize = obj.optLong(FIELD_FILE_SIZE, 0L),
                modifiedAt = obj.optLong(FIELD_MODIFIED_AT, 0L)
            )
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
        PrefsGateway.open(context, PREFS_NAME)

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
