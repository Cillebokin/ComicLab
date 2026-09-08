package com.example.comiclab.ebook.epub

import java.io.IOException

enum class EpubParseError {
    INVALID_FILE,
    INVALID_ARCHIVE,
    MISSING_MIMETYPE,
    INVALID_CONTAINER,
    INVALID_PACKAGE,
    ENCRYPTED,
    UNSUPPORTED_FORMAT,
    EMPTY_BOOK
}

class EpubParseException(
    val reason: EpubParseError,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
