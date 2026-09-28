package com.example.comiclab

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpButtonTest {

    @Test
    fun mainLayoutPlacesTheHelpButtonImmediatelyBeforeSettings() {
        val layoutFile = File("src/main/res/layout/activity_main.xml")
        assertTrue("Main layout should exist", layoutFile.isFile)

        val layout = layoutFile.readText()
        val helpIndex = layout.indexOf("android:id=\"@+id/btnHelp\"")
        val settingsIndex = layout.indexOf("android:id=\"@+id/btnOptions\"")
        assertTrue("Help button should exist", helpIndex >= 0)
        assertTrue("Settings button should exist", settingsIndex >= 0)
        assertTrue("Help button should be before settings", helpIndex < settingsIndex)

        val helpStart = layout.lastIndexOf("<ImageButton", helpIndex)
        val helpEnd = layout.indexOf("/>", helpIndex)
        assertTrue("Help button view should be complete", helpStart >= 0 && helpEnd > helpStart)
        val helpView = layout.substring(helpStart, helpEnd)
        assertTrue(helpView.contains("android:src=\"@drawable/png_help_question_icon\""))
        assertTrue(helpView.contains("android:tint=\"@color/comiclab_icon\""))
    }

    @Test
    fun helpActivityAndIconAreRegistered() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Manifest should exist", manifestFile.isFile)
        assertTrue(
            manifestFile.readText().contains(
                "android:name=\"com.example.comiclab.HelpActivity\""
            )
        )
        assertTrue(
            File("src/main/res/drawable-nodpi/png_help_question_icon.png").isFile
        )
        assertTrue(File("src/main/res/layout/activity_help.xml").isFile)
    }
}
