package com.example.comiclab.ui.reader.manga

import com.example.comiclab.ebook.ReaderFileDetector
import com.example.comiclab.ebook.ReaderFileType
import com.example.comiclab.ui.browser.DirectorySortOrder
import com.example.comiclab.ui.browser.DirectorySortPolicy
import java.io.File
import java.io.IOException

internal fun findNextManga(current: File, order: DirectorySortOrder): File? {
    val parent = current.parentFile ?: return null
    val children = (parent.listFiles() ?: throw IOException("Cannot list comic directory"))
        .filterNot { it.name.startsWith(".") }
    val sorted = DirectorySortPolicy.sort(children, order)
    val index = sorted.indexOf(current)
    if (index < 0) return null
    return sorted.drop(index + 1).firstOrNull {
        when (ReaderFileDetector.typeOf(it)) {
            ReaderFileType.IMAGE_ARCHIVE, ReaderFileType.PDF -> true
            else -> false
        }
    }
}
