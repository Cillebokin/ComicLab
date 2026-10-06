package com.example.comiclab.ui.browser.model

import java.io.File

data class FileItem(
    val file: File? = null,
    val isParent: Boolean = false,
    val childCount: Int? = null
)
