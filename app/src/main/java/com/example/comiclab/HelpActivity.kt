package com.example.comiclab

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class HelpActivity : AppCompatActivity() {

    private lateinit var btnContents: ImageButton
    private lateinit var tvCurrentHelpArticleTitle: TextView
    private lateinit var helpContentsScrim: View
    private lateinit var webView: WebView
    private var helpContentsPopupWindow: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        SystemBars.fitContentBelowSystemBars(
            this,
            findViewById<View>(R.id.main),
            findViewById<View>(R.id.statusBarBackground),
            statusBarColorResId = R.color.comiclab_file_picker_background,
            lightStatusBars = true
        )

        btnContents = findViewById(R.id.btnContents)
        tvCurrentHelpArticleTitle = findViewById(R.id.tvCurrentHelpArticleTitle)
        helpContentsScrim = findViewById(R.id.helpContentsScrim)
        webView = findViewById(R.id.webViewHelpContent)
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.webViewClient = WebViewClient()
        webView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            forceDark = WebSettings.FORCE_DARK_AUTO
        }
        HelpArticles.all.firstOrNull()?.let(::showArticle)

        helpContentsScrim.setOnClickListener {
            dismissContentsPanel()
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (helpContentsPopupWindow != null) {
                dismissContentsPanel()
            } else {
                finish()
            }
        }

        btnContents.setOnClickListener {
            if (helpContentsPopupWindow == null) {
                showContentsPanel()
            } else {
                dismissContentsPanel()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (helpContentsPopupWindow != null) {
                    dismissContentsPanel()
                } else {
                    finish()
                }
            }
        })
    }

    private fun showContentsPanel() {
        if (helpContentsPopupWindow != null) {
            return
        }

        val rootView = findViewById<View>(R.id.main)
        val screenWidth = rootView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val panelWidth = ((screenWidth * 0.75f).roundToInt())
            .coerceAtLeast(dpToPx(280))
            .coerceAtMost(screenWidth)
        val content = layoutInflater.inflate(R.layout.panel_help_contents, null)
        val listHelpArticles = content.findViewById<RecyclerView>(R.id.listHelpArticles)

        listHelpArticles.layoutManager = LinearLayoutManager(this)
        listHelpArticles.adapter = HelpArticleAdapter(HelpArticles.all) { article ->
            dismissContentsPanel()
            showArticle(article)
        }

        showContentsScrim()
        val popupWindow = PopupWindow(
            content,
            panelWidth,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.animationStyle = R.style.Animation_ComicLab_HelpContentsPanel
        popupWindow.elevation = dpToPx(8).toFloat()
        popupWindow.setOnDismissListener {
            if (helpContentsPopupWindow === popupWindow) {
                helpContentsPopupWindow = null
                hideContentsScrim()
                updateContentsButtonDescription(false)
            }
        }

        helpContentsPopupWindow = popupWindow
        updateContentsButtonDescription(true)
        popupWindow.showAtLocation(rootView, Gravity.START or Gravity.TOP, 0, 0)
    }

    private fun dismissContentsPanel() {
        val popupWindow = helpContentsPopupWindow ?: return
        helpContentsPopupWindow = null
        popupWindow.dismiss()
        hideContentsScrim()
        updateContentsButtonDescription(false)
    }

    private fun showContentsScrim() {
        helpContentsScrim.animate().cancel()
        helpContentsScrim.alpha = 0f
        helpContentsScrim.visibility = View.VISIBLE
        helpContentsScrim.animate()
            .alpha(1f)
            .setDuration(180L)
            .start()
    }

    private fun hideContentsScrim() {
        if (helpContentsScrim.visibility != View.VISIBLE) {
            return
        }

        helpContentsScrim.animate().cancel()
        helpContentsScrim.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction {
                helpContentsScrim.visibility = View.GONE
                helpContentsScrim.alpha = 1f
            }
            .start()
    }

    private fun updateContentsButtonDescription(expanded: Boolean) {
        btnContents.contentDescription = getString(
            if (expanded) {
                R.string.help_contents_collapse
            } else {
                R.string.help_contents_expand
            }
        )
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun showArticle(article: HelpArticle) {
        tvCurrentHelpArticleTitle.setText(article.titleResId)
        val html = resources.openRawResource(article.htmlResId)
            .bufferedReader(Charsets.UTF_8)
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
}
