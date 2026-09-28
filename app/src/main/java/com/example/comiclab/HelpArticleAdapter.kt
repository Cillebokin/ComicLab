package com.example.comiclab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HelpArticleAdapter(
    private val articles: List<HelpArticle>,
    private val onArticleClick: (HelpArticle) -> Unit
) : RecyclerView.Adapter<HelpArticleAdapter.ArticleViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder {
        return ArticleViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.item_help_article,
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
        holder.bind(articles[position], onArticleClick)
    }

    override fun getItemCount(): Int = articles.size

    class ArticleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvHelpArticleTitle)
        private val arrow: ImageView = itemView.findViewById(R.id.ivHelpArticleArrow)

        fun bind(article: HelpArticle, onArticleClick: (HelpArticle) -> Unit) {
            title.setText(article.titleResId)
            arrow.setColorFilter(itemView.context.getColor(R.color.comiclab_icon))
            itemView.setOnClickListener {
                onArticleClick(article)
            }
        }
    }
}
