package com.example.comiclab.ebook

import java.io.InputStream

interface EbookSession : AutoCloseable {

    val book: EbookBook

    fun openResource(resourceId: String): InputStream?

    /** HTML文档已持久到本次会话临时文件，目录/进度仅需章节元数据。 */
    fun releaseChapterContent() {}
}

internal fun EbookBook.withoutChapterContent(): EbookBook = copy(
    chapters = chapters.map { it.copy(html = "") }, stylesheet = null
)
