package com.example.comiclab.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.R

data class SettingsSection(
    @StringRes val titleResId: Int,
    @LayoutRes val layoutResId: Int
)

class SettingsSectionAdapter(
    private val sections: List<SettingsSection>,
    private val onSectionClick: (Int) -> Unit
) : RecyclerView.Adapter<SettingsSectionAdapter.SectionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(
            R.layout.item_settings_section,
            parent,
            false
        )
        return SectionViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        holder.bind(sections[position], onSectionClick)
    }

    override fun getItemCount(): Int = sections.size

    class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvSettingsSectionTitle)
        private val arrow: ImageView = itemView.findViewById(R.id.ivSettingsSectionArrow)

        fun bind(
            section: SettingsSection,
            onSectionClick: (Int) -> Unit
        ) {
            title.setText(section.titleResId)
            arrow.setColorFilter(itemView.context.getColor(R.color.comiclab_icon))
            itemView.setOnClickListener {
                val currentPosition = bindingAdapterPosition
                if (currentPosition != RecyclerView.NO_POSITION) {
                    onSectionClick(currentPosition)
                }
            }
        }
    }
}
