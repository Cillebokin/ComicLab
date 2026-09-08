package com.example.comiclab.ebook

data class EbookBook(
    val title: String,
    val author: String?,
    val chapters: List<EbookChapter>,
    val resources: List<EbookResource>,
    val coverResourceId: String? = null,
    val stylesheet: String? = null
)

data class EbookChapter(
    val index: Int,
    val title: String,
    val html: String,
    val sourcePath: String? = null
)

data class EbookResource(
    val id: String,
    val recordIndex: Int = -1,
    val mimeType: String,
    val path: String = "",
    /** Relative resource slot used by formats such as MOBI recindex. */
    val resourceIndex: Int = -1
)

data class EbookResourceRecord(
    val id: String,
    val recordIndex: Int = -1,
    val mimeType: String,
    val path: String = "",
    val offset: Long = -1L,
    val length: Int = -1
)

data class ParsedEbook(
    val book: EbookBook,
    val resourceRecords: Map<String, EbookResourceRecord>,
    val sourceFile: java.io.File
)
