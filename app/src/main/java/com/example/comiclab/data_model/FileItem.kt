package com.example.comiclab

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class FileItem(
    val file: File? = null,
    val docFile: DocumentFile? = null,
    val isParent: Boolean = false
)

class FileListAdapter(
    context: Context,
    private val items: List<FileItem>
) : ArrayAdapter<FileItem>(context, 0, items) {

    // 支持的压缩包后缀
    private val zipExtensions = listOf("zip", "rar", "7z", "tar", "gz")

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.file_item, parent, false)

        val imgIcon = view.findViewById<ImageView>(R.id.imgIcon)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)

        val item = items[position]

        // ========== 返回上一级 ==========
        if (item.isParent) {
            imgIcon.setImageResource(R.drawable.png_back_icon)
            tvName.text = ".."
            tvInfo.text = "返回上一级"
            tvDate.text = ""
            return view
        }

        // ========== File ==========
        item.file?.let { file ->
            tvName.text = file.name
            tvDate.text = CommonFunc.formatDate(file.lastModified())

            if (file.isDirectory) {
                imgIcon.setImageResource(R.drawable.png_directory_icon)
                val count = file.listFiles()?.size ?: 0
                tvInfo.text = "$count 项"
            } else {
                if (isCompressed(file.name)) {
                    imgIcon.setImageResource(R.drawable.png_press_package_icon)
                } else {
                    imgIcon.setImageResource(R.drawable.png_file_icon)
                }
                tvInfo.text = CommonFunc.formatFileSize(file.length())
            }
            return view
        }

        // ========== DocumentFile (SAF) ==========
        item.docFile?.let { doc ->
            tvName.text = doc.name ?: ""
            tvDate.text = "" // SAF 默认无法获取 lastModified

            if (doc.isDirectory) {
                imgIcon.setImageResource(R.drawable.png_directory_icon)
                val count = doc.listFiles().size
                tvInfo.text = "$count 项"
            } else {
                if (isCompressed(doc.name)) {
                    imgIcon.setImageResource(R.drawable.png_press_package_icon)
                } else {
                    imgIcon.setImageResource(R.drawable.png_file_icon)
                }
                tvInfo.text = "文件"
            }
            return view
        }

        // ========== 都为空的占位 ==========
        tvName.text = "未知"
        tvInfo.text = ""
        tvDate.text = ""
        imgIcon.setImageResource(R.drawable.png_file_icon)

        return view
    }

    // 判断是否是压缩包
    private fun isCompressed(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return zipExtensions.contains(ext)
    }
}