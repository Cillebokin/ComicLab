package com.example.comiclab

import android.app.AlertDialog
import android.content.Intent
import android.widget.ListView
import com.example.comiclab.core.migration.ComicMigrationCopyStatus
import com.example.comiclab.core.migration.ComicMigrationPlanner
import com.example.comiclab.core.organize.ComicClassifier
import com.example.comiclab.core.organize.ComicMergeOperations
import com.example.comiclab.core.organize.ComicMergeResult
import com.example.comiclab.core.organize.DirectorySimilarityScanner
import com.example.comiclab.core.organize.ImageFileMergeOperations
import com.example.comiclab.core.organize.ImageFileMergeResult
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.privacy.PrivacyAccessGuard
import com.example.comiclab.ui.privacy.PrivacySessionManager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class OrganizeMigrationPrivacyRobolectricTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun lock() {
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test fun ordinaryClassificationAndSimilarityReportSkipMarkedSubtrees() {
        val root = storageRoot()
        val ordinary = folder(root, "Author")
        val hidden = folder(root, "Author copy").also(::mark)
        File(ordinary, "ordinary.cbz").writeText("ordinary")
        File(hidden, "private.cbz").writeText("private")

        val report = DirectorySimilarityScanner.createReport(root,
            formatMessage = { type, args -> "$type ${args.joinToString()}" }, canUsePath = ::ordinaryPath) {}
        assertEquals(1, report.directoryCount)
        assertFalse(report.outputFile.readText().contains(hidden.path))
        assertTrue(report.outputFile.delete())

        val classified = ComicClassifier.classify(root, "", ::ordinaryPath) {}
        assertEquals(1, classified.copiedCount)
        assertEquals(1, classified.totalCount)
        val files = classified.outputDirectory!!.walk().filter(File::isFile).toList()
        assertEquals(listOf("ordinary.cbz"), files.map(File::getName))
        assertEquals("ordinary", files.single().readText())
    }

    @Test fun imageMergeSkipsMarkedDirectoryAndRechecksGeneratedDestination() {
        val root = storageRoot()
        File(folder(root, "ordinary scans"), "page.png").writeText("ordinary")
        File(folder(root, "private scans").also(::mark), "page.png").writeText("private")

        val merged = ImageFileMergeOperations.merge(root, canUsePath = ::ordinaryPath) {} as ImageFileMergeResult.Completed
        assertEquals(1, merged.folderCount)
        assertEquals("ordinary", merged.outputDirectory!!.listFiles()!!.single().readText())

        val denied = ImageFileMergeOperations.merge(root, canUsePath = {
            ordinaryPath(it) && !it.name.startsWith("ComicLab_file_merged_")
        }) {}
        assertEquals(ImageFileMergeResult.Failed(ImageFileMergeResult.Failure.OUTPUT_DIRECTORY_CREATION), denied)
    }

    @Test fun archiveMergeStopsPublicationWhenSourceBecomesPrivateDuringExtraction() {
        val root = storageRoot()
        val archive = File(root, "sample.cbz")
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry("page.png"))
            it.write(byteArrayOf(1))
        }
        val merged = ComicMergeOperations.merge(root, extractEntryToFile = { _, _, staged ->
            staged.writeText("prepared")
            mark(root)
            true
        }, canUsePath = ::ordinaryPath) {} as ComicMergeResult.Completed
        assertEquals(0, merged.copiedCount)
        assertTrue(merged.outputDirectory!!.listFiles()!!.isEmpty())
    }

    @Test fun migrationSkipsPrivateSourcesAndTargetsAndRechecksAtPublication() {
        val root = storageRoot()
        val source = folder(root, "source")
        val ordinary = File(source, "[Author] ordinary.cbz").apply { writeText("ordinary") }
        File(folder(source, "hidden").also(::mark), "private.cbz").writeText("private")
        val destination = folder(root, "destination")
        val publicTarget = folder(destination, "Author")
        val privateTarget = folder(destination, "Author private").also(::mark)
        val examined = mutableListOf<File>()
        val planner = ComicMigrationPlanner(allowFileCopy = true, canUsePath = ::ordinaryPath) {
            examined.add(it)
            true
        }

        val sources = planner.scanComicFilesRecursively(source)
        assertEquals(listOf(ordinary), sources)
        assertEquals(listOf(ordinary), examined)
        val plan = planner.prepareTargetPlans(sources, destination, destination, source, markerForFile = { "Author" }).single()
        assertTrue(publicTarget in plan.directories)
        assertFalse(privateTarget in plan.directories)
        assertEquals(ComicMigrationCopyStatus.COPIED, planner.copyComicFile(ordinary, publicTarget).status)
        assertEquals("ordinary", File(publicTarget, ordinary.name).readText())
        assertEquals(ComicMigrationCopyStatus.FAILED, planner.copyComicFile(ordinary, privateTarget).status)
        assertTrue(privateTarget.listFiles()!!.isEmpty())

        val changedTarget = folder(destination, "becomes private")
        val result = planner.copyComicFile(ordinary, changedTarget) {
            if (changedTarget.listFiles()!!.any { it.name.endsWith(".part") }) mark(changedTarget)
            false
        }
        assertEquals(ComicMigrationCopyStatus.FAILED, result.status)
        assertTrue(changedTarget.listFiles()!!.isEmpty())
    }

    @Test fun migrationPickerHidesPrivateFoldersAndActivityRejectsPrivateSourcesAndSessions() {
        val root = storageRoot()
        val source = folder(root, "ordinary source")
        val destination = folder(root, "ordinary target")
        val hidden = folder(root, "private").also(::mark)
        val ordinary = Robolectric.buildActivity(ComicMigrationActivity::class.java, migrationIntent(source)).setup()
        try {
            val dialog: AlertDialog = checkNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val list = dialog.findViewById<ListView>(R.id.listComicMigrationPickerDirectories)
            val items = (0 until list.count).map { list.getItemAtPosition(it) }
            assertTrue(destination in items)
            assertFalse(hidden in items)
        } finally { ordinary.pause().stop().destroy() }

        val deniedSource = Robolectric.buildActivity(ComicMigrationActivity::class.java, migrationIntent(hidden)).setup()
        try { assertTrue(deniedSource.get().isFinishing) } finally { deniedSource.pause().stop().destroy() }
        assertTrue(PrivacySessionManager.enterPrivacySpace(root.path, PrivacySessionManager.sessionEpoch))
        val deniedSession = Robolectric.buildActivity(ComicMigrationActivity::class.java, migrationIntent(source)).setup()
        try { assertTrue(deniedSession.get().isFinishing) } finally { deniedSession.pause().stop().destroy() }
    }

    private fun migrationIntent(source: File) = Intent(context, ComicMigrationActivity::class.java)
        .putExtra(ComicMigrationActivity.EXTRA_SOURCE_DIRECTORY_PATH, source.path)

    private fun ordinaryPath(file: File): Boolean = PrivacyAccessGuard.resolveLocalPathInMode(context, file, false) != null

    private fun mark(directory: File) { assertTrue(PrivacyStore.addPrivacyDirectory(context, directory)) }

    private fun storageRoot(): File = temporaryFolder.newFolder("storage").also {
        ShadowEnvironment.setExternalStorageDirectory(it.toPath())
    }

    private fun folder(parent: File, name: String): File = File(parent, name).apply { check(mkdir()) }
}
