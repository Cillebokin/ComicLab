package com.example.comiclab

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class HelpArticleActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_article)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground),
            statusBarColorResId = R.color.comiclab_file_picker_background,
            lightStatusBars = true
        )

        val article = HelpArticles.find(intent.getStringExtra(EXTRA_ARTICLE_ID))
        if (article == null) {
            finish()
            return
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }
        findViewById<TextView>(R.id.tvHelpArticleTitle).setText(article.titleResId)

        webView = findViewById(R.id.webViewHelpArticle)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.webViewClient = WebViewClient()
        webView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            forceDark = WebSettings.FORCE_DARK_AUTO
        }

        val html = resources.openRawResource(article.htmlResId).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        webView.loadDataWithBaseURL(
            "https://comiclab.local/help/",
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ARTICLE_ID = "help_article_id"
    }
}
