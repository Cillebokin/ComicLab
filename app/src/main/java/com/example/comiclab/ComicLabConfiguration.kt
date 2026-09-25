package com.example.comiclab

import org.json.JSONArray
import org.json.JSONObject

data class ComicLabFavoriteComic(
    val path: String,
    val addedAt: Long,
    val fileSize: Long,
    val modifiedAt: Long
)

data class ComicLabFavoritePath(
    val path: String,
    val addedAt: Long
)

data class ComicLabHistoryEntry(
    val path: String,
    val lastReadAt: Long,
    val fileSize: Long,
    val modifiedAt: Long
)

data class ComicLabMangaProgress(
    val path: String,
    val position: Int,
    val offset: Int
)

data class ComicLabEbookProgress(
    val path: String,
    val chapterIndex: Int,
    val scrollFraction: Float
)

data class ComicLabAppSettings(
    val darkModeEnabled: Boolean? = null,
    val languageTag: String? = null,
    val readingDirection: String? = null,
    val doublePageCoverSingle: Boolean? = null,
    val volumeKeyPageTurn: Boolean? = null,
    val autoHideSystemBars: Boolean? = null,
    val startMarkerErrorTags: String? = null,
    val customReaderBrightnessEnabled: Boolean? = null,
    val customReaderBrightness: Int? = null
)

data class ComicLabConfiguration(
    val schemaVersion: Int = ComicLabConfigurationJson.CURRENT_SCHEMA_VERSION,
    val exportedAt: Long = 0L,
    val favoriteComics: List<ComicLabFavoriteComic> = emptyList(),
    val favoritePaths: List<ComicLabFavoritePath> = emptyList(),
    val readingHistory: List<ComicLabHistoryEntry> = emptyList(),
    val bookcasePaths: List<String> = emptyList(),
    val mangaReadingProgress: List<ComicLabMangaProgress> = emptyList(),
    val ebookReadingProgress: List<ComicLabEbookProgress> = emptyList(),
    val appSettings: ComicLabAppSettings = ComicLabAppSettings()
)

data class ComicLabConfigurationDecodeResult(
    val configuration: ComicLabConfiguration,
    val skippedEntries: Int
)

object ComicLabConfigurationJson {
    const val CURRENT_SCHEMA_VERSION = 1

    fun encode(configuration: ComicLabConfiguration): String {
        val root = JSONObject()
            .put("schemaVersion", configuration.schemaVersion)
            .put("exportedAt", configuration.exportedAt)
            .put("favoriteComics", JSONArray().also { array ->
                configuration.favoriteComics.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("path", item.path)
                            .put("addedAt", item.addedAt)
                            .put("fileSize", item.fileSize)
                            .put("modifiedAt", item.modifiedAt)
                    )
                }
            })
            .put("favoritePaths", JSONArray().also { array ->
                configuration.favoritePaths.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("path", item.path)
                            .put("addedAt", item.addedAt)
                    )
                }
            })
            .put("readingHistory", JSONArray().also { array ->
                configuration.readingHistory.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("path", item.path)
                            .put("lastReadAt", item.lastReadAt)
                            .put("fileSize", item.fileSize)
                            .put("modifiedAt", item.modifiedAt)
                    )
                }
            })
            .put("bookcasePaths", JSONArray().also { array ->
                configuration.bookcasePaths.forEach(array::put)
            })
            .put("mangaReadingProgress", JSONArray().also { array ->
                configuration.mangaReadingProgress.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("path", item.path)
                            .put("position", item.position)
                            .put("offset", item.offset)
                    )
                }
            })
            .put("ebookReadingProgress", JSONArray().also { array ->
                configuration.ebookReadingProgress.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("path", item.path)
                            .put("chapterIndex", item.chapterIndex)
                            .put("scrollFraction", item.scrollFraction.toDouble())
                    )
                }
            })

        val settings = JSONObject()
        configuration.appSettings.darkModeEnabled?.let {
            settings.put("darkModeEnabled", it)
        }
        configuration.appSettings.languageTag?.let {
            settings.put("languageTag", it)
        }
        configuration.appSettings.readingDirection?.let { settings.put("readingDirection", it) }
        configuration.appSettings.doublePageCoverSingle?.let {
            settings.put("doublePageCoverSingle", it)
        }
        configuration.appSettings.volumeKeyPageTurn?.let {
            settings.put("volumeKeyPageTurn", it)
        }
        configuration.appSettings.autoHideSystemBars?.let {
            settings.put("autoHideSystemBars", it)
        }
        configuration.appSettings.startMarkerErrorTags?.let {
            settings.put("startMarkerErrorTags", it)
        }
        configuration.appSettings.customReaderBrightnessEnabled?.let {
            settings.put("customReaderBrightnessEnabled", it)
        }
        configuration.appSettings.customReaderBrightness?.let {
            settings.put("customReaderBrightness", it)
        }
        root.put("appSettings", settings)
        return root.toString()
    }

    fun decode(rawJson: String): ComicLabConfigurationDecodeResult {
        val root = runCatching { JSONObject(rawJson) }
            .getOrElse { error ->
                throw IllegalArgumentException("Invalid configuration JSON", error)
            }
        val schemaVersion = root.optInt("schemaVersion", CURRENT_SCHEMA_VERSION)
        if (schemaVersion !in 1..CURRENT_SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported configuration schema: $schemaVersion")
        }

        var skippedEntries = 0
        val favoriteComics = mutableListOf<ComicLabFavoriteComic>()
        val favoritePaths = mutableListOf<ComicLabFavoritePath>()
        val readingHistory = mutableListOf<ComicLabHistoryEntry>()
        val bookcasePaths = mutableListOf<String>()
        val mangaReadingProgress = mutableListOf<ComicLabMangaProgress>()
        val ebookReadingProgress = mutableListOf<ComicLabEbookProgress>()

        forEachObject(root, "favoriteComics") { obj ->
            val path = obj.requiredString("path")
            val addedAt = obj.requiredLong("addedAt")
            val fileSize = obj.requiredLong("fileSize")
            val modifiedAt = obj.requiredLong("modifiedAt")
            if (path == null || addedAt == null || fileSize == null || modifiedAt == null) {
                skippedEntries++
            } else {
                favoriteComics += ComicLabFavoriteComic(path, addedAt, fileSize, modifiedAt)
            }
        }.also { skippedEntries += it }

        forEachObject(root, "favoritePaths") { obj ->
            val path = obj.requiredString("path")
            val addedAt = obj.requiredLong("addedAt")
            if (path == null || addedAt == null) {
                skippedEntries++
            } else {
                favoritePaths += ComicLabFavoritePath(path, addedAt)
            }
        }.also { skippedEntries += it }

        forEachObject(root, "readingHistory") { obj ->
            val path = obj.requiredString("path")
            val lastReadAt = obj.requiredLong("lastReadAt")
            val fileSize = obj.requiredLong("fileSize")
            val modifiedAt = obj.requiredLong("modifiedAt")
            if (path == null || lastReadAt == null || fileSize == null || modifiedAt == null) {
                skippedEntries++
            } else {
                readingHistory += ComicLabHistoryEntry(path, lastReadAt, fileSize, modifiedAt)
            }
        }.also { skippedEntries += it }

        forEachString(root, "bookcasePaths") { path ->
            if (path.isBlank()) {
                skippedEntries++
            } else {
                bookcasePaths += path
            }
        }.also { skippedEntries += it }

        forEachObject(root, "mangaReadingProgress") { obj ->
            val path = obj.requiredString("path")
            val position = obj.requiredInt("position")
            val offset = obj.requiredInt("offset")
            if (path == null || position == null || offset == null) {
                skippedEntries++
            } else {
                mangaReadingProgress += ComicLabMangaProgress(path, position, offset)
            }
        }.also { skippedEntries += it }

        forEachObject(root, "ebookReadingProgress") { obj ->
            val path = obj.requiredString("path")
            val chapterIndex = obj.requiredInt("chapterIndex")
            val scrollFraction = obj.requiredFloat("scrollFraction")
            if (path == null || chapterIndex == null || scrollFraction == null) {
                skippedEntries++
            } else {
                ebookReadingProgress += ComicLabEbookProgress(path, chapterIndex, scrollFraction)
            }
        }.also { skippedEntries += it }

        val settingsObject = root.optJSONObject("appSettings")
        val appSettings = if (settingsObject == null) {
            if (root.has("appSettings") && !root.isNull("appSettings")) {
                skippedEntries++
            }
            ComicLabAppSettings()
        } else {
            ComicLabAppSettings(
                darkModeEnabled = settingsObject.optionalBoolean("darkModeEnabled")
                    .also {
                        if (settingsObject.has("darkModeEnabled") && it == null) skippedEntries++
                    },
                languageTag = settingsObject.optionalString("languageTag")
                    .also {
                        if (settingsObject.has("languageTag") && it == null) skippedEntries++
                    },
                readingDirection = settingsObject.optionalString("readingDirection")
                    .also { if (settingsObject.has("readingDirection") && it == null) skippedEntries++ },
                doublePageCoverSingle = settingsObject.optionalBoolean("doublePageCoverSingle")
                    .also {
                        if (settingsObject.has("doublePageCoverSingle") && it == null) skippedEntries++
                    },
                volumeKeyPageTurn = settingsObject.optionalBoolean("volumeKeyPageTurn")
                    .also {
                        if (settingsObject.has("volumeKeyPageTurn") && it == null) skippedEntries++
                    },
                autoHideSystemBars = settingsObject.optionalBoolean("autoHideSystemBars")
                    .also {
                        if (settingsObject.has("autoHideSystemBars") && it == null) skippedEntries++
                    },
                startMarkerErrorTags = settingsObject.optionalString("startMarkerErrorTags")
                    .also {
                        if (settingsObject.has("startMarkerErrorTags") && it == null) skippedEntries++
                    },
                customReaderBrightnessEnabled = settingsObject
                    .optionalBoolean("customReaderBrightnessEnabled")
                    .also {
                        if (settingsObject.has("customReaderBrightnessEnabled") && it == null) {
                            skippedEntries++
                        }
                    },
                customReaderBrightness = settingsObject.optionalInt("customReaderBrightness")
                    .also {
                        if (settingsObject.has("customReaderBrightness") && it == null) skippedEntries++
                    }
            )
        }

        return ComicLabConfigurationDecodeResult(
            configuration = ComicLabConfiguration(
                schemaVersion = schemaVersion,
                exportedAt = root.optLong("exportedAt", 0L),
                favoriteComics = favoriteComics,
                favoritePaths = favoritePaths,
                readingHistory = readingHistory,
                bookcasePaths = bookcasePaths,
                mangaReadingProgress = mangaReadingProgress,
                ebookReadingProgress = ebookReadingProgress,
                appSettings = appSettings
            ),
            skippedEntries = skippedEntries
        )
    }

    private fun forEachObject(
        root: JSONObject,
        key: String,
        action: (JSONObject) -> Unit
    ): Int {
        val array = root.optJSONArray(key) ?: return if (root.has(key) && !root.isNull(key)) 1 else 0
        var skipped = 0
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
            if (obj == null) {
                skipped++
            } else {
                action(obj)
            }
        }
        return skipped
    }

    private fun forEachString(
        root: JSONObject,
        key: String,
        action: (String) -> Unit
    ): Int {
        val array = root.optJSONArray(key) ?: return if (root.has(key) && !root.isNull(key)) 1 else 0
        var skipped = 0
        for (index in 0 until array.length()) {
            val value = array.opt(index)
            if (value !is String) {
                skipped++
            } else {
                action(value)
            }
        }
        return skipped
    }

    private fun JSONObject.requiredString(key: String): String? {
        val value = opt(key)
        return (value as? String)?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.requiredLong(key: String): Long? {
        val value = opt(key)
        return (value as? Number)?.toLong()
    }

    private fun JSONObject.requiredInt(key: String): Int? {
        val value = opt(key) as? Number ?: return null
        val longValue = value.toLong()
        return longValue.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun JSONObject.requiredFloat(key: String): Float? {
        val value = opt(key) as? Number ?: return null
        return value.toFloat().takeIf { it.isFinite() }
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? String
    }

    private fun JSONObject.optionalBoolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? Boolean
    }

    private fun JSONObject.optionalInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return requiredInt(key)
    }
}

object ComicLabConfigurationMerger {
    fun merge(
        current: ComicLabConfiguration,
        imported: ComicLabConfiguration
    ): ComicLabConfiguration {
        return ComicLabConfiguration(
            schemaVersion = ComicLabConfigurationJson.CURRENT_SCHEMA_VERSION,
            exportedAt = maxOf(current.exportedAt, imported.exportedAt),
            favoriteComics = mergeByPath(current.favoriteComics, imported.favoriteComics),
            favoritePaths = mergeByPath(current.favoritePaths, imported.favoritePaths),
            readingHistory = mergeHistory(current.readingHistory, imported.readingHistory),
            bookcasePaths = mergeStrings(current.bookcasePaths, imported.bookcasePaths),
            mangaReadingProgress = mergeByPath(
                current.mangaReadingProgress,
                imported.mangaReadingProgress
            ),
            ebookReadingProgress = mergeByPath(
                current.ebookReadingProgress,
                imported.ebookReadingProgress
            ),
            appSettings = mergeSettings(current.appSettings, imported.appSettings)
        )
    }

    private fun <T : Any> mergeByPath(current: List<T>, imported: List<T>): List<T> {
        val merged = LinkedHashMap<String, T>()
        current.forEach { merged[pathOf(it)] = it }
        imported.forEach { merged[pathOf(it)] = it }
        return merged.values.toList()
    }

    private fun mergeHistory(
        current: List<ComicLabHistoryEntry>,
        imported: List<ComicLabHistoryEntry>
    ): List<ComicLabHistoryEntry> {
        val merged = LinkedHashMap<String, ComicLabHistoryEntry>()
        current.forEach { merged[it.path] = it }
        imported.forEach { item ->
            val existing = merged[item.path]
            if (existing == null || item.lastReadAt >= existing.lastReadAt) {
                merged[item.path] = item
            }
        }
        return merged.values.toList()
    }

    private fun mergeStrings(current: List<String>, imported: List<String>): List<String> {
        return (current + imported).filter { it.isNotBlank() }.distinct()
    }

    private fun mergeSettings(
        current: ComicLabAppSettings,
        imported: ComicLabAppSettings
    ): ComicLabAppSettings {
        return ComicLabAppSettings(
            darkModeEnabled = imported.darkModeEnabled ?: current.darkModeEnabled,
            languageTag = imported.languageTag ?: current.languageTag,
            readingDirection = imported.readingDirection ?: current.readingDirection,
            doublePageCoverSingle = imported.doublePageCoverSingle ?: current.doublePageCoverSingle,
            volumeKeyPageTurn = imported.volumeKeyPageTurn ?: current.volumeKeyPageTurn,
            autoHideSystemBars = imported.autoHideSystemBars ?: current.autoHideSystemBars,
            startMarkerErrorTags = imported.startMarkerErrorTags ?: current.startMarkerErrorTags,
            customReaderBrightnessEnabled = imported.customReaderBrightnessEnabled
                ?: current.customReaderBrightnessEnabled,
            customReaderBrightness = imported.customReaderBrightness
                ?: current.customReaderBrightness
        )
    }

    private fun pathOf(item: Any): String {
        return when (item) {
            is ComicLabFavoriteComic -> item.path
            is ComicLabFavoritePath -> item.path
            is ComicLabMangaProgress -> item.path
            is ComicLabEbookProgress -> item.path
            else -> error("Unsupported configuration item: ${item::class.java.name}")
        }
    }
}
