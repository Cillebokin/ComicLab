package com.example.comiclab

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import java.io.File

data class FileItem(
    val file: File? = null,
    val isParent: Boolean = false
)

class FileListAdapter(
    context: Context,
    private val items: List<FileItem>
) : ArrayAdapter<FileItem>(context, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.file_item, parent, false)

        val imgIcon = view.findViewById<ImageView>(R.id.imgIcon)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvInfo = view.findViewById<TextView>(R.id.tvInfo)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)

        val item = items[position]

        if (item.isParent) {
            imgIcon.setImageResource(R.drawable.png_back_icon)
            tvName.text = ".."
            tvInfo.text = context.getString(R.string.parent_directory)
            tvDate.text = ""
            return view
        }

        val file = item.file
        if (file == null) {
            imgIcon.setImageResource(R.drawable.png_file_icon)
            tvName.text = context.getString(R.string.unknown_item)
            tvInfo.text = ""
            tvDate.text = ""
            return view
        }

        tvName.text = file.name
        tvDate.text = CommonFunc.formatDate(file.lastModified())

        if (file.isDirectory) {
            imgIcon.setImageResource(R.drawable.png_directory_icon)
            tvInfo.text = context.getString(R.string.item_count, file.listFiles()?.size ?: 0)
            return view
        }

        imgIcon.setImageResource(
            if (ComicArchive.isArchive(file)) R.drawable.png_press_package_icon else R.drawable.png_file_icon
        )
        tvInfo.text = CommonFunc.formatFileSize(file.length())

        return view
    }
}
