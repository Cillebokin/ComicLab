package com.example.comiclab

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpContentsTest {

    @Test
    fun helpPageHasContentsButtonAndArticleList() {
        val layoutFile = File("src/main/res/layout/activity_help.xml")
        assertTrue("Help layout should exist", layoutFile.isFile)

        val layout = layoutFile.readText()
        assertTrue(layout.contains("android:id=\"@+id/btnContents\""))
        assertTrue(layout.contains("android:id=\"@+id/helpContentsScrim\""))
        assertTrue(
            File("src/main/res/layout/panel_help_contents.xml").isFile
        )
        val panelLayout = File("src/main/res/layout/panel_help_contents.xml")
        assertTrue(panelLayout.readText().contains("@drawable/bg_side_panel_start"))
        assertTrue(
            panelLayout.readText().contains("android:id=\"@+id/listHelpArticles\"")
        )
        assertTrue(
            layout.contains(
                "android:contentDescription=\"@string/help_contents_expand\""
            )
        )
    }

    @Test
    fun startMarkerArticleIsRegisteredAsAnInternalHtmlPage() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Manifest should exist", manifestFile.isFile)
        assertTrue(
            manifestFile.readText().contains(
                "android:name=\"com.example.comiclab.HelpArticleActivity\""
            )
        )

        val htmlFile = File("src/main/res/raw/help_start_marker.html")
        assertTrue("Start marker HTML should exist", htmlFile.isFile)
        val html = htmlFile.readText()
        assertTrue(html.contains("<html"))
        assertTrue(html.contains("起始标记"))
        assertTrue(html.contains("[ABC]作品名称.zip"))
        assertFalse(html.contains("作者"))
    }

    @Test
    fun helpDirectoryContainsErrorTagsArticleWithSettingsGuidance() {
        assertTrue(
            HelpArticles.all.any { it.id == HelpArticles.START_MARKER_ERROR_TAGS_ID }
        )

        val htmlFile = File("src/main/res/raw/help_start_marker_error_tags.html")
        assertTrue("Error tags HTML should exist", htmlFile.isFile)
        val html = htmlFile.readText()
        assertTrue(html.contains("起始标记错误标识"))
        assertTrue(html.contains("设置 → 文件浏览 → 起始标记错误标识"))
        assertTrue(html.contains("中;汉;漢;翻;译;譯"))
        assertTrue(html.contains("[中] [ABC]作品名称.zip"))
    }
}
