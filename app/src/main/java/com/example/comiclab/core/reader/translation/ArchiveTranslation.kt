package com.example.comiclab.core.reader.translation

import com.example.comiclab.core.reader.ReaderDerivationPrivacy

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
    val keptOriginalPages: Int,
    val pagesWithoutText: Int,
    val serviceStopped: TranslationServiceException.Reason?
)

internal class ArchiveTranslationPagesFailedException : IllegalStateException("No page could be translated")

/** 顺序翻译整本漫画；单页处理失败时把原始页面写入译本并继续。 */
internal object ArchiveTranslation {
    fun translate(
        context: Context,
        source: File,
        directory: File,
        cancelled: AtomicBoolean,
        language: String,
        settings: TranslationSettings,
        ocrConcurrency: Int = SinglePageTranslation.DEFAULT_OCR_CONCURRENCY,
        inpaintConcurrency: Int = SinglePageTranslation.DEFAULT_INPAINT_CONCURRENCY,
        translate: (TranslationSettings, List<String>, String, AtomicBoolean) -> List<String> = TranslationApi::translate,
        sourcePath: String? = ReaderDerivationPrivacy.ordinaryPath(context, source),
        onProgress: (page: Int, total: Int, pagePercent: Int, stage: String,
            translatedPages: Int, keptOriginalPages: Int) -> Unit
    ): ArchiveTranslationResult {
        require(source.isFile && !ComicArchive.isPdf(source))
        fun checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
            ReaderDerivationPrivacy.check(context, source, sourcePath)
        }

        checkCancelled()
        val entries = ComicArchive.imageEntries(source)
        require(entries.isNotEmpty()) { "Archive contains no readable pages" }
        TranslationPolicy.endpoint(settings.address)
        require(settings.model.isNotBlank() && settings.key.isNotBlank()) { "Translation settings are incomplete" }

        val inputs = File(directory, "inputs").apply { check(mkdirs() || isDirectory) }
        val pages = File(directory, "pages").apply { check(mkdirs() || isDirectory) }
        val output = File(directory, "translated.cbz")
        try {
            val models = TranslationModels.ensure(context, cancelled) { detail ->
                onProgress(0, entries.size, 0, "models|$detail", 0, 0)
            }
            checkCancelled()
            return ComicArchive.openPreparedImageExtractor(source, inputs).use { extractor ->
                translatePages(
                    output, entries, cancelled,
                    extractPage = { index, entry ->
                        checkCancelled()
                        extractor.extract(index, entry) ?: error("Could not extract page ${index + 1}")
                    },
                    translatePage = { index, entry, original, report ->
                        val pageDirectory = File(pages, "%06d".format(Locale.ROOT, index + 1))
                        try {
                            check(pageDirectory.mkdirs() || pageDirectory.isDirectory)
                            val pipeline = SinglePageTranslation(context, cancelled, ocrConcurrency, inpaintConcurrency, report)
                            val analysis = pipeline.process(
                                archive = source,
                                entry = entry,
                                directory = pageDirectory,
                                settings = settings,
                                language = language,
                                translate = translate,
                                modelDirectory = models,
                                preparedSource = original,
                                sourcePath = sourcePath
                            )
                            pipeline.render(analysis, pageDirectory, TranslationStyle()).also { rendered ->
                                pageDirectory.listFiles()?.forEach { if (it != rendered) it.deleteRecursively() }
                            }
                        } catch (failure: Exception) {
                            pageDirectory.deleteRecursively()
                            throw failure
                        }
                    },
                    onProgress = onProgress
                )
            }.also { checkCancelled() }
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        } finally {
            inputs.deleteRecursively()
            pages.deleteRecursively()
        }
    }

    internal fun translatePages(
        output: File,
        entries: List<String>,
        cancelled: AtomicBoolean,
        extractPage: (Int, String) -> File,
        translatePage: (Int, String, File, (String) -> Unit) -> File,
        onProgress: (Int, Int, Int, String, Int, Int) -> Unit
    ): ArchiveTranslationResult {
        var translatedPages = 0
        var keptOriginalPages = 0
        var pagesWithoutText = 0
        var consecutiveServiceFailures = 0
        var lastServiceFailure: TranslationServiceException? = null
        var stoppedBy: TranslationServiceException.Reason? = null
        EnhancedComicWriter.write(
            output = output,
            entries = entries,
            cancelled = cancelled,
            render = render@{ index, entry ->
                TranslationModels.checkCancelled(cancelled)
                val original = extractPage(index, entry)
                fun progress(percent: Int, stage: String) {
                    onProgress(index + 1, entries.size, percent, stage, translatedPages, keptOriginalPages)
                }
                fun keepOriginal(stage: String): File {
                    keptOriginalPages++
                    progress(100, stage)
                    return original
                }
                if (stoppedBy != null) return@render keepOriginal("kept")
                val rendered = try {
                    translatePage(index, entry, original) { progress(0, it) }
                } catch (failure: CancellationException) {
                    ComicArchive.discardPreparedImageFile(original)
                    throw failure
                } catch (failure: TranslationServiceException) {
                    lastServiceFailure = failure
                    consecutiveServiceFailures++
                    if (failure.reason != TranslationServiceException.Reason.UNAVAILABLE ||
                        consecutiveServiceFailures >= 2
                    ) {
                        if (translatedPages == 0) {
                            ComicArchive.discardPreparedImageFile(original)
                            throw failure
                        }
                        stoppedBy = failure.reason
                    }
                    return@render keepOriginal("kept")
                } catch (_: NoReadableTranslationTextException) {
                    pagesWithoutText++
                    return@render keepOriginal("no_text")
                } catch (_: Exception) {
                    return@render keepOriginal("kept")
                }
                translatedPages++
                consecutiveServiceFailures = 0
                ComicArchive.discardPreparedImageFile(original)
                progress(100, "render")
                rendered
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
        if (translatedPages == 0) {
            output.delete()
            throw lastServiceFailure ?: if (pagesWithoutText == entries.size) NoReadableTranslationTextException()
            else ArchiveTranslationPagesFailedException()
        }
        return ArchiveTranslationResult(output, translatedPages, keptOriginalPages, pagesWithoutText, stoppedBy)
    }
}
