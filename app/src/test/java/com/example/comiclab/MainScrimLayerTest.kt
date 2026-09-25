package com.example.comiclab

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MainScrimLayerTest {

    @Test
    fun scrimDrawsAboveEveryElevatedMainContentCard() {
        val layout = parseXml("src/main/res/layout/activity_main.xml")
        val dimensions = parseXml("src/main/res/values/dimens.xml")
            .documentElement
            .childElements()
            .filter { it.tagName == "dimen" }
            .associate { it.getAttribute("name") to it.textContent.trim() }
        val rootChildren = layout.documentElement.childElements()
        val scrim = rootChildren.single {
            it.getAttributeNS(ANDROID_NAMESPACE, "id") == "@+id/readingHistoryScrim"
        }
        assertTrue(
            "Scrim must declare an elevation above the main content cards",
            scrim.getAttributeNS(ANDROID_NAMESPACE, "elevation").isNotBlank()
        )
        val scrimElevation = elevationDp(scrim, dimensions)
        val contentElevations = rootChildren
            .filter { it !== scrim }
            .mapNotNull { child ->
                child.getAttributeNS(ANDROID_NAMESPACE, "elevation")
                    .takeIf(String::isNotBlank)
                    ?.let { elevationDp(child, dimensions) }
            }

        assertTrue("Expected elevated main content cards", contentElevations.isNotEmpty())
        assertTrue(
            "Scrim elevation must exceed every main content card elevation",
            scrimElevation > contentElevations.max()
        )
    }

    private fun parseXml(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File(path))

    private fun Element.childElements(): List<Element> = buildList {
        for (index in 0 until childNodes.length) {
            (childNodes.item(index) as? Element)?.let(::add)
        }
    }

    private fun elevationDp(view: Element, dimensions: Map<String, String>): Float {
        val value = view.getAttributeNS(ANDROID_NAMESPACE, "elevation")
        val dimension = if (value.startsWith("@dimen/")) {
            dimensions[value.removePrefix("@dimen/")]
                ?: error("Missing dimension resource: $value")
        } else {
            value
        }
        return dimension.removeSuffix("dp").toFloat()
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
