package com.example.comiclab

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
        val oldPrefix = directoryPrefix(oldPath)
        val affectedPaths = paths.filter { path ->
            path == oldPath || path.startsWith(oldPrefix)
        }
        if (affectedPaths.isEmpty()) {
            return paths.toSet()
        }

        return paths.toMutableSet().apply {
            affectedPaths.forEach { oldSelection ->
                remove(oldSelection)
                add(newPath + oldSelection.removePrefix(oldPath))
            }
        }
    }

    fun underDirectory(paths: Set<String>, directoryPath: String): Set<String> {
        val prefix = directoryPrefix(directoryPath)
        return paths.filterNot { path ->
            path == directoryPath || path.startsWith(prefix)
        }.toSet()
    }

    private fun directoryPrefix(path: String): String {
        val separator = if ('\\' in path) '\\' else '/'
        return if (path.endsWith('/') || path.endsWith('\\')) path else "$path$separator"
    }
}
