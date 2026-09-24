package com.example.comiclab

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import java.io.File

class ComicMigrationDirectoryAdapter(
    context: Context,
    private val directories: List<File>
) : ArrayAdapter<File>(context, 0, directories) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_comic_migration_directory, parent, false)
        val directory = directories[position]
        view.findViewById<TextView>(R.id.tvComicMigrationDirectoryName).text = directory.name
        view.findViewById<TextView>(R.id.tvComicMigrationDirectoryPath).text = directory.absolutePath
        return view
    }
}
