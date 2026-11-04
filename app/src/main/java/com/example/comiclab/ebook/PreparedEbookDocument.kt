package com.example.comiclab.ebook

import com.example.comiclab.ebook.epub.EpubParser
import com.example.comiclab.ebook.html.EbookHtmlRenderer
import com.example.comiclab.ebook.html.EbookStyle
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** 所有权归阅读会话；退出、取消和失败时一并关闭流、会话与临时文档。 */
internal class PreparedEbookDocument private constructor(
    val session: EbookSession,
    val htmlFile: File,
    val htmlChars: Long,
    private val chapterFiles: Map<Int, File>
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    @Volatile
    var readFailed = false
        private set
    private val streams = mutableSetOf<InputStream>()

    fun openHtml(): InputStream? = openFile(htmlFile)

    fun openChapter(index: Int): InputStream? = chapterFiles[index]?.let(::openFile)

    private fun openFile(file: File): InputStream? = synchronized(lock) {
        if (closed) return@synchronized null
        try {
            object : FilterInputStream(file.inputStream()) {
                override fun close() {
                    try { super.close() } finally { synchronized(lock) { streams.remove(this) } }
                }
            }.also(streams::add)
        } catch (error: java.io.IOException) {
            readFailed = true
            android.util.Log.w("ComicLabEbook", "Unable to open prepared ebook document", error)
            null
        }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            streams.toList().also { streams.clear() }
        }
        pending.forEach { runCatching { it.close() } }
        try { session.close() } finally {
            chapterFiles.values.forEach(::discard)
            discard(htmlFile)
        }
    }

    companion object {
        private val activeFiles = mutableSetOf<File>()

        private fun createFile(cacheDir: File): File = synchronized(activeFiles) {
            val directory = File(cacheDir, "ebook-documents").canonicalFile
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create ebook document cache" }
            // 清除上次进程被结束留下的本功能缓存，不触及当前仍在读取的文档。
            directory.listFiles()?.filter { it.name.startsWith("reader-") && it.extension == "html" && it !in activeFiles }
                ?.forEach { it.delete() }
            File.createTempFile("reader-", ".html", directory).also(activeFiles::add)
        }

        private fun discard(file: File) = synchronized(activeFiles) {
            file.delete()
            activeFiles.remove(file)
        }

        fun prepare(session: EbookSession, cacheDir: File, style: EbookStyle = EbookStyle(),
                    limits: EbookContentLimits = EbookContentLimits.forHeap()): PreparedEbookDocument {
            var file: File? = null
            val chapters = linkedMapOf<Int, File>()
            try {
                checkEbookCancellation()
                file = createFile(cacheDir)
                var chars = 0L
                var tags = 0L
                EbookLimitWriter(file.bufferedWriter(Charsets.UTF_8), limits).use { writer ->
                    val svgSource: (EbookResource) -> String? = { resource ->
                        try {
                            session.openResource(resource.id)?.use { input ->
                                EpubParser().decodeXml(input.readEbookBytes(limits.maxEntryBytes))
                            }
                        } catch (error: java.io.IOException) {
                            android.util.Log.w("ComicLabEbook", "Unable to load SVG resource ${resource.id}", error)
                            null
                        }
                    }
                    EbookHtmlRenderer().writeChapterDocuments(session.book, style, writer, svgSource,
                        minOf(limits.maxHtmlChars, limits.maxChapterHtmlChars.coerceAtMost(Long.MAX_VALUE / 3) * 3),
                        minOf(limits.maxHtmlTags, limits.maxChapterHtmlTags.coerceAtMost(Long.MAX_VALUE / 3) * 3)) { chapter, write ->
                        val chapterFile = synchronized(activeFiles) {
                            File(file.parentFile, "${file.nameWithoutExtension}-chapter-${chapter.index}.html")
                                .also(activeFiles::add)
                        }
                        chapters[chapter.index] = chapterFile
                        val remaining = limits.maxHtmlChars - chars - writer.charsWritten
                        val remainingTags = limits.maxHtmlTags - tags - writer.tagsWritten
                        if (remaining <= 0 || remainingTags <= 0) throw EbookContentLimitException()
                        EbookLimitWriter(chapterFile.bufferedWriter(Charsets.UTF_8), limits.copy(
                            maxHtmlChars = minOf(remaining, limits.maxChapterHtmlChars),
                            maxHtmlTags = minOf(remainingTags, limits.maxChapterHtmlTags))).use {
                            write(it)
                            chars += it.charsWritten
                            tags += it.tagsWritten
                        }
                    }
                    chars += writer.charsWritten
                    if (chars > limits.maxHtmlChars || tags + writer.tagsWritten > limits.maxHtmlTags) throw EbookContentLimitException()
                }
                checkEbookCancellation()
                session.releaseChapterContent()
                return PreparedEbookDocument(session, file, chars, chapters)
            } catch (error: Throwable) {
                chapters.values.forEach(::discard)
                file?.let(::discard)
                runCatching { session.close() }
                throw error
            }
        }
    }
}
