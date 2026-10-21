package com.example.comiclab

import android.content.ComponentName
import androidx.core.content.FileProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileProviderBoundaryTest {

    private val context = RuntimeEnvironment.getApplication()
    private val authority = "${context.packageName}.fileprovider"

    @Test
    fun filePathsMapOnlySharedStorageAndCrashExportSubdirectory() {
        val parser = context.resources.getXml(R.xml.file_paths)
        val paths = mutableListOf<String>()
        try {
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                if (parser.name == "paths") continue
                fun attribute(name: String): String? =
                    (0 until parser.attributeCount)
                        .firstOrNull { parser.getAttributeName(it) == name }
                        ?.let { parser.getAttributeValue(it) }
                val name = attribute("name")
                val path = attribute("path")
                paths += "${parser.name}:$name:$path"
            }
        } finally {
            parser.close()
        }

        assertEquals(
            listOf(
                "external-path:external_storage:.",
                "cache-path:crash_exports:crash_exports/"
            ),
            paths
        )
    }

    @Test
    fun onlyCrashExportCacheSubdirectoryIsMappedForPrivateFiles() {
        val exportedLog = File(context.cacheDir, "crash_exports/ComicLab_crash_latest.txt")
        val uri = FileProvider.getUriForFile(context, authority, exportedLog)
        assertEquals("content", uri.scheme)

        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(
                context,
                authority,
                File(context.filesDir, "crash_logs/private.txt")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(
                context,
                authority,
                File(context.cacheDir, "private.txt")
            )
        }
    }

    @Test
    fun providerRemainsNonExportedAndAllowsUriGrants() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context, FileProvider::class.java),
            0
        )

        assertNotNull(provider)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }
}
