package com.example.comiclab.ebook.mobi

import java.io.File

data class MobiBook(
    val title: String,
    val author: String?,
    val chapters: List<MobiChapter>,
    val resources: List<MobiResource>,
    val coverResourceId: String? = null
)

data class MobiChapter(
    val index: Int,
    val title: String,
    val html: String
)

data class MobiResource(
    val id: String,
    val recordIndex: Int,
    val mimeType: String
)

data class ResourceRecord(
    val recordIndex: Int,
    val offset: Long,
    val length: Int,
    val mimeType: String
)

data class ParsedMobi(
    val book: MobiBook,
    val resourceRecords: Map<String, ResourceRecord>,
    val sourceFile: File
)
