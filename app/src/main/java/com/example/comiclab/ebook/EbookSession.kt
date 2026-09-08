package com.example.comiclab.ebook

import java.io.InputStream

interface EbookSession : AutoCloseable {

    val book: EbookBook

    fun openResource(resourceId: String): InputStream?
}
