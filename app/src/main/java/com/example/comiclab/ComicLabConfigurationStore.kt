package com.example.comiclab

import android.content.Context
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ebook.EbookProgressStore
import com.example.comiclab.ebook.ReaderFileDetector
import java.io.File

data class ComicLabConfigurationImportSummary(
    val restoredEntries: Int,
    val skippedEntries: Int
)

object ComicLabConfigurationStore {

    fun export(context: Context): ComicLabConfiguration {
        val favoriteComics = FavoriteComicStore.items(context)
        val favoritePaths = FavoritePathStore.items(context)
        val history = ReadingHistoryStore.items(context)
        val candidateFiles = LinkedHashMap<String, File>()
        (favoriteComics.map { it.file } + history.map { it.file }).forEach { file ->
            candidateFiles.putIfAbsent(file.absolutePath, file)
        }

        val mangaProgress = candidateFiles.values
            .filter { !ReaderFileDetector.isEbook(it) }
            .mapNotNull { MangaReaderActivity.savedReadingProgress(context, it) }
        val ebookProgress = candidateFiles.values
            .filter { ReaderFileDetector.isEbook(it) }
            .mapNotNull { file ->
                EbookProgressStore.load(context, file)?.let { progress ->
                    ComicLabEbookProgress(
                        path = file.absolutePath,
                        chapterIndex = progress.chapterIndex,
                        scrollFraction = progress.scrollFraction
                    )
                }
            }

        return ComicLabConfiguration(
            schemaVersion = ComicLabConfigurationJson.CURRENT_SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            favoriteComics = favoriteComics.map { item ->
                ComicLabFavoriteComic(
                    path = item.file.absolutePath,
                    addedAt = item.addedAt,
                    fileSize = item.fileSize,
                    modifiedAt = item.modifiedAt
                )
            },
            favoritePaths = favoritePaths.map { item ->
                ComicLabFavoritePath(
                    path = item.directory.absolutePath,
                    addedAt = item.addedAt
                )
            },
            readingHistory = history.map { item ->
                ComicLabHistoryEntry(
                    path = item.file.absolutePath,
                    lastReadAt = item.lastReadAt,
                    fileSize = item.fileSize,
                    modifiedAt = item.modifiedAt
                )
            },
            bookcasePaths = AppSettings.bookcaseDirectoryPaths(context).sorted(),
            mangaReadingProgress = mangaProgress,
            ebookReadingProgress = ebookProgress,
            appSettings = AppSettings.snapshot(context)
        )
    }

    fun importAndMerge(
        context: Context,
        configuration: ComicLabConfiguration,
        initiallySkippedEntries: Int = 0,
        applyAppSettings: Boolean = true
    ): ComicLabConfigurationImportSummary {
        var skippedEntries = initiallySkippedEntries
        var restoredEntries = 0

        val favoriteComics = configuration.favoriteComics.mapNotNull { item ->
            val file = File(item.path)
            if (!file.isFile || !ReaderFileDetector.isSupported(file)) {
                skippedEntries++
                return@mapNotNull null
            }
            FavoriteComicStore.Item(
                file = file,
                addedAt = item.addedAt,
                fileSize = item.fileSize,
                modifiedAt = item.modifiedAt
            )
        }
        restoredEntries += FavoriteComicStore.merge(context, favoriteComics)

        val favoritePaths = configuration.favoritePaths.mapNotNull { item ->
            val directory = File(item.path)
            if (!directory.isDirectory) {
                skippedEntries++
                return@mapNotNull null
            }
            FavoritePathStore.Item(directory = directory, addedAt = item.addedAt)
        }
        restoredEntries += FavoritePathStore.merge(context, favoritePaths)

        val history = configuration.readingHistory.mapNotNull { item ->
            val file = File(item.path)
            if (!file.isFile || !ReaderFileDetector.isSupported(file)) {
                skippedEntries++
                return@mapNotNull null
            }
            ReadingHistoryStore.Item(
                file = file,
                lastReadAt = item.lastReadAt,
                fileSize = item.fileSize,
                modifiedAt = item.modifiedAt
            )
        }
        restoredEntries += ReadingHistoryStore.merge(context, history)

        val bookcaseDirectories = configuration.bookcasePaths.mapNotNull { path ->
            val directory = File(path)
            if (!directory.isDirectory) {
                skippedEntries++
                return@mapNotNull null
            }
            directory
        }.distinctBy { it.absolutePath }
        restoredEntries += AppSettings.setBookcaseDirectories(
            context,
            bookcaseDirectories,
            enabled = true
        )

        configuration.mangaReadingProgress.forEach { item ->
            val file = File(item.path)
            if (!file.isFile || !ReaderFileDetector.isSupported(file) || ReaderFileDetector.isEbook(file)) {
                skippedEntries++
                return@forEach
            }
            MangaReaderActivity.restoreReadingProgress(context, file, item)
            restoredEntries++
        }

        configuration.ebookReadingProgress.forEach { item ->
            val file = File(item.path)
            if (!file.isFile || !ReaderFileDetector.isEbook(file)) {
                skippedEntries++
                return@forEach
            }
            EbookProgressStore.save(
                context,
                file,
                EbookProgress(
                    chapterIndex = item.chapterIndex,
                    scrollFraction = item.scrollFraction
                )
            )
            restoredEntries++
        }

        restoredEntries += if (applyAppSettings) {
            AppSettings.applySnapshot(context, configuration.appSettings)
        } else {
            configuration.appSettings.entryCount()
        }
        return ComicLabConfigurationImportSummary(
            restoredEntries = restoredEntries,
            skippedEntries = skippedEntries
        )
    }

    private fun ComicLabAppSettings.entryCount(): Int {
        return listOf(
            darkModeEnabled,
            languageTag,
            readingDirection,
            doublePageCoverSingle,
            volumeKeyPageTurn,
            autoHideSystemBars,
            startMarkerErrorTags,
            customReaderBrightnessEnabled,
            customReaderBrightness
        ).count { it != null }
    }
}
