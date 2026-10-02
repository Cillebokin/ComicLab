package com.example.comiclab

import java.io.File
import java.util.Locale

internal enum class DirectorySortOrder {
    NAME_ASC,
    NAME_DESC,
    MODIFIED_ASC,
    MODIFIED_DESC,
    SIZE_ASC,
    SIZE_DESC
}

internal data class DirectorySortMetadata(
    val isDirectory: Boolean,
    val name: String,
    val modifiedAt: Long,
    val sizeBytes: Long
)

internal object DirectorySortPolicy {

    fun sort(
        files: List<File>,
        order: DirectorySortOrder,
        metadataReader: (File) -> DirectorySortMetadata = { file -> readMetadata(file, order) }
    ): List<File> {
        return files
            .map { file -> file to metadataReader(file) }
            .sortedWith { left, right ->
                val (leftFile, leftMetadata) = left
                val (rightFile, rightMetadata) = right
                val typeComparison = leftMetadata.isDirectory.compareTo(rightMetadata.isDirectory)
                if (typeComparison != 0) {
                    return@sortedWith -typeComparison
                }

                val valueComparison = when (order) {
                    DirectorySortOrder.NAME_ASC -> compareNames(leftMetadata.name, rightMetadata.name)
                    DirectorySortOrder.NAME_DESC -> compareNames(rightMetadata.name, leftMetadata.name)
                    DirectorySortOrder.MODIFIED_ASC ->
                        leftMetadata.modifiedAt.compareTo(rightMetadata.modifiedAt)

                    DirectorySortOrder.MODIFIED_DESC ->
                        rightMetadata.modifiedAt.compareTo(leftMetadata.modifiedAt)

                    DirectorySortOrder.SIZE_ASC ->
                        leftMetadata.sizeBytes.compareTo(rightMetadata.sizeBytes)

                    DirectorySortOrder.SIZE_DESC ->
                        rightMetadata.sizeBytes.compareTo(leftMetadata.sizeBytes)
                }
                valueComparison.takeIf { it != 0 }
                    ?: compareNames(leftMetadata.name, rightMetadata.name)
            }
            .map { it.first }
    }

    private fun readMetadata(file: File, order: DirectorySortOrder): DirectorySortMetadata {
        val isDirectory = runCatching { file.isDirectory }.getOrDefault(false)
        val modifiedAt = if (order.isModifiedOrder()) {
            runCatching { file.lastModified() }.getOrDefault(0L)
        } else {
            0L
        }
        val sizeBytes = if (order.isSizeOrder() && !isDirectory &&
            runCatching { file.isFile }.getOrDefault(false)
        ) {
            runCatching { file.length() }.getOrDefault(0L)
        } else {
            0L
        }
        return DirectorySortMetadata(
            isDirectory = isDirectory,
            name = file.name,
            modifiedAt = modifiedAt,
            sizeBytes = sizeBytes
        )
    }

    private fun compareNames(left: String, right: String): Int {
        return left.lowercase(Locale.ROOT).compareTo(right.lowercase(Locale.ROOT))
            .takeIf { it != 0 }
            ?: left.compareTo(right)
    }

    private fun DirectorySortOrder.isModifiedOrder(): Boolean =
        this == DirectorySortOrder.MODIFIED_ASC || this == DirectorySortOrder.MODIFIED_DESC

    private fun DirectorySortOrder.isSizeOrder(): Boolean =
        this == DirectorySortOrder.SIZE_ASC || this == DirectorySortOrder.SIZE_DESC
}
