package com.example.comiclab.ebook.mobi

import java.io.IOException

enum class MobiParseError {
    INVALID_FILE,
    TRUNCATED_FILE,
    INVALID_MOBI_HEADER,
    DRM_PROTECTED,
    UNSUPPORTED_COMPRESSION,
    UNSUPPORTED_FORMAT,
    INVALID_RECORD,
    EMPTY_BOOK
}

class MobiParseException(
    val reason: MobiParseError,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
