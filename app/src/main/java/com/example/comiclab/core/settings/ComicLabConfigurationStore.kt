package com.example.comiclab.core.settings

import com.example.comiclab.AppSettings
import com.example.comiclab.MangaReaderActivity
import android.content.Context
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.ebook.ReaderFileDetector
import java.io.File

data class ComicLabConfigurationImportSummary(
    // 不含 applyAppSettings=false 时尚未应用的设置，UI 应在应用后追加实际数量。
    val restoredEntries: Int,
    val skippedEntries: Int,
    // 备份包含该路径进度且本地已有进度时，按唯一文件路径统计。
    val preservedReadingProgressEntries: Int = 0
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
        validateImportDestinations(context, configuration)

        var skippedEntries = initiallySkippedEntries
        var restoredEntries = 0
        var preservedReadingProgressEntries = 0

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
        val retainedComics = FavoriteComicStore.merge(context, favoriteComics)
        restoredEntries += retainedComics
        skippedEntries += favoriteComics.size - retainedComics

        val favoritePaths = configuration.favoritePaths.mapNotNull { item ->
            val directory = File(item.path)
            if (!directory.isDirectory) {
                skippedEntries++
                return@mapNotNull null
            }
            FavoritePathStore.Item(directory = directory, addedAt = item.addedAt)
        }
        val retainedPaths = FavoritePathStore.merge(context, favoritePaths)
        restoredEntries += retainedPaths
        skippedEntries += favoritePaths.size - retainedPaths

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
        val retainedHistory = ReadingHistoryStore.merge(context, history)
        restoredEntries += retainedHistory
        skippedEntries += history.size - retainedHistory

        val bookcaseDirectories = configuration.bookcasePaths.mapNotNull { path ->
            val directory = File(path)
            if (!directory.isDirectory) {
                skippedEntries++
                return@mapNotNull null
            }
            directory
        }
        AppSettings.setBookcaseDirectories(
            context,
            bookcaseDirectories,
            enabled = true
        )
        val savedBookcasePaths = AppSettings.bookcaseDirectoryPaths(context)
        val retainedBookcases = bookcaseDirectories.distinctBy { it.absolutePath }
            .count { it.absolutePath in savedBookcasePaths }
        restoredEntries += retainedBookcases
        skippedEntries += bookcaseDirectories.size - retainedBookcases

        // 配置没有单本进度更新时间，导入只补本地空缺，避免旧备份回退当前进度。
        val mangaPaths = mutableSetOf<String>()
        val mangaPathHadLocalProgress = mutableMapOf<String, Boolean>()
        configuration.mangaReadingProgress.forEach { item ->
            val file = File(item.path)
            if (!file.isFile || !ReaderFileDetector.isSupported(file) || ReaderFileDetector.isEbook(file)) {
                skippedEntries++
                return@forEach
            }
            val path = file.absolutePath
            val hadLocalProgress = mangaPathHadLocalProgress.getOrPut(path) {
                MangaReaderActivity.savedReadingProgress(context, file) != null
            }
            if (hadLocalProgress) {
                if (mangaPaths.add(path)) preservedReadingProgressEntries++
                skippedEntries++
                return@forEach
            }
            MangaReaderActivity.restoreReadingProgress(context, file, item)
            if (mangaPaths.add(path)) restoredEntries++ else skippedEntries++
        }

        val ebookIdentities = mutableSetOf<String>()
        val ebookIdentityHadLocalProgress = mutableMapOf<String, Boolean>()
        configuration.ebookReadingProgress.forEach { item ->
            val file = File(item.path)
            if (!file.isFile || !ReaderFileDetector.isEbook(file)) {
                skippedEntries++
                return@forEach
            }
            val identity = EbookProgressStore.fileIdentity(file)
            val hadLocalProgress = ebookIdentityHadLocalProgress.getOrPut(identity) {
                EbookProgressStore.load(context, file) != null
            }
            if (hadLocalProgress) {
                if (ebookIdentities.add(identity)) preservedReadingProgressEntries++
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
            if (ebookIdentities.add(identity)) restoredEntries++ else skippedEntries++
        }

        if (applyAppSettings) restoredEntries += AppSettings.applySnapshot(context, configuration.appSettings)
        return ComicLabConfigurationImportSummary(
            restoredEntries = restoredEntries,
            skippedEntries = skippedEntries,
            preservedReadingProgressEntries = preservedReadingProgressEntries
        )
    }

    /**
     * 在任何导入写入前读取所有将参与合并的本地数据，避免后续 Store 的损坏值
     * 在前序集合已经落盘后才暴露。
     */
    private fun validateImportDestinations(
        context: Context,
        configuration: ComicLabConfiguration
    ) {
        FavoriteComicStore.validateReadable(context)
        FavoritePathStore.validateReadable(context)
        ReadingHistoryStore.validateReadable(context)
        AppSettings.bookcaseDirectoryPaths(context)
        AppSettingsStore.validateSnapshotApply(context, configuration.appSettings)

        configuration.mangaReadingProgress
            .asSequence()
            .map { File(it.path) }
            .filter { it.isFile && ReaderFileDetector.isSupported(it) && !ReaderFileDetector.isEbook(it) }
            .distinctBy { it.absolutePath }
            .forEach { MangaReaderActivity.savedReadingProgress(context, it) }

        configuration.ebookReadingProgress
            .asSequence()
            .map { File(it.path) }
            .filter { it.isFile && ReaderFileDetector.isEbook(it) }
            .distinctBy { EbookProgressStore.fileIdentity(it) }
            .forEach { EbookProgressStore.load(context, it) }
    }

}
