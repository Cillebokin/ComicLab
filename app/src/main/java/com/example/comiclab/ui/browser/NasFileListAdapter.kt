package com.example.comiclab.ui.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.CommonFunc
import com.example.comiclab.R
import com.example.comiclab.core.nas.NasDirectoryEntry

internal class NasFileListAdapter(
    context: Context,
    private val items: MutableList<NasDirectoryEntry>
) : ArrayAdapter<NasDirectoryEntry>(context, 0, items) {
    private val inflater = LayoutInflater.from(context)

    fun replaceItems(newItems: List<NasDirectoryEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.file_item, parent, false)
        val item = items[position]
        val icon = view.findViewById<ImageView>(R.id.imgIcon)
        val collectionCoverList = view.findViewById<RecyclerView>(R.id.collectionCoverList)
        val favoriteMarker = view.findViewById<ImageView>(R.id.imgFavoriteMarker)
        val name = view.findViewById<TextView>(R.id.tvName)
        val info = view.findViewById<TextView>(R.id.tvInfo)
        val type = view.findViewById<TextView>(R.id.tvTypeMarker)
        val date = view.findViewById<TextView>(R.id.tvDate)

        collectionCoverList.visibility = View.GONE
        favoriteMarker.visibility = View.GONE
        icon.visibility = View.VISIBLE
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        icon.setColorFilter(context.getColor(R.color.comiclab_icon))
        icon.setImageResource(
            when {
                item.isParent -> R.drawable.ic_lucide_corner_up_left
                item.isDirectory -> R.drawable.ic_lucide_folder
                else -> R.drawable.ic_lucide_file
            }
        )
        name.text = if (item.isParent) ".." else item.name
        type.text = if (item.isDirectory) "(D)" else "(F)"
        info.text = when {
            item.isParent -> context.getString(R.string.parent_directory)
            item.isDirectory -> context.getString(R.string.nas_directory)
            else -> CommonFunc.formatFileSize(item.sizeBytes)
        }
        date.text = if (item.modifiedAt > 0L) {
            CommonFunc.formatDate(item.modifiedAt)
        } else {
            ""
        }
        return view
    }
}
