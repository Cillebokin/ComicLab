package com.example.comiclab

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLinkTest {

    @Test
    fun releasesLinkUsesTheRequestedUrl() {
        assertEquals(
            "https://github.com/Cillebokin/ComicLab/releases",
            COMICLAB_RELEASES_URL
        )
    }

    @Test
    fun releasesLinkLayoutAllowsWrappedText() {
        val layoutFile = File("src/main/res/layout/activity_settings.xml")
        assertTrue("Settings layout should exist", layoutFile.isFile)

        val layout = layoutFile.readText()
        val linkId = "android:id=\"@+id/tvAppLink\""
        val linkStart = layout.indexOf(linkId)
        assertTrue("Release link view should exist", linkStart >= 0)

        val viewStart = layout.lastIndexOf("<TextView", linkStart)
        val viewEnd = layout.indexOf("/>", linkStart)
        assertTrue("Release link view should be complete", viewStart >= 0 && viewEnd > viewStart)

        val linkView = layout.substring(viewStart, viewEnd)
        assertTrue(
            "Release link view should use wrap_content height",
            linkView.contains("android:layout_height=\"wrap_content\"")
        )
    }
}
