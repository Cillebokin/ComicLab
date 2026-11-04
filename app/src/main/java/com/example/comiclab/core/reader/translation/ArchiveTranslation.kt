package com.example.comiclab.core.reader.translation

import android.content.Context
import com.example.comiclab.ComicArchive
import com.example.comiclab.core.archive.ArchiveEntryLister
import com.example.comiclab.core.reader.enhance.EnhancedComicWriter
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal data class ArchiveTranslationResult(
    val file: File,
    val translatedPages: Int,
    val keptOriginalPages: Int
)

/** 顺序翻译整本漫画；单页处理失败时把原始页面写入译本并继续。 */
internal object ArchiveTranslation {
    fun translate(
        context: Context,
        source: File,
        directory: File,
        cancelled: AtomicBoolean,
        language: String,
        settings: TranslationSettings,
        onProgress: (page: Int, total: Int, pagePercent: Int, stage: String,
            translatedPages: Int, keptOriginalPages: Int) -> Unit
    ): ArchiveTranslationResult {
        require(source.isFile && !ComicArchive.isPdf(source))
        fun checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        }

        checkCancelled()
        val entries = ComicArchive.imageEntries(source)
        require(entries.isNotEmpty()) { "Archive contains no readable pages" }
        TranslationPolicy.endpoint(settings.address)
        require(settings.model.isNotBlank() && settings.key.isNotBlank()) { "Translation settings are incomplete" }

        val inputs = File(directory, "inputs").apply { check(mkdirs() || isDirectory) }
        val pages = File(directory, "pages").apply { check(mkdirs() || isDirectory) }
        val output = File(directory, "translated.cbz")
        var translatedPages = 0
        var keptOriginalPages = 0
        try {
            val models = TranslationModels.ensure(context, cancelled) { detail ->
                onProgress(0, entries.size, 0, "models|$detail", translatedPages, keptOriginalPages)
            }
            ComicArchive.openPreparedImageExtractor(source, inputs).use { extractor ->
                EnhancedComicWriter.write(
                    output = output,
                    entries = entries,
                    cancelled = cancelled,
                    render = { index, entry ->
                        checkCancelled()
                        val original = extractor.extract(index, entry)
                            ?: error("Could not extract page ${index + 1}")
                        val pageDirectory = File(pages, "%06d".format(Locale.ROOT, index + 1))
                        val translated = try {
                            check(pageDirectory.mkdirs() || pageDirectory.isDirectory)
                            val pipeline = SinglePageTranslation(context, cancelled) { stage ->
                                onProgress(index + 1, entries.size, 0, stage,
                                    translatedPages, keptOriginalPages)
                            }
                            val analysis = pipeline.process(
                                archive = source,
                                entry = entry,
                                directory = pageDirectory,
                                settings = settings,
                                language = language,
                                modelDirectory = models,
                                preparedSource = original
                            )
                            pipeline.render(analysis, pageDirectory, TranslationStyle())
                        } catch (failure: CancellationException) {
                            ComicArchive.discardPreparedImageFile(original)
                            pageDirectory.deleteRecursively()
                            throw failure
                        } catch (_: Exception) {
                            keptOriginalPages++
                            pageDirectory.deleteRecursively()
                            onProgress(index + 1, entries.size, 100, "kept",
                                translatedPages, keptOriginalPages)
                            original
                        }

                        if (translated != original) {
                            translatedPages++
                            ComicArchive.discardPreparedImageFile(original)
                            pageDirectory.listFiles()?.forEach { file ->
                                if (file != translated) file.deleteRecursively()
                            }
                            onProgress(index + 1, entries.size, 100, "render",
                                translatedPages, keptOriginalPages)
                        }
                        translated
                    },
                    onPage = { page, total ->
                        onProgress(page, total, 100, "write", translatedPages, keptOriginalPages)
                    },
                    outputEntryName = { index, entry, image ->
                        val extension = image.extension.lowercase(Locale.ROOT)
                            .takeIf { it in ArchiveEntryLister.imageExtensions }
                            ?: entry.substringAfterLast('.', "png").lowercase(Locale.ROOT)
                        "%06d.%s".format(Locale.ROOT, index + 1, extension)
                    }
                )
            }
            checkCancelled()
            return ArchiveTranslationResult(output, translatedPages, keptOriginalPages)
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            inputs.deleteRecursively()
            pages.deleteRecursively()
        }
    }
}
