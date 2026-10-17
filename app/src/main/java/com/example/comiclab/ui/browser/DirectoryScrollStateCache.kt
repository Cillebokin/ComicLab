package com.example.comiclab.ui.browser

import java.io.File

internal data class FileListScrollState(
    val directoryPath: String,
    val firstVisiblePosition: Int,
    val firstVisibleTop: Int,
    val anchorPath: String?,
    val anchorIsParent: Boolean
)

internal class DirectoryScrollStateCache(
    initialStates: Iterable<FileListScrollState> = emptyList()
) {
    private val states = linkedMapOf<String, FileListScrollState>()

    init {
        restore(initialStates)
    }

    fun save(state: FileListScrollState) {
        val directoryPath = File(state.directoryPath).absolutePath
        states[directoryPath] = state.copy(directoryPath = directoryPath)
    }

    fun get(directoryPath: String): FileListScrollState? {
        return states[File(directoryPath).absolutePath]
    }

    fun snapshot(): List<FileListScrollState> {
        return states.values.toList()
    }

    fun restore(savedStates: Iterable<FileListScrollState>) {
        states.clear()
        savedStates.forEach(::save)
    }
}
