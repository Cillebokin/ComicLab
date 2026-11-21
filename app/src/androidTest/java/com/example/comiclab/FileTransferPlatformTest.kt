package com.example.comiclab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.core.transfer.DirectoryTransferOperations
import com.example.comiclab.core.transfer.FileOperationProgress
import com.example.comiclab.core.transfer.FileTransferOperations
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTransferPlatformTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val temporaryFolder = TemporaryFolder(context.cacheDir)

    @Test
    fun sameVolumeCutsMoveFileAndDirectoryWithoutCopying() {
        val source = temporaryFolder.newFolder("source")
        val destination = temporaryFolder.newFolder("destination")
        val file = File(source, "漫画📚.cbz").apply { writeText("sample") }
        val directory = File(source, "画册").apply { check(mkdir()) }
        File(directory, ".hidden.cbz").writeText("hidden")
        val phases = mutableListOf<FileOperationProgress.Phase>()

        assertEquals(
            FileTransferOperations.Result.SUCCESS,
            FileTransferOperations.transfer(file, File(destination, file.name), moveSource = true) {
                phases += it.phase
            }
        )
        assertEquals(
            DirectoryTransferOperations.Result.SUCCESS,
            DirectoryTransferOperations.transfer(directory, destination, directory.name, moveSource = true) {
                phases += it.phase
            }
        )
        assertFalse(phases.contains(FileOperationProgress.Phase.COPYING))
        assertTrue(source.listFiles().orEmpty().isEmpty())
        assertEquals("sample", File(destination, file.name).readText())
        assertEquals("hidden", File(destination, "${directory.name}/.hidden.cbz").readText())
    }

    @Test
    fun sameVolumeCutDoesNotReplaceTargetCreatedImmediatelyBeforeMove() {
        val source = temporaryFolder.newFile("source.cbz").apply { writeText("source") }
        val target = File(temporaryFolder.root, "target.cbz")

        val result = FileTransferOperations.transfer(source, target, moveSource = true) {
            if (it.phase == FileOperationProgress.Phase.MOVING) target.writeText("existing")
        }

        assertEquals(FileTransferOperations.Result.TARGET_EXISTS, result)
        assertEquals("existing", target.readText())
        assertEquals("source", source.readText())
    }

    @Test
    fun crossVolumeCutsVerifyCopyAndRemoveUnchangedSource() {
        val source = temporaryFolder.newFolder("source")
        val directory = File(source, "folder").apply { check(mkdir()) }
        val file = File(source, "source.cbz").apply { writeText("source") }
        File(directory, "chapter.cbz").writeText("chapter")
        val external = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val destination = Files.createTempDirectory(external.toPath(), "cl001-test-").toFile()
        try {
            assertEquals(
                FileTransferOperations.Result.SUCCESS,
                FileTransferOperations.transfer(file, File(destination, file.name), moveSource = true)
            )
            assertEquals(
                DirectoryTransferOperations.Result.SUCCESS,
                DirectoryTransferOperations.transfer(directory, destination, directory.name, moveSource = true)
            )
            assertFalse(file.exists())
            assertEquals("source", File(destination, file.name).readText())
            assertFalse(directory.exists())
            assertEquals("chapter", File(destination, "folder/chapter.cbz").readText())
        } finally {
            check(destination.canonicalFile.parentFile == external && destination.name.startsWith("cl001-test-"))
            check(destination.deleteRecursively())
        }
    }
}
