package com.example.comiclab

import java.io.File

object MangaCollectionCoverClickHandler {

    fun dispatch(directory: File, callback: ((File) -> Unit)?): Boolean {
        callback?.invoke(directory)
        return callback != null
    }

    fun dispatchLongClick(directory: File, callback: ((File) -> Unit)?): Boolean {
        return dispatch(directory, callback)
    }
}
