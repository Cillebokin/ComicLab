package com.example.comiclab.core.settings

import android.content.Context
import com.example.comiclab.AppSettings
import com.example.comiclab.MangaReaderActivity
import com.example.comiclab.core.store.BookcaseFolderPaths
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.ebook.EbookProgress
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
        val knownFiles = favoriteComics.mapTo(linkedSetOf()) { it.file }
        history.mapTo(knownFiles) { it.file }
        val bookcasePaths = AppSettings.bookcaseDirectoryPaths(context)
        val mangaReadingProgress = ReaderProgressStore.exportSavedProgress(context)
        val mangaProgressPaths = mangaReadingProgress.mapTo(hashSetOf()) { it.path }
        val ebookReadingProgress = EbookProgressStore.exportSavedProgress(context, knownFiles)
        // 一次导出中的各类记录按同一份最新标记判定。
        val privateDirectories = PrivacyStore.privacyDirectories(context)

        fun isPrivatePath(path: String): Boolean =
            privateDirectories.any { BookcaseFolderPaths.isPathInDirectory(path, it) }

        fun exportPath(path: String): String? {
            val canonicalPath = PrivacyStore.resolveCanonicalPath(path) ?: return null
            if (isPrivatePath(canonicalPath)) return null
            if (path == canonicalPath || privateDirectories.isEmpty()) return path
            // 普通目标的原始别名也可能经过隐私目录，例如 private/../ordinary。
            val privateAlias = isPrivatePath(path) || generateSequence(File(path).parentFile) { it.parentFile }
                .any { parent ->
                    val canonicalParent = PrivacyStore.resolveCanonicalPath(parent)
                    canonicalParent == null || isPrivatePath(canonicalParent)
                }
            return if (privateAlias) canonicalPath else path
        }

        val settings = AppSettings.snapshot(context)
        return ComicLabConfiguration(
            schemaVersion = ComicLabConfigurationJson.CURRENT_SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            favoriteComics = favoriteComics.mapNotNull { item ->
                ComicLabFavoriteComic(
                    path = exportPath(item.file.path) ?: return@mapNotNull null,
                    addedAt = item.addedAt,
                    fileSize = item.fileSize,
                    modifiedAt = item.modifiedAt
                )
            },
            favoritePaths = favoritePaths.mapNotNull { item ->
                ComicLabFavoritePath(
                    path = exportPath(item.directory.path) ?: return@mapNotNull null,
                    addedAt = item.addedAt
                )
            },
            readingHistory = history.mapNotNull { item ->
                ComicLabHistoryEntry(
                    path = exportPath(item.file.path) ?: return@mapNotNull null,
                    lastReadAt = item.lastReadAt,
                    fileSize = item.fileSize,
                    modifiedAt = item.modifiedAt
                )
            },
            bookcasePaths = bookcasePaths.mapNotNull(::exportPath).distinct().sorted(),
            mangaReadingProgress = mangaReadingProgress.mapNotNull { item ->
                val path = exportPath(item.path) ?: return@mapNotNull null
                // 改写别名不能覆盖普通目标已有的进度。
                if (path != item.path && path in mangaProgressPaths) return@mapNotNull null
                item.copy(path = path)
            }.distinctBy { it.path },
            ebookReadingProgress = ebookReadingProgress.mapNotNull { item ->
                item.copy(path = exportPath(item.path) ?: return@mapNotNull null)
            },
            appSettings = settings.copy(startMarkerErrorTags = settings.startMarkerErrorTags?.let { tags ->
                exportErrorTags(tags, privateDirectories, ::exportPath)
            })
        )
    }

    private fun exportErrorTags(
        tags: String,
        privateDirectories: Set<String>,
        exportPath: (String) -> String?
    ): String {
        if (privateDirectories.isEmpty()) return tags
        val checkedPaths = mutableListOf<IntRange>()
        val privateReferences = mutableListOf<IntRange>()

        fun checkPath(value: String, range: IntRange) {
            val path = value.trim()
            if (!File(path).isAbsolute) return
            checkedPaths += range
            if (exportPath(path) != path) privateReferences += range
        }

        // 引号界定完整路径，目录名中的空格和分号都作为路径内容检查。
        val absoluteStart = """(?:[/\\]|[A-Za-z]:[/\\])"""
        val quotedPaths = Regex(""""\s*$absoluteStart[^"]*"|'\s*$absoluteStart[^']*'""").findAll(tags).toList()
        quotedPaths.forEach { checkPath(it.value.drop(1).dropLast(1), it.range) }

        fun splitUnquoted(range: IntRange, separators: String): List<IntRange> = buildList {
            var start = range.first
            for (index in range) {
                if (tags[index] in separators && quotedPaths.none { index in it.range }) {
                    add(start until index)
                    start = index + 1
                }
            }
            add(start..range.last)
        }

        val parts = splitUnquoted(tags.indices, ";")
        parts.forEach { part ->
            val fields = splitUnquoted(part, ",，")
            var index = 0
            while (index < fields.size) {
                val field = fields[index]
                if (!File(tags.substring(field).trim()).isAbsolute) {
                    index++
                    continue
                }
                // 逗号也能出现在目录名里。优先采用确实存在的最长路径，再退回单个字段。
                val end = (fields.lastIndex downTo index + 1).firstOrNull { candidate ->
                    File(tags.substring(field.first, fields[candidate].last + 1).trim()).exists()
                } ?: index
                val pathRange = field.first..fields[end].last
                checkPath(tags.substring(pathRange), pathRange)
                index = end + 1
            }
        }

        // 兼容旧标签中的裸路径字面值，已检查的完整路径优先于子串匹配。
        privateDirectories.forEach { directory ->
            val literalPath = Regex(
                """(?<![\p{L}\p{N}_./\\-])${Regex.escape(directory)}(?=$|[/\\;\s,，"'=)\]])"""
            )
            literalPath.findAll(tags).forEach { match ->
                if (checkedPaths.none { match.range.first in it && match.range.last in it }) {
                    privateReferences += match.range
                }
            }
        }
        if (privateReferences.isEmpty()) return tags
        return parts.filterNot { part ->
            privateReferences.any { it.first <= part.last && part.first <= it.last }
        }.map { tags.substring(it) }.filter { it.isNotBlank() }.joinToString(";")
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
        val lastMangaProgressByPath = configuration.mangaReadingProgress.associateBy { File(it.path).absolutePath }
        configuration.mangaReadingProgress.forEach { item ->
            val file = File(item.path)
            if (!file.isFile || !ReaderFileDetector.isSupported(file) || ReaderFileDetector.isEbook(file)) {
                skippedEntries++
                return@forEach
            }
            val path = file.absolutePath
            if (!mangaPaths.add(path)) {
                skippedEntries++
                return@forEach
            }
            if (ReaderProgressStore.restoreReadingProgressIfMissing(context, file, lastMangaProgressByPath.getValue(path))) {
                restoredEntries++
            } else {
                preservedReadingProgressEntries++
                skippedEntries++
            }
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
                    scrollFraction = item.scrollFraction,
                    coordinateVersion = item.coordinateVersion
                ),
                chapterCount = item.chapterCount
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
