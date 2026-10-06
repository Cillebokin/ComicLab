package com.example.comiclab.ui.browser.transfer

enum class FileTransferMode {
    COPY,
    CUT
}

data class PendingFileTransfer(
    val sourcePath: String,
    val mode: FileTransferMode,
    val isDirectory: Boolean
)
