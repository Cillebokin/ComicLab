package com.example.comiclab

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CommonFunc {

    fun formatFileSize(size: Long): String {
        if (size <= 0L) {
            return "0 B"
        }

        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024

        return when {
            size < kb -> "$size B"
            size < mb -> String.format("%.1f KB", size / kb)
            size < gb -> String.format("%.1f MB", size / mb)
            else -> String.format("%.1f GB", size / gb)
        }
    }

    fun formatDate(timeMillis: Long): String {
        val formatter = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        return formatter.format(Date(timeMillis))
    }

    fun extractStartMarker(source: String, errorMarkerText: String): String {
        if (source.isBlank()) {
            return ""
        }

        val errorMarkers = errorMarkerText
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var searchStart = 0
        while (searchStart < source.length) {
            val openIndex = source.indexOf('[', searchStart)
            if (openIndex < 0) {
                return ""
            }

            val closeIndex = source.indexOf(']', openIndex + 1)
            if (closeIndex < 0) {
                return ""
            }

            val marker = source.substring(openIndex + 1, closeIndex).trim()
            if (marker.isNotEmpty() && errorMarkers.none { marker.contains(it) }) {
                return marker
            }

            searchStart = closeIndex + 1
        }

        return ""
    }
}
