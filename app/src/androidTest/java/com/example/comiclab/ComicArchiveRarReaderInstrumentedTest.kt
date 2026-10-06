package com.example.comiclab

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComicArchiveRarReaderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val temporaryFolder = TemporaryFolder(instrumentation.targetContext.cacheDir)

    @Test
    fun rarAndCbrReaderSessionsDecodeImagesAndCloseNativeResources() {
        listOf("rar/rar3-image.rar", "rar/rar5-image.cbr").forEach { fixturePath ->
            val archive = copyFixture(fixturePath)
            val cacheRoot = File(
                temporaryFolder.root,
                "${archive.nameWithoutExtension}-cache"
            ).apply { mkdirs() }

            assertTrue("Unsupported archive: ${archive.name}", ComicArchive.isSupportedArchive(archive))
            assertEquals(
                listOf("testfile.jpg", "testfile.png"),
                ComicArchive.imageEntries(archive)
            )
            assertNoOpenFileDescriptors(archive)

            val session = ComicArchive.openReaderSession(archive, cacheRoot)
            try {
                assertFalse(session.isPdfSource())
                listOf("testfile.jpg", "testfile.png").forEach { entryName ->
                    val bounds = session.readBounds(entryName)
                    assertNotNull("Missing image bounds for $entryName in ${archive.name}", bounds)
                    assertNoOpenFileDescriptors(archive)
                    val bitmap = session.decodeImageForPage(
                        entryName = entryName,
                        targetWidth = bounds!!.width,
                        targetHeight = bounds.height
                    )
                    assertNotNull("Image decode failed for $entryName in ${archive.name}", bitmap)
                    try {
                        assertEquals(bounds.width, bitmap!!.width)
                        assertEquals(bounds.height, bitmap.height)
                    } finally {
                        bitmap?.recycle()
                    }
                    assertNoOpenFileDescriptors(archive)
                }
            } finally {
                session.close()
                session.close()
            }

            assertNoOpenFileDescriptors(archive)
            assertTrue("Reader cache was not cleaned for ${archive.name}", cacheRoot.listFiles().orEmpty().isEmpty())
        }
    }

    private fun copyFixture(assetPath: String): File {
        val archive = File(temporaryFolder.root, assetPath.substringAfterLast('/'))
        instrumentation.context.assets.open(assetPath).use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        return archive
    }

    private fun hasOpenFileDescriptor(file: File): Boolean {
        val canonical = file.canonicalFile
        val descriptors = checkNotNull(File("/proc/self/fd").listFiles()) {
            "Cannot inspect this process's file descriptors"
        }
        return descriptors.any { descriptor ->
            runCatching { File(Os.readlink(descriptor.absolutePath)).canonicalFile == canonical }
                .getOrDefault(false)
        }
    }

    private fun assertNoOpenFileDescriptors(file: File) {
        assertFalse(
            "Archive file descriptor still open for ${file.name}",
            hasOpenFileDescriptor(file)
        )
    }
}
