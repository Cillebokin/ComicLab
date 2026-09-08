package com.example.comiclab.ebook.epub

import com.example.comiclab.ebook.EbookBook
import com.example.comiclab.ebook.EbookResourceRecord
import java.io.File

data class ParsedEpub(
    val book: EbookBook,
    val resourceRecords: Map<String, EbookResourceRecord>,
    val sourceFile: File
)
