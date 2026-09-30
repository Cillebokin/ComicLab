package com.example.comiclab

internal data class FileOperationProgress(
    val phase: Phase,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val currentItemName: String? = null
) {
    enum class Phase {
        SCANNING,
        COPYING,
        DELETING_SOURCE,
        DELETING
    }
}

internal object FileOperationProgressPolicy {

    fun percentage(progress: FileOperationProgress): Int? {
        if (progress.phase == FileOperationProgress.Phase.SCANNING ||
            (progress.phase == FileOperationProgress.Phase.DELETING && progress.totalItems <= 0)
        ) {
            return null
        }

        val (completed, total) = if (
            progress.phase == FileOperationProgress.Phase.COPYING && progress.totalBytes > 0
        ) {
            progress.completedBytes to progress.totalBytes
        } else {
            progress.completedItems.toLong() to progress.totalItems.toLong()
        }

        if (total <= 0) {
            return 0
        }

        val percent = ((completed.coerceAtLeast(0).toDouble() / total.toDouble()) * 100)
            .toInt()
            .coerceIn(0, 100)
        return if (
            progress.phase == FileOperationProgress.Phase.COPYING &&
            percent == 100 && progress.completedItems < progress.totalItems
        ) {
            99
        } else {
            percent
        }
    }
}
