package com.example.comiclab

import com.example.comiclab.core.settings.ComicLabMangaProgress
import android.content.Context
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.core.store.FavoriteComicStore
import com.example.comiclab.core.store.FavoritePathStore
import com.example.comiclab.core.store.ReaderProgressStore
import com.example.comiclab.core.store.ReadingHistoryStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileTransferFlowTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
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
    fun cutFileCopiesExactBytesAndRemovesSourceAfterCompletion() {
        val source = createComicFile(SOURCE_DIRECTORY_NAME, "sample.cbz", "cut bytes")
        val expectedBytes = source.readBytes()

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseFileAction(source.name, R.id.btnCutFile)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())
        awaitTransferModeEnded()

        assertFalse(source.exists())
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
    fun cutFolderCopiesCompleteTreeBeforeRemovingSource() {
        val source = createComicFolder("cut-folder")
        val expectedBytes = File(source, "nested/chapter.cbz").readBytes()

        openDirectory(SOURCE_DIRECTORY_NAME)
        chooseDirectoryAction(source.name, R.id.btnCutDirectory)
        navigateToDestination()
        onView(withId(R.id.btnPasteFile)).perform(click())
        awaitTransferModeEnded()

        assertFalse(source.exists())
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
    fun cutDirectoryMigratesBookcaseFavoritesHistoryAndProgressOnDevice() {
        val sourceDirectory = createComicFolder("cut-metadata")
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

        val movedDirectory = File(destinationDirectory(), sourceDirectory.name)
        val movedArchive = File(movedDirectory, "nested/chapter.cbz")
        assertFalse(sourceDirectory.exists())
        assertArrayEquals(expectedBytes, movedArchive.readBytes())
        assertEquals(setOf(movedDirectory.absolutePath), FavoritePathStore.favoriteDirectoryPaths(context))
        assertEquals(setOf(movedArchive.absolutePath), FavoriteComicStore.favoriteFilePaths(context))
        val history = ReadingHistoryStore.items(context)
        assertEquals(listOf(movedArchive.absolutePath), history.map { it.file.absolutePath })
        assertEquals(lastReadAt, history.single().lastReadAt)
        assertEquals(4 to -91, ReaderProgressStore.readPositionAndOffset(context, movedArchive))
        assertTrue(AppSettings.bookcaseDirectoryPaths(context).contains(movedDirectory.absolutePath))
        assertFalse(AppSettings.bookcaseDirectoryPaths(context).contains(sourceDirectory.absolutePath))
        assertFalse(
            context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                .all.keys.any { it.startsWith("reader:${sourceArchive.absolutePath}:") }
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

        assertEquals(
            FavoritePathStore.RecordResult.ADDED,
            FavoritePathStore.record(context, sourceDirectory)
        )
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
        assertEquals(setOf(targetDirectory.absolutePath), FavoritePathStore.favoriteDirectoryPaths(context))
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
        val PROTECTED_PREFERENCE_NAMES = listOf("reader_prefs", "app_settings")
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
