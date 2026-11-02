package com.example.comiclab.ebook.epub

import android.net.Uri

internal object EpubPath {

    fun resolve(baseFile: String, reference: String): String? {
        val cleanedReference = reference.trim()
            .substringBefore('#')
            .substringBefore('?')
        if (cleanedReference.isBlank() || isExternalReference(cleanedReference)) {
            return null
        }

        val decodedReference = Uri.decode(cleanedReference).replace('\\', '/')
        val baseDirectory = baseFile.substringBeforeLast('/', "")
        val combined = if (decodedReference.startsWith('/')) {
            decodedReference.removePrefix("/")
        } else if (baseDirectory.isBlank()) {
            decodedReference
        } else {
            "$baseDirectory/$decodedReference"
        }
        return normalizeDecoded(combined)
    }

    fun normalize(path: String): String? = normalizeDecoded(Uri.decode(path))

    private fun normalizeDecoded(path: String): String? {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast() else return null
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/").takeIf(String::isNotEmpty)
    }

    private fun isExternalReference(reference: String): Boolean {
        return reference.startsWith("//") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(reference)
    }
}
