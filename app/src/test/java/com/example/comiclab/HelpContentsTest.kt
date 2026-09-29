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
    fun featureMarkerArticleIsRegisteredAsAnInternalHtmlPage() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Manifest should exist", manifestFile.isFile)
        assertTrue(
            manifestFile.readText().contains(
                "android:name=\"com.example.comiclab.HelpArticleActivity\""
            )
        )

        val htmlFile = File("src/main/res/raw/help_start_marker.html")
        assertTrue("Feature marker HTML should exist", htmlFile.isFile)
        val html = htmlFile.readText()
        assertTrue(html.contains("<html"))
        assertTrue(html.contains("特征标记"))
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
        assertTrue(html.contains("特征标记错误标识"))
        assertTrue(html.contains("设置 → 文件浏览 → 特征标记错误标识"))
        assertTrue(html.contains("中;汉;漢;翻;译;譯"))
        assertTrue(html.contains("[中] [ABC]作品名称.zip"))
    }

    @Test
    fun comicClassificationAndMigrationArticlesAreRegisteredWithCurrentGuidance() {
        val articleIds = HelpArticles.all.map { it.id }
        assertTrue(articleIds.contains("comic_classification"))
        assertTrue(articleIds.contains("comic_migration"))

        val classificationFile = File("src/main/res/raw/help_comic_classification.html")
        assertTrue("Comic classification HTML should exist", classificationFile.isFile)
        val classificationHtml = classificationFile.readText()
        assertTrue(classificationHtml.contains("特征标记"))
        assertTrue(classificationHtml.contains("ClassifyNList000/ABC/[ABC]作品名称.zip"))
        assertTrue(classificationHtml.contains("原文件会保留"))

        val migrationFile = File("src/main/res/raw/help_comic_migration.html")
        assertTrue("Comic migration HTML should exist", migrationFile.isFile)
        val migrationHtml = migrationFile.readText()
        assertTrue(migrationHtml.contains("特征标记"))
        assertTrue(migrationHtml.contains("默认存放位置"))
        assertTrue(migrationHtml.contains("原文件仍留在源位置"))
        assertTrue(migrationHtml.contains("已经有同名文件"))
        assertTrue(migrationHtml.contains("不会覆盖"))
    }

    @Test
    fun comicMergeAndFileMergeArticlesExplainWhatTheyIncludeAndKeepOriginals() {
        val articleIds = HelpArticles.all.map { it.id }
        assertTrue(articleIds.contains("comic_merge"))
        assertTrue(articleIds.contains("file_merge"))

        val comicMergeFile = File("src/main/res/raw/help_comic_merge.html")
        assertTrue("Comic merge HTML should exist", comicMergeFile.isFile)
        val comicMergeHtml = comicMergeFile.readText()
        assertTrue(comicMergeHtml.contains("当前文件夹"))
        assertTrue(comicMergeHtml.contains("不会查找子文件夹"))
        assertTrue(comicMergeHtml.contains("原压缩包会保留"))
        assertTrue(comicMergeHtml.contains("ComicLab_merged_"))

        val fileMergeFile = File("src/main/res/raw/help_file_merge.html")
        assertTrue("File merge HTML should exist", fileMergeFile.isFile)
        val fileMergeHtml = fileMergeFile.readText()
        assertTrue(fileMergeHtml.contains("下一级子文件夹"))
        assertTrue(fileMergeHtml.contains("不会继续进入更深的文件夹"))
        assertTrue(fileMergeHtml.contains("原图片会保留"))
        assertTrue(fileMergeHtml.contains("ComicLab_file_merged_"))
    }
}
