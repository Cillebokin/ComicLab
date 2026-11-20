package com.example.comiclab.core.store

internal object BookcaseFolderPaths {

    fun withSelection(paths: Set<String>, path: String, selected: Boolean): Set<String> {
        return paths.toMutableSet().apply {
            if (selected) {
                add(path)
            } else {
                remove(path)
            }
        }
    }

    fun withSelections(paths: Set<String>, selectedPaths: Set<String>, selected: Boolean): Set<String> {
        return paths.toMutableSet().apply {
            if (selected) {
                addAll(selectedPaths)
            } else {
                removeAll(selectedPaths)
            }
        }
    }

    fun afterRename(paths: Set<String>, oldPath: String, newPath: String): Set<String> {
        val affectedPaths = paths.filter { path -> remapPath(path, oldPath, newPath) != path }
        if (affectedPaths.isEmpty()) {
            return paths.toSet()
        }

        return paths.toMutableSet().apply {
            affectedPaths.forEach { oldSelection ->
                remove(oldSelection)
                add(remapPath(oldSelection, oldPath, newPath))
            }
        }
    }

    fun remapPath(path: String, oldPath: String, newPath: String): String {
        return if (isPathInDirectory(path, oldPath)) {
            newPath + path.removePrefix(oldPath)
        } else {
            path
        }
    }

    fun underDirectory(paths: Set<String>, directoryPath: String): Set<String> =
        paths.filterNot { isPathInDirectory(it, directoryPath) }.toSet()

    fun isPathInDirectory(path: String, directoryPath: String): Boolean =
        path == directoryPath || path.startsWith(directoryPrefix(directoryPath))

    private fun directoryPrefix(path: String): String {
        val separator = if (path.startsWith('/') || '\\' !in path) '/' else '\\'
        return if (path.endsWith(separator) || path.endsWith('/')) path else "$path$separator"
    }
}
