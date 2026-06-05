package com.example.comiclab

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FavoritePathAdapter(
    private val context: Context,
    private val items: MutableList<FavoritePathStore.Item>,
    private val onItemClick: (FavoritePathStore.Item) -> Unit,
    private val onItemsEmptyChanged: (Boolean) -> Unit
) : RecyclerView.Adapter<FavoritePathAdapter.PathViewHolder>() {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.directory?.absolutePath?.hashCode()?.toLong()
            ?: RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PathViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_path, parent, false)
        return PathViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PathViewHolder, position: Int) {
        val item = items[position]
        val directory = item.directory

        holder.tvName.text = directory.name.ifBlank { directory.absolutePath }
        holder.tvPath.text = directory.absolutePath
        holder.content.setOnClickListener {
            items.getOrNull(holder.bindingAdapterPosition)?.let(onItemClick)
        }
        holder.btnOptions.setOnClickListener { anchor ->
            showItemOptionsMenu(anchor, holder.bindingAdapterPosition)
        }
    }

    fun clearItems() {
        items.clear()
        notifyDataSetChanged()
        onItemsEmptyChanged(true)
    }

    private fun showItemOptionsMenu(anchor: View, position: Int) {
        val item = items.getOrNull(position) ?: return

        RoundedPopupMenu.show(
            context = context,
            anchor = anchor,
            widthDp = 148,
            items = listOf(
                RoundedPopupMenu.Item(context.getString(R.string.delete_path)) {
                    removeItem(item)
                }
            )
        )
    }

    private fun removeItem(item: FavoritePathStore.Item) {
        val itemIndex = items.indexOfFirst {
            it.directory.absolutePath == item.directory.absolutePath
        }
        if (itemIndex < 0) {
            return
        }

        FavoritePathStore.remove(context, item.directory)
        items.removeAt(itemIndex)
        notifyItemRemoved(itemIndex)
        onItemsEmptyChanged(items.isEmpty())
    }

    class PathViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val content: View = itemView.findViewById(R.id.layoutFavoritePathContent)
        val imgIcon: ImageView = itemView.findViewById(R.id.imgFavoritePathIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvFavoritePathName)
        val tvPath: TextView = itemView.findViewById(R.id.tvFavoritePathValue)
        val btnOptions: ImageButton = itemView.findViewById(R.id.btnFavoritePathItemOptions)
    }
}
