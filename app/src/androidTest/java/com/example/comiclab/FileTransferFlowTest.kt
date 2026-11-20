package com.example.comiclab

import com.example.comiclab.core.settings.ComicLabMangaProgress
import android.content.Context
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.ListView
import android.widget.SeekBar
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ebook.EbookReaderProgressMapper
import com.example.comiclab.ui.browser.transfer.FileTransferMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import com.example.comiclab.core.transfer.TransferFileMove
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.hamcrest.Matchers.allOf
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTransferFlowTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val privateFiles = TemporaryFolder(instrumentation.targetContext.cacheDir)
    private lateinit var context: Context
    private var activity: MainActivity? = null
    private var preferences: android.content.SharedPreferences? = null
    private var hadCurrentPath = false
    private var previousCurrentPath: String? = null
    private var hadPermissionPrompted = false
    private var previousPermissionPrompted = false
    private var testRoot: File? = null
    private var sandbox: File? = null
    private var storedPathSandbox: StoredPathPreferenceSandbox? = null
    private var protectedPreferenceSnapshots: Map<String, Map<String, Any?>> = emptyMap()

    @Before
    fun setUp() {
        context = instrumentation.targetContext
        protectedPreferenceSnapshots = PROTECTED_PREFERENCE_NAMES.associateWith { name ->
            snapshotPreferences(name)
        }
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        hadCurrentPath = preferences!!.contains(KEY_CURRENT_PATH)
        previousCurrentPath = preferences!!.getString(KEY_CURRENT_PATH, null)
        hadPermissionPrompted = preferences!!.contains(KEY_STORAGE_PERMISSION_PROMPTED)
        previousPermissionPrompted =
            preferences!!.getBoolean(KEY_STORAGE_PERMISSION_PROMPTED, false)

        testRoot = File(TEST_ROOT_PATH).canonicalFile
        check(testRoot!!.isDirectory) {
            "The requested validation directory must already exist: ${testRoot!!.absolutePath}"
        }
        check(!Files.isSymbolicLink(testRoot!!.toPath())) {
            "The requested validation directory must not be a symbolic link"
        }
        val previousPath = previousCurrentPath?.let(::File)?.canonicalFile?.toPath()
        val validationRootPath = testRoot!!.toPath()
        val previousPathWithinTestRoot = previousPath
            ?.takeIf { it.startsWith(validationRootPath) }
            ?.let(validationRootPath::relativize)
        if (
            previousPathWithinTestRoot != null &&
            previousPathWithinTestRoot.nameCount > 0 &&
            previousPathWithinTestRoot.getName(0).toString().startsWith(TEST_SANDBOX_PREFIX)
        ) {
            // Recover the browser path if a prior instrumentation process was interrupted
            // after setting current_path but before its @After restoration ran.
            previousCurrentPath = testRoot!!.absolutePath
        }

        sandbox = File(
            testRoot,
            "$TEST_SANDBOX_PREFIX${UUID.randomUUID()}"
        ).canonicalFile
        check(sandbox!!.toPath().startsWith(testRoot!!.toPath())) {
            "The test sandbox must remain inside the requested validation directory"
        }
        check(sandbox!!.name.startsWith(TEST_SANDBOX_PREFIX)) {
            "The test sandbox must use its dedicated cleanup prefix"
        }
        check(!sandbox!!.exists()) { "Refusing to reuse an existing test sandbox" }
        check(sandbox!!.mkdirs()) { "Could not create the isolated test sandbox" }
        File(sandbox, SOURCE_DIRECTORY_NAME).mkdirs()
        File(sandbox, DESTINATION_DIRECTORY_NAME).mkdirs()

        check(
            preferences!!.edit()
                .putString(KEY_CURRENT_PATH, sandbox!!.absolutePath)
                .putBoolean(KEY_STORAGE_PERMISSION_PROMPTED, true)
                .commit()
        )
        storedPathSandbox = StoredPathPreferenceSandbox(context)

        activity = launchBrowserActivity()
        awaitListItem(SOURCE_DIRECTORY_NAME)
    }

    @After
    fun tearDown() {
        activity?.let { launchedActivity ->
            instrumentation.runOnMainSync { launchedActivity.finish() }
            instrumentation.waitForIdleSync()
        }

        try {
            preferences?.let { prefs ->
                val editor = prefs.edit()
                if (hadCurrentPath) {
                    editor.putString(KEY_CURRENT_PATH, previousCurrentPath)
                } else {
                    editor.remove(KEY_CURRENT_PATH)
                }
                if (hadPermissionPrompted) {
                    editor.putBoolean(KEY_STORAGE_PERMISSION_PROMPTED, previousPermissionPrompted)
                } else {
                    editor.remove(KEY_STORAGE_PERMISSION_PROMPTED)
                }
                check(editor.commit()) { "Could not restore the original browser preferences" }
            }
        } finally {
            try {
                storedPathSandbox?.restore()
            } finally {
                try {
                    restoreProtectedPreferences()
                } finally {
                    val cleanupRoot = sandbox
                    if (cleanupRoot != null && cleanupRoot.exists()) {
                        val parent = testRoot ?: error("Missing validation test root")
                        check(cleanupRoot.canonicalFile.toPath().startsWith(parent.toPath())) {
                            "Refusing to clean anything outside the validation test directory"
                        }
                        check(cleanupRoot.name.startsWith(TEST_SANDBOX_PREFIX)) {
                            "Refusing to clean a directory without the test-only prefix"
                        }
                        check(cleanupRoot.deleteRecursively()) {
                            "Test cleanup failed; the remaining data is confined to ${cleanupRoot.absolutePath}"
                        }
                    }
                }
            }
        }
    }

    @Test
    fun copyFilePreservesSourceAndCopiesExactBytes() {
        val source = createComicFile(SOURCE_DIRECTORY_NAME, "sample.cbz", "copy bytes")
        val expectedBytes = source.readBytes()

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseFileAction(source.name, R.id.btnCopyFile)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())
        awaitTransferModeEnded()

        assertTrue(source.isFile)
        assertArrayEquals(expectedBytes, File(destinationDirectory(), source.name).readBytes())
    }

    @Test
    fun cutFileTransfersExactBytesAndKeepsSourceWhenMoveIsUnavailable() {
        val source = createComicFile(SOURCE_DIRECTORY_NAME, "sample.cbz", "cut bytes")
        val expectedBytes = source.readBytes()
        val supportsMove = supportsDirectMove(source)

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseFileAction(source.name, R.id.btnCutFile)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())
        awaitTransferModeEnded()

        assertEquals(!supportsMove, source.exists())
        if (!supportsMove) assertArrayEquals(expectedBytes, source.readBytes())
        assertArrayEquals(expectedBytes, File(destinationDirectory(), source.name).readBytes())
    }

    @Test
    fun copyFolderPreservesNestedHiddenAndEmptyEntries() {
        val source = createComicFolder("copy-folder")
        val expectedBytes = File(source, "nested/chapter.cbz").readBytes()
        val expectedHiddenBytes = File(source, ".hidden.cbz").readBytes()

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseDirectoryAction(source.name, R.id.btnCopyDirectory)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())
        awaitTransferModeEnded()

        val copied = File(destinationDirectory(), source.name)
        assertArrayEquals(expectedBytes, File(copied, "nested/chapter.cbz").readBytes())
        assertArrayEquals(expectedHiddenBytes, File(copied, ".hidden.cbz").readBytes())
        assertTrue(File(copied, "empty").isDirectory)
        assertTrue(source.isDirectory)
    }

    @Test
    fun cutFolderTransfersFullTreeAndKeepsSourceWhenMoveIsUnavailable() {
        val source = createComicFolder("cut-folder")
        val expectedBytes = File(source, "nested/chapter.cbz").readBytes()
        val supportsMove = supportsDirectMove(source)

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseDirectoryAction(source.name, R.id.btnCutDirectory)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())
        awaitTransferModeEnded()

        assertEquals(!supportsMove, source.exists())
        if (!supportsMove) assertArrayEquals(expectedBytes, File(source, "nested/chapter.cbz").readBytes())
        val moved = File(destinationDirectory(), source.name)
        assertArrayEquals(expectedBytes, File(moved, "nested/chapter.cbz").readBytes())
        assertTrue(File(moved, "empty").isDirectory)
    }

    @Test
    fun fileNameConflictRequiresNewNameAndDoesNotOverwriteExistingFile() {
        val source = createComicFile(SOURCE_DIRECTORY_NAME, "sample.cbz", "source bytes")
        val existing = File(destinationDirectory(), source.name).apply {
            writeText("existing destination bytes")
        }
        val expectedSource = source.readBytes()

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseFileAction(source.name, R.id.btnCopyFile)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())

        onView(withText(R.string.paste_file_conflict_title)).check(matches(isDisplayed()))
        onView(isAssignableFrom(EditText::class.java))
            .inRoot(isDialog())
            .perform(replaceText("renamed-copy"))
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
        awaitTransferModeEnded()

        assertArrayEquals("existing destination bytes".toByteArray(), existing.readBytes())
        assertArrayEquals(
            expectedSource,
            File(destinationDirectory(), "renamed-copy.cbz").readBytes()
        )
        assertTrue(source.isFile)
    }

    @Test
    fun exitingTransferModeBeforePasteLeavesFilesUnchangedAndRestoresToolbar() {
        val source = createComicFile(SOURCE_DIRECTORY_NAME, "sample.cbz", "do not move")
        val originalBytes = source.readBytes()

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseFileAction(source.name, R.id.btnCopyFile)
        onView(withId(R.id.btnHelp))
            .check(matches(org.hamcrest.Matchers.not(
                androidx.test.espresso.matcher.ViewMatchers.isEnabled()
            )))
        onView(withId(R.id.btnOptions))
            .check(matches(org.hamcrest.Matchers.not(
                androidx.test.espresso.matcher.ViewMatchers.isEnabled()
            )))
        onView(withId(R.id.btnExitFileTransfer)).perform(click())

        onView(withId(R.id.btnPasteFile))
            .check(matches(org.hamcrest.Matchers.not(isDisplayed())))
        onView(withId(R.id.btnHelp))
            .check(matches(androidx.test.espresso.matcher.ViewMatchers.isEnabled()))
        onView(withId(R.id.btnOptions))
            .check(matches(androidx.test.espresso.matcher.ViewMatchers.isEnabled()))
        assertArrayEquals(originalBytes, source.readBytes())
        assertTrue(destinationDirectory().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun folderCannotBePastedIntoItsOwnDescendant() {
        val source = createComicFolder("folder")

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseDirectoryAction(source.name, R.id.btnCopyDirectory)
        openDirectory(source.name)
        openDirectory("nested")
        onView(withId(R.id.btnPasteFile)).perform(click())

        assertTrue(source.isDirectory)
        assertFalse(File(source, "nested/${source.name}").exists())
        onView(withId(R.id.btnPasteFile)).check(matches(isDisplayed()))
    }

    @Test
    fun cutDirectoryMovesMetadataOnlyWhenSourceIsMoved() {
        val sourceDirectory = createComicFolder("cut-metadata")
        val supportsMove = supportsDirectMove(sourceDirectory)
        val sourceArchive = File(sourceDirectory, "nested/chapter.cbz")
        val expectedBytes = sourceArchive.readBytes()
        val lastReadAt = System.currentTimeMillis() - 1_000L

        assertEquals(
            FavoritePathStore.RecordResult.ADDED,
            FavoritePathStore.record(context, sourceDirectory)
        )
        assertEquals(
            FavoriteComicStore.RecordResult.ADDED,
            FavoriteComicStore.record(context, sourceArchive)
        )
        ReadingHistoryStore.merge(
            context,
            listOf(historyItem(sourceArchive, lastReadAt))
        )
        ReaderProgressStore.restoreReadingProgress(
            context,
            sourceArchive,
            ComicLabMangaProgress(
                path = sourceArchive.absolutePath,
                position = 4,
                offset = -91
            )
        )
        AppSettings.setBookcaseDirectory(context, sourceDirectory, enabled = true)

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseDirectoryAction(sourceDirectory.name, R.id.btnCutDirectory)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())
        awaitTransferModeEnded()

        val targetDirectory = File(destinationDirectory(), sourceDirectory.name)
        val targetArchive = File(targetDirectory, "nested/chapter.cbz")
        assertEquals(!supportsMove, sourceDirectory.exists())
        assertArrayEquals(expectedBytes, targetArchive.readBytes())

        val metadataDirectory = if (supportsMove) targetDirectory else sourceDirectory
        val metadataArchive = if (supportsMove) targetArchive else sourceArchive
        val otherArchive = if (supportsMove) sourceArchive else targetArchive
        assertEquals(setOf(metadataDirectory.absolutePath), FavoritePathStore.favoriteDirectoryPaths(context))
        assertEquals(setOf(metadataArchive.absolutePath), FavoriteComicStore.favoriteFilePaths(context))
        val history = ReadingHistoryStore.items(context)
        assertEquals(listOf(metadataArchive.absolutePath), history.map { it.file.absolutePath })
        assertEquals(lastReadAt, history.single().lastReadAt)
        assertEquals(4 to -91, ReaderProgressStore.readPositionAndOffset(context, metadataArchive))
        assertEquals(setOf(metadataDirectory.absolutePath), AppSettings.bookcaseDirectoryPaths(context))
        assertFalse(
            context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                .all.keys.any { it.startsWith("reader:${otherArchive.absolutePath}:") }
        )
    }

    @Test
    fun renamingDirectoryMigratesMetadataAndKeepsNewerTargetHistoryOnDevice() {
        val sourceDirectory = createComicFolder("rename-directory")
        val sourceArchive = File(sourceDirectory, "nested/chapter.cbz")
        val sourceReadAt = System.currentTimeMillis() - 10_000L
        val targetReadAt = sourceReadAt + 5_000L
        val targetDirectory = File(sourceDirectory.parentFile, "renamed-directory")
        val staleTargetArchive = File(targetDirectory, "nested/chapter.cbz")

        val parentFavorite = FavoritePathStore.Item(sourceDirectory, sourceReadAt)
        assertEquals(1, FavoritePathStore.merge(context, listOf(parentFavorite)))
        assertEquals(
            FavoriteComicStore.RecordResult.ADDED,
            FavoriteComicStore.record(context, sourceArchive)
        )
        ReadingHistoryStore.merge(context, listOf(historyItem(sourceArchive, sourceReadAt)))
        ReaderProgressStore.restoreReadingProgress(
            context,
            sourceArchive,
            ComicLabMangaProgress(sourceArchive.absolutePath, position = 6, offset = -40)
        )
        AppSettings.setBookcaseDirectory(context, sourceDirectory, enabled = true)

        check(staleTargetArchive.parentFile?.mkdirs() == true)
        staleTargetArchive.writeBytes(sourceArchive.readBytes())
        check(staleTargetArchive.setLastModified(sourceArchive.lastModified()))
        ReadingHistoryStore.merge(context, listOf(historyItem(staleTargetArchive, targetReadAt)))
        ReaderProgressStore.restoreReadingProgress(
            context,
            staleTargetArchive,
            ComicLabMangaProgress(staleTargetArchive.absolutePath, position = 9, offset = -10)
        )
        check(staleTargetArchive.delete())
        check(targetDirectory.deleteRecursively())

        openDirectory(SOURCE_DIRECTORY_NAME)
        renameDirectoryInBrowser(sourceDirectory.name, targetDirectory.name)

        val movedArchive = File(targetDirectory, "nested/chapter.cbz")
        assertTrue(targetDirectory.isDirectory)
        assertFalse(sourceDirectory.exists())
        assertTrue(AppSettings.bookcaseDirectoryPaths(context).contains(targetDirectory.absolutePath))
        assertFalse(AppSettings.bookcaseDirectoryPaths(context).contains(sourceDirectory.absolutePath))
        assertEquals(
            listOf(parentFavorite.copy(directory = targetDirectory)),
            FavoritePathStore.items(context)
        )
        assertEquals(setOf(movedArchive.absolutePath), FavoriteComicStore.favoriteFilePaths(context))
        val history = ReadingHistoryStore.items(context)
        assertEquals(listOf(movedArchive.absolutePath), history.map { it.file.absolutePath })
        assertEquals(targetReadAt, history.single().lastReadAt)
        assertEquals(9 to -10, ReaderProgressStore.readPositionAndOffset(context, movedArchive))
        assertFalse(
            context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                .all.keys.any { it.startsWith("reader:${sourceArchive.absolutePath}:") }
        )
    }

    @Test
    fun comicMergeListsUnreadableArchivesAndEmptyArchivesAlongsideSuccessfulOutput() {
        val root = File(sandbox, SOURCE_DIRECTORY_NAME)
        createComicFile(SOURCE_DIRECTORY_NAME, "good.cbz", "merged page bytes")
        val badZip = File(root, "broken-cbz.cbz").apply { writeText("broken zip") }
        val badSevenZip = File(root, "broken-7z.7z").apply { writeText("broken seven zip") }
        val empty = File(root, "empty.zip").apply { ZipOutputStream(outputStream()).close() }

        openDirectory(SOURCE_DIRECTORY_NAME)
        startComicMergeFromBrowser()
        awaitComicMergeReport(badZip.name, badSevenZip.name, empty.name)

        val output = root.listFiles().orEmpty().single { it.isDirectory && it.name.startsWith("ComicLab_merged_") }
        assertEquals(listOf("0001.jpg"), output.listFiles().orEmpty().map { it.name })
        assertEquals("merged page bytes", File(output, "0001.jpg").readText())
        val summary = context.getString(R.string.language_comics_merge_result, 1, 1, 0, output.name)
        val unreadable = context.getString(R.string.language_comics_merge_unreadable, 2)
        val imageFree = context.getString(R.string.language_comics_merge_empty_archives, 1)
        onView(withId(android.R.id.message)).inRoot(isDialog()).check(matches(withText(
            "$summary\n\n$unreadable\n${badSevenZip.name}\n${badZip.name}\n\n$imageFree\n${empty.name}"
        )))
    }

    @Test
    fun comicMergeReportsUnreadableAndImageFreeInputsWhenNothingCanBeMerged() {
        val root = File(sandbox, SOURCE_DIRECTORY_NAME)
        val broken = File(root, "damaged.cbz").apply { writeText("invalid archive") }
        val imageFree = File(root, "notes-only.cbz").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("note.txt"))
                zip.write("no images in this archive".toByteArray())
                zip.closeEntry()
            }
        }

        openDirectory(SOURCE_DIRECTORY_NAME)
        startComicMergeFromBrowser()
        awaitComicMergeReport(broken.name, imageFree.name)

        val summary = context.getString(R.string.language_comics_merge_no_output)
        val unreadableHeader = context.getString(R.string.language_comics_merge_unreadable, 1)
        val imageFreeHeader = context.getString(R.string.language_comics_merge_empty_archives, 1)
        onView(withId(android.R.id.message)).inRoot(isDialog()).check(matches(withText(
            "$summary\n\n$unreadableHeader\n${broken.name}\n\n$imageFreeHeader\n${imageFree.name}"
        )))
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("ComicLab_merged_") })
    }

    private fun startComicMergeFromBrowser() {
        onView(withId(R.id.btnClassify)).perform(click())
        onView(withText(R.string.language_menu_merge_comics)).inRoot(isPlatformPopup()).perform(click())
    }

    private fun awaitComicMergeReport(vararg names: String) {
        awaitCondition("comic merge should report the skipped input names together") {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText(names.first())
                ?.any { node -> names.all { node.text?.contains(it) == true } } == true
        }
    }

    @Test
    fun deletingDirectoryFreesOnlyItsBookAndDirectoryFavoriteSlots() {
        val parent = File(sandbox, SOURCE_DIRECTORY_NAME)
        val root = File(parent, "library")
        val child = File(root, "series")
        val deep = File(child, "volume").apply { check(mkdirs()) }
        val deletedPaths = listOf(root, child, deep)
        val deletedBooks = listOf(
            File(root, "book.cbz").apply { writeText("comic") },
            File(child, "novel.epub").apply { writeText("ebook") },
            File(deep, "text.txt").apply { writeText("ebook") }
        )
        val sibling = File(parent, "library-other").apply { check(mkdir()) }
        val offline = File(parent, "library-offline").apply { check(mkdir()) }
        val parked = File(parent, "offline-storage")
        val offlineBook = File(offline, "offline.epub").apply { writeText("offline content") }
        val otherPaths = listOf(sibling, offline) + List(15) { index ->
            File(sibling, "path-$index").apply { check(mkdir()) }
        }
        val otherBooks = listOf(offlineBook) + List(96) { index ->
            File(sibling, "book-$index.cbz").apply { writeText("unrelated comic") }
        }
        assertEquals(20, FavoritePathStore.merge(context, (deletedPaths + otherPaths).mapIndexed { index, file ->
            FavoritePathStore.Item(file, index + 1L)
        }))
        assertEquals(100, FavoriteComicStore.merge(context, (deletedBooks + otherBooks).mapIndexed { index, file ->
            FavoriteComicStore.Item(file, index + 1L, file.length(), file.lastModified())
        }))
        check(offline.renameTo(parked))
        assertFalse(FavoritePathStore.isFavorite(context, offline))
        assertFalse(FavoriteComicStore.isFavorite(context, offlineBook))

        val newPaths = List(4) { index -> File(sibling, "new-path-$index").apply { check(mkdir()) } }
        val newBooks = List(4) { index -> File(sibling, "new-book-$index.cbz").apply { writeText("new") } }
        assertEquals(FavoritePathStore.RecordResult.LIMIT_REACHED, FavoritePathStore.record(context, newPaths.first()))
        assertEquals(FavoriteComicStore.RecordResult.LIMIT_REACHED, FavoriteComicStore.record(context, newBooks.first()))

        openDirectory(SOURCE_DIRECTORY_NAME)
        openDirectoryDeleteConfirmation(root.name)
        onView(withText(R.string.yes)).inRoot(isDialog()).perform(click())
        awaitDirectoryDeletion()

        assertFalse(root.exists())
        assertEquals(otherPaths.map { it.absolutePath }.toSet(), storedPaths("favorite_path_prefs", "favorite_paths"))
        assertEquals(otherBooks.map { it.absolutePath }.toSet(), storedPaths("favorite_comic_prefs", "favorite_comics"))
        newPaths.take(3).forEach { assertEquals(FavoritePathStore.RecordResult.ADDED, FavoritePathStore.record(context, it)) }
        newBooks.take(3).forEach { assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, it)) }
        assertEquals(FavoritePathStore.RecordResult.LIMIT_REACHED, FavoritePathStore.record(context, newPaths.last()))
        assertEquals(FavoriteComicStore.RecordResult.LIMIT_REACHED, FavoriteComicStore.record(context, newBooks.last()))

        check(parked.renameTo(offline))
        assertTrue(FavoritePathStore.isFavorite(context, offline))
        assertTrue(FavoriteComicStore.isFavorite(context, offlineBook))
    }

    @Test
    fun directoryDeletionThatFailsWhileUnavailableRetainsAllFavorites() {
        val parent = File(sandbox, SOURCE_DIRECTORY_NAME)
        val root = File(parent, "missing-during-delete")
        val child = File(root, "subdir").apply { check(mkdirs()) }
        val book = File(child, "book.cbz").apply { writeText("comic") }
        val parked = File(parent, "offline-delete-source")
        assertEquals(FavoritePathStore.RecordResult.ADDED, FavoritePathStore.record(context, root))
        assertEquals(FavoritePathStore.RecordResult.ADDED, FavoritePathStore.record(context, child))
        assertEquals(FavoriteComicStore.RecordResult.ADDED, FavoriteComicStore.record(context, book))
        val directoryPrefs = snapshotPreferences("favorite_path_prefs")
        val bookPrefs = snapshotPreferences("favorite_comic_prefs")

        openDirectory(SOURCE_DIRECTORY_NAME)
        openDirectoryDeleteConfirmation(root.name)
        val releaseWorker = CountDownLatch(1)
        val executor = checkNotNull(activity).directoryLoadExecutorForTest
        executor.execute { check(releaseWorker.await(10, TimeUnit.SECONDS)) }
        try {
            onView(withText(R.string.yes)).inRoot(isDialog()).perform(click())
            check(root.renameTo(parked))
        } finally {
            releaseWorker.countDown()
        }
        awaitDirectoryDeletion()

        assertTrue(File(parked, "subdir/book.cbz").isFile)
        assertEquals(directoryPrefs, snapshotPreferences("favorite_path_prefs"))
        assertEquals(bookPrefs, snapshotPreferences("favorite_comic_prefs"))
        check(parked.renameTo(root))
        assertTrue(FavoritePathStore.isFavorite(context, root))
        assertTrue(FavoritePathStore.isFavorite(context, child))
        assertTrue(FavoriteComicStore.isFavorite(context, book))
    }

    private fun openDirectoryDeleteConfirmation(name: String) {
        awaitListItem(name)
        onData(fileItemNamed(name)).inAdapterView(withId(R.id.listFiles)).perform(longClick())
        waitForActionToSettle(R.id.btnDeleteDirectory)
        onView(withId(R.id.btnDeleteDirectory)).inRoot(isDialog()).perform(click())
        onView(withText(R.string.delete_directory_message)).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private fun awaitDirectoryDeletion() {
        checkNotNull(activity).directoryLoadExecutorForTest.submit {}.get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
    }

    private fun storedPaths(preferences: String, key: String): Set<String> {
        val rows = JSONArray(context.getSharedPreferences(preferences, 0).getString(key, "[]"))
        return (0 until rows.length()).mapTo(mutableSetOf()) { rows.getJSONObject(it).getString("path") }
    }

    @Test
    fun renamingAnUnfavoritedParentKeepsNestedFavoritesAndResolvesStoredPathCollisions() {
        val parent = File(sandbox, SOURCE_DIRECTORY_NAME)
        val source = File(parent, "shelf")
        val sourceMiddle = File(source, "series")
        val sourceDeep = File(sourceMiddle, "volume").apply { check(mkdirs()) }
        val sourceSolo = File(sourceDeep, "only-source-favorite").apply { check(mkdir()) }
        File(sourceSolo, "marker.cbz").writeText("favorite destination marker")
        val target = File(parent, "renamed-shelf")
        val targetMiddle = File(target, "series")
        val targetDeep = File(targetMiddle, "volume").apply { check(mkdirs()) }
        val targetSolo = File(targetDeep, "only-source-favorite")
        val unrelated = File(parent, "shelf-other/series").apply { check(mkdirs()) }
        val favorites = listOf(
            FavoritePathStore.Item(unrelated, 50L),
            FavoritePathStore.Item(sourceMiddle, 200L),
            FavoritePathStore.Item(sourceDeep, 300L),
            FavoritePathStore.Item(sourceSolo, 350L),
            FavoritePathStore.Item(targetMiddle, 100L),
            FavoritePathStore.Item(targetDeep, 400L)
        )
        assertEquals(favorites.size, FavoritePathStore.merge(context, favorites))
        val prefs = context.getSharedPreferences("favorite_path_prefs", Context.MODE_PRIVATE)
        val before = prefs.getString("favorite_paths", null)

        openDirectory(SOURCE_DIRECTORY_NAME)
        renameDirectoryInBrowser(source.name, target.name)
        assertTrue(sourceSolo.isDirectory)
        assertTrue(targetDeep.isDirectory)
        assertEquals(before, prefs.getString("favorite_paths", null))

        // Keep the destination's saved favorites while removing its physical name conflict.
        check(targetDeep.delete())
        check(targetMiddle.delete())
        check(target.delete())
        renameDirectoryInBrowser(source.name, target.name)

        assertFalse(source.exists())
        val expected = listOf(
            FavoritePathStore.Item(unrelated, 50L),
            FavoritePathStore.Item(targetMiddle, 100L),
            FavoritePathStore.Item(targetDeep, 300L),
            FavoritePathStore.Item(targetSolo, 350L)
        )
        assertEquals(expected, FavoritePathStore.items(context))
        val stored = JSONArray(checkNotNull(prefs.getString("favorite_paths", null)))
        assertEquals(expected.map { it.directory.absolutePath }, List(stored.length()) { index ->
            stored.getJSONObject(index).getString("path")
        })

        onView(withId(R.id.btnFavoritePaths)).perform(click())
        onView(allOf(withId(R.id.layoutFavoritePathContent), hasDescendant(withText(targetSolo.absolutePath))))
            .inRoot(isPlatformPopup()).perform(click())
        awaitListItem("marker.cbz")
        assertEquals(targetSolo.absolutePath, onMain { checkNotNull(activity).currentPathForTest })
    }

    @Test
    fun renamingFileMigratesFavoriteHistoryAndProgressOnDevice() {
        val sourceArchive = createComicFile(SOURCE_DIRECTORY_NAME, "rename-file.cbz", "file rename bytes")
        val lastReadAt = System.currentTimeMillis() - 10_000L
        val targetReadAt = lastReadAt + 5_000L
        val targetArchive = File(sourceArchive.parentFile, "renamed-file.cbz")

        assertEquals(
            FavoriteComicStore.RecordResult.ADDED,
            FavoriteComicStore.record(context, sourceArchive)
        )
        ReadingHistoryStore.merge(context, listOf(historyItem(sourceArchive, lastReadAt)))
        ReaderProgressStore.restoreReadingProgress(
            context,
            sourceArchive,
            ComicLabMangaProgress(sourceArchive.absolutePath, position = 7, offset = -30)
        )

        targetArchive.writeBytes(sourceArchive.readBytes())
        targetArchive.setLastModified(sourceArchive.lastModified())
        ReadingHistoryStore.merge(context, listOf(historyItem(targetArchive, targetReadAt)))
        ReaderProgressStore.restoreReadingProgress(
            context,
            targetArchive,
            ComicLabMangaProgress(targetArchive.absolutePath, position = 9, offset = -15)
        )
        check(targetArchive.delete())

        openDirectory(SOURCE_DIRECTORY_NAME)
        renameFileInBrowser(sourceArchive.name, targetArchive.nameWithoutExtension)

        assertFalse(sourceArchive.exists())
        assertTrue(targetArchive.isFile)
        assertEquals(setOf(targetArchive.absolutePath), FavoriteComicStore.favoriteFilePaths(context))
        val history = ReadingHistoryStore.items(context)
        assertEquals(listOf(targetArchive.absolutePath), history.map { it.file.absolutePath })
        assertEquals(targetReadAt, history.single().lastReadAt)
        assertEquals(9 to -15, ReaderProgressStore.readPositionAndOffset(context, targetArchive))
        assertFalse(
            context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                .all.keys.any { it.startsWith("reader:${sourceArchive.absolutePath}:") }
        )
    }

    @Test
    fun renamingEbookResumesAtFurthestPositionAndKeepsSourceOnNameConflict() {
        val source = File(File(sandbox, SOURCE_DIRECTORY_NAME), "novel.txt").apply {
            writeText((1..300).joinToString("\n\n") { "Paragraph $it. Reader position migration fixture." })
        }
        val target = File(source.parentFile, "renamed.txt")
        val sourceProgress = EbookProgress(0, 0.75f)
        val targetProgress = EbookProgress(0, 0.4f)
        EbookProgressStore.save(context, source, sourceProgress)
        rememberProgressForTarget(source, target, targetProgress, keepFile = true)

        openDirectory(SOURCE_DIRECTORY_NAME)
        renameFileInBrowser(source.name, target.nameWithoutExtension)
        assertTrue(source.isFile)
        assertEquals(sourceProgress, EbookProgressStore.load(context, source))
        assertEquals(targetProgress, EbookProgressStore.load(context, target))

        assertTrue(target.delete())
        renameFileInBrowser(source.name, target.nameWithoutExtension)
        assertFalse(source.exists())
        assertEquals(sourceProgress, EbookProgressStore.load(context, target))

        awaitListItem(target.name)
        onData(fileItemNamed(target.name)).inAdapterView(withId(R.id.listFiles)).perform(click())
        waitForActionToSettle(R.id.btnReadComic)
        onView(withId(R.id.btnReadComic)).inRoot(isDialog()).perform(click())
        awaitCondition("ebook preview did not make Continue available") {
            onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<EbookPreviewActivity>()
                    .any { it.findViewById<View>(R.id.btnEbookContinue).isEnabled }
            }
        }
        try {
            onView(withId(R.id.btnEbookContinue)).perform(click())
            awaitCondition("the renamed TXT did not resume its saved WebView position") {
                onMain {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<EbookReaderActivity>()
                        .any { reader ->
                            val slider = reader.findViewById<SeekBar>(R.id.sliderEbookReaderProgress)
                            val expected = EbookReaderProgressMapper.toSlider(sourceProgress, 1)
                            abs(slider.progress - expected) <= 5 &&
                                reader.findViewById<WebView>(R.id.webEbookReader).scrollY > 0
                        }
                }
            }
        } finally {
            onMain {
                listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                }.filter { it is EbookReaderActivity || it is EbookPreviewActivity }
                    .forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun renamingEbookParentMigratesBothIndexedAndLegacyProgress() {
        val parent = File(File(sandbox, SOURCE_DIRECTORY_NAME), "books")
        val ebook = createEbookFixture(parent, "nested/novel.epub")
        val legacy = createEbookFixture(parent, "nested/deeper/old.mobi")
        val current = EbookProgress(3, 0.62f)
        val old = EbookProgress(2, 0.8f, coordinateVersion = 1)
        EbookProgressStore.save(context, ebook, current)
        saveLegacyEbookProgress(legacy, old)

        openDirectory(SOURCE_DIRECTORY_NAME)
        renameDirectoryInBrowser(parent.name, "renamed-books")
        val target = File(parent.parentFile, "renamed-books")
        assertFalse(parent.exists())
        assertEquals(current, EbookProgressStore.load(context, File(target, "nested/novel.epub")))
        assertEquals(old, EbookProgressStore.load(context, File(target, "nested/deeper/old.mobi")))
    }

    @Test
    fun cuttingEbookFileOnPrivateStorageKeepsFurtherTargetChapter() {
        val source = createEbookFixture(privateFiles.root, "source/novel.epub")
        val destination = privateFiles.newFolder("destination")
        val target = File(destination, source.name)
        EbookProgressStore.save(context, source, EbookProgress(2, 0.9f))
        val sourceKey = ebookProgressKey(source)
        val targetProgress = EbookProgress(3, 0.1f)
        rememberProgressForTarget(source, target, targetProgress)

        pastePrivateCut(source, destination)
        awaitTransferModeEnded()
        assertFalse(source.exists())
        assertTrue(target.isFile)
        assertEquals(targetProgress, EbookProgressStore.load(context, target))
        assertFalse(context.getSharedPreferences("ebook_progress_prefs", 0)
            .contains("ebook_progress_${sourceKey}_chapter"))
    }

    @Test
    fun cuttingEbookParentOnPrivateStorageMigratesNestedProgress() {
        val sourceDirectory = privateFiles.newFolder("source")
        val source = createEbookFixture(sourceDirectory, "nested/novel.mobi")
        val progress = EbookProgress(2, 0.64f, coordinateVersion = 1)
        EbookProgressStore.save(context, source, progress)
        val destination = privateFiles.newFolder("destination")
        val otherSource = createEbookFixture(sourceDirectory, "another.txt")
        val otherTarget = File(destination, "source/another.txt")
        val otherTargetDirectory = checkNotNull(otherTarget.parentFile)
        check(otherTargetDirectory.mkdir())
        EbookProgressStore.save(context, otherSource, EbookProgress(1, 0.9f, coordinateVersion = 1))
        val existing = EbookProgress(1, 0.25f, coordinateVersion = 2)
        rememberProgressForTarget(otherSource, otherTarget, existing)
        check(otherTargetDirectory.delete())

        pastePrivateCut(sourceDirectory, destination)
        awaitTransferModeEnded()
        assertFalse(sourceDirectory.exists())
        val target = File(destination, "source/nested/novel.mobi")
        assertEquals(progress, EbookProgressStore.load(context, target))
        assertEquals(existing, EbookProgressStore.load(context, otherTarget))
    }

    @Test
    fun failedEbookCutKeepsOriginalProgressForFileAndParent() {
        val sourceDirectory = privateFiles.newFolder("source")
        val source = createEbookFixture(sourceDirectory, "novel.epub")
        val progress = EbookProgress(2, 0.62f)
        EbookProgressStore.save(context, source, progress)
        val browser = checkNotNull(activity)

        listOf(source, sourceDirectory).forEachIndexed { index, entry ->
            val destination = privateFiles.newFolder("destination-$index")
            val releaseWorker = CountDownLatch(1)
            browser.fileTransferExecutorForTest.execute { check(releaseWorker.await(10, TimeUnit.SECONDS)) }
            try {
                pastePrivateCut(entry, destination)
                assertTrue(browser.fileTransferControllerForTest.isTransferInProgress)
                // The actual transfer observes an invalid destination after the request was accepted.
                assertTrue(destination.delete())
                destination.writeText("destination is now a file")
            } finally {
                releaseWorker.countDown()
            }
            awaitCondition("failed cut did not finish") {
                onMain { !browser.fileTransferControllerForTest.isTransferInProgress }
            }
            assertTrue(source.isFile)
            assertEquals(progress, EbookProgressStore.load(context, source))
            assertTrue(browser.fileTransferControllerForTest.pendingTransfer != null)
            onView(withId(R.id.btnExitFileTransfer)).perform(click())
        }
    }

    @Test
    fun cuttingEbookOnSharedStorageMovesProgressOnlyWhenSourceMoves() {
        val source = createEbookFixture(File(sandbox, SOURCE_DIRECTORY_NAME), "novel.txt")
        val progress = EbookProgress(0, 0.71f)
        EbookProgressStore.save(context, source, progress)
        val supportsMove = supportsDirectMove(source)

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseFileAction(source.name, R.id.btnCutFile)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())
        awaitTransferModeEnded()
        val target = File(destinationDirectory(), source.name)
        assertTrue(target.isFile)
        assertEquals(!supportsMove, source.isFile)
        assertEquals(progress, EbookProgressStore.load(context, if (supportsMove) target else source))
        if (!supportsMove) assertNull(EbookProgressStore.load(context, target))
    }

    private fun pastePrivateCut(source: File, destination: File) {
        val browser = checkNotNull(activity)
        onMain {
            browser.currentPathForTest = destination.absolutePath
            browser.beginFileTransferForTest(source, FileTransferMode.CUT)
            assertTrue(browser.findViewById<View>(R.id.btnPasteFile).performClick())
            assertTrue(browser.fileTransferControllerForTest.isTransferInProgress)
        }
    }

    private fun createEbookFixture(directory: File, name: String): File = File(directory, name).apply {
        check(parentFile?.let { it.mkdirs() || it.isDirectory } == true)
        writeText("book content for progress migration")
    }

    private fun rememberProgressForTarget(source: File, target: File, progress: EbookProgress, keepFile: Boolean = false) {
        source.copyTo(target)
        check(target.setLastModified(source.lastModified()))
        check(target.lastModified() == source.lastModified())
        EbookProgressStore.save(context, target, progress)
        if (!keepFile) check(target.delete())
    }

    private fun saveLegacyEbookProgress(file: File, progress: EbookProgress) {
        val key = ebookProgressKey(file)
        check(context.getSharedPreferences("ebook_progress_prefs", 0).edit()
            .putInt("ebook_progress_${key}_chapter", progress.chapterIndex)
            .putFloat("ebook_progress_${key}_fraction", progress.scrollFraction)
            .commit())
    }

    private fun ebookProgressKey(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(EbookProgressStore.fileIdentity(file).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun createComicFile(parentName: String, name: String, contents: String): File {
        val file = File(File(sandbox, parentName), name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("page.jpg"))
            zip.write(contents.toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun createComicFolder(name: String): File {
        val directory = File(File(sandbox, SOURCE_DIRECTORY_NAME), name).apply {
            check(mkdirs())
        }
        File(directory, "nested").mkdirs()
        createComicFile("$SOURCE_DIRECTORY_NAME/$name/nested", "chapter.cbz", "chapter bytes")
        createComicFile("$SOURCE_DIRECTORY_NAME/$name", ".hidden.cbz", "hidden bytes")
        File(directory, "empty").mkdir()
        return directory
    }

    private fun historyItem(file: File, lastReadAt: Long) = ReadingHistoryStore.Item(
        file = file,
        lastReadAt = lastReadAt,
        fileSize = file.length(),
        modifiedAt = file.lastModified()
    )

    private fun renameDirectoryInBrowser(oldName: String, newName: String) {
        awaitListItem(oldName)
        onData(fileItemNamed(oldName))
            .inAdapterView(withId(R.id.listFiles))
            .perform(longClick())
        waitForActionToSettle(R.id.btnRenameDirectoryName)
        onView(withId(R.id.btnRenameDirectoryName))
            .inRoot(isDialog())
            .perform(click())
        onView(isAssignableFrom(EditText::class.java))
            .inRoot(isDialog())
            .perform(replaceText(newName))
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
    }

    private fun renameFileInBrowser(oldName: String, newName: String) {
        awaitListItem(oldName)
        onData(fileItemNamed(oldName))
            .inAdapterView(withId(R.id.listFiles))
            .perform(click())
        waitForActionToSettle(R.id.btnRenameFileName)
        onView(withId(R.id.btnRenameFileName))
            .inRoot(isDialog())
            .perform(click())
        onView(isAssignableFrom(EditText::class.java))
            .inRoot(isDialog())
            .perform(replaceText(newName))
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
    }

    private fun snapshotPreferences(name: String): Map<String, Any?> {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
            .all
            .mapValues { (_, value) ->
                if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
            }
    }

    private fun restoreProtectedPreferences() {
        protectedPreferenceSnapshots.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(
                        key,
                        value.filterIsInstance<String>().toMutableSet()
                    )
                    else -> error("Unsupported SharedPreferences value for $name/$key")
                }
            }
            check(editor.commit()) { "Could not restore $name after device validation" }
        }
    }

    private fun openDirectory(name: String) {
        awaitListItem(name)
        onData(fileItemNamed(name))
            .inAdapterView(withId(R.id.listFiles))
            .perform(click())
    }

    private fun chooseFileAction(fileName: String, actionId: Int) {
        awaitListItem(fileName)
        onData(fileItemNamed(fileName))
            .inAdapterView(withId(R.id.listFiles))
            .perform(click())
        val actionLabel = when (actionId) {
            R.id.btnCopyFile -> R.string.copy_file
            R.id.btnCutFile -> R.string.cut_file
            else -> error("Unexpected file action id: $actionId")
        }
        onView(withId(actionId))
            .inRoot(isDialog())
            .check(matches(withText(actionLabel)))
            .check(matches(isDisplayed()))
        waitForActionToSettle(actionId)
        onView(withId(actionId)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.btnPasteFile)).check(matches(isDisplayed()))
    }

    private fun chooseDirectoryAction(directoryName: String, actionId: Int) {
        awaitListItem(directoryName)
        onData(fileItemNamed(directoryName))
            .inAdapterView(withId(R.id.listFiles))
            .perform(longClick())
        val actionLabel = when (actionId) {
            R.id.btnCopyDirectory -> R.string.copy_folder
            R.id.btnCutDirectory -> R.string.cut_folder
            else -> error("Unexpected directory action id: $actionId")
        }
        onView(withId(actionId))
            .inRoot(isDialog())
            .check(matches(withText(actionLabel)))
            .check(matches(isDisplayed()))
        waitForActionToSettle(actionId)
        onView(withId(actionId))
            .inRoot(isDialog())
            .perform(click())
        onView(withId(R.id.btnPasteFile)).check(matches(isDisplayed()))
    }

    private fun waitForActionToSettle(actionId: Int) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var previousBounds: Rect? = null
        var stableSamples = 0

        while (SystemClock.uptimeMillis() < deadline) {
            var currentBounds: Rect? = null
            onView(withId(actionId))
                .inRoot(isDialog())
                .perform(object : ViewAction {
                    override fun getConstraints(): Matcher<View> =
                        isAssignableFrom(View::class.java)

                    override fun getDescription(): String =
                        "read stable bounds for action $actionId"

                    override fun perform(uiController: UiController, view: View) {
                        val visibleBounds = Rect()
                        if (view.isShown && view.isEnabled && view.isClickable &&
                            view.getGlobalVisibleRect(visibleBounds) &&
                            visibleBounds.width() == view.width &&
                            visibleBounds.height() == view.height
                        ) {
                            currentBounds = Rect(visibleBounds)
                        }
                    }
                })

            if (currentBounds != null && currentBounds == previousBounds) {
                stableSamples++
                if (stableSamples >= REQUIRED_STABLE_SAMPLES) {
                    return
                }
            } else {
                stableSamples = 0
            }

            previousBounds = currentBounds
            SystemClock.sleep(BOUNDS_SAMPLE_INTERVAL_MS)
        }

        assertTrue(
            "Menu action $actionId did not reach stable, fully visible bounds",
            stableSamples >= REQUIRED_STABLE_SAMPLES
        )
    }

    private fun navigateToDestination() {
        onView(withId(R.id.btnTransferParent)).perform(click())
        openDirectory(DESTINATION_DIRECTORY_NAME)
    }

    private fun launchBrowserActivity(): MainActivity {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -n com.example.comiclab/.MainActivity -f 0x10008000"
        )
        val shellOutput = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
        check(shellOutput.contains("Starting:")) {
            "device shell could not foreground isolated file-transfer test browser: $shellOutput"
        }

        var resumedActivity: MainActivity? = null
        awaitCondition("isolated MainActivity did not resume") {
            instrumentation.runOnMainSync {
                resumedActivity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull { !it.isFinishing && !it.isDestroyed }
            }
            resumedActivity != null
        }
        return checkNotNull(resumedActivity)
    }

    private fun awaitTransferModeEnded() {
        awaitCondition("transfer mode should end after a successful paste") {
            var ended = false
            val launchedActivity = activity ?: return@awaitCondition false
            instrumentation.runOnMainSync {
                ended = launchedActivity.findViewById<View>(R.id.btnPasteFile).visibility ==
                    View.GONE
            }
            ended
        }
        instrumentation.waitForIdleSync()
    }

    private fun awaitListItem(name: String) {
        awaitCondition("list should show $name") {
            var found = false
            val launchedActivity = activity ?: return@awaitCondition false
            instrumentation.runOnMainSync {
                val list = launchedActivity.findViewById<ListView>(R.id.listFiles)
                val adapter = list.adapter
                found = (0 until adapter.count).any { index ->
                    (adapter.getItem(index) as? FileItem)?.file?.name == name
                }
            }
            found
        }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) {
                return
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(description, condition())
    }

    private fun supportsDirectMove(source: File): Boolean {
        val probe = File(source.parentFile, ".cut-probe-${UUID.randomUUID()}")
        val target = File(destinationDirectory(), probe.name)
        if (source.isDirectory) check(probe.mkdir()) else check(probe.createNewFile())
        return try {
            when (TransferFileMove.move(probe, target)) {
                TransferFileMove.Result.MOVED -> true
                TransferFileMove.Result.UNAVAILABLE -> false
                else -> error("Could not probe atomic move support with a temporary fixture")
            }
        } finally {
            probe.delete()
            target.delete()
        }
    }

    private fun destinationDirectory(): File =
        File(sandbox, DESTINATION_DIRECTORY_NAME)

    private fun fileItemNamed(name: String): Matcher<Any> =
        object : TypeSafeMatcher<Any>() {
            override fun describeTo(description: Description) {
                description.appendText("a file-list item named $name")
            }

            override fun matchesSafely(item: Any): Boolean {
                return item is FileItem && item.file?.name == name
            }
        }

    private companion object {
        const val TEST_ROOT_PATH = "/sdcard/Download/CMCNList"
        const val TEST_SANDBOX_PREFIX = "ComicLab-DeviceValidation-"
        val PROTECTED_PREFERENCE_NAMES = listOf("reader_prefs", "ebook_progress_prefs", "app_settings")
        const val PREFS_NAME = "saf_prefs"
        const val KEY_CURRENT_PATH = "current_path"
        const val KEY_STORAGE_PERMISSION_PROMPTED = "storage_permission_prompted"
        const val SOURCE_DIRECTORY_NAME = "Source"
        const val DESTINATION_DIRECTORY_NAME = "Destination"
        const val UI_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 50L
        const val BOUNDS_SAMPLE_INTERVAL_MS = 75L
        const val REQUIRED_STABLE_SAMPLES = 3
    }
}
