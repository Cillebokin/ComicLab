package com.example.comiclab

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CommonFunc {

    // 格式化文件大小
    fun formatFileSize(size: Long): String {
        if (size <= 0)
            return "0 B"

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

    // 格式化日期
    fun formatDate(timeMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        return sdf.format(Date(timeMillis))
    }

    //==============================================================================================

}