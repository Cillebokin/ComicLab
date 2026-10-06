package com.example.comiclab

import android.content.Context
import android.content.Intent
import android.graphics.Rect
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
import com.example.comiclab.ui.browser.model.FileItem
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

    @Before
    fun setUp() {
        context = instrumentation.targetContext
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        hadCurrentPath = preferences!!.contains(KEY_CURRENT_PATH)
        previousCurrentPath = preferences!!.getString(KEY_CURRENT_PATH, null)
        hadPermissionPrompted = preferences!!.contains(KEY_STORAGE_PERMISSION_PROMPTED)
        previousPermissionPrompted =
            preferences!!.getBoolean(KEY_STORAGE_PERMISSION_PROMPTED, false)

        testRoot = File(context.cacheDir, TEST_ROOT_NAME).canonicalFile
        if (!testRoot!!.exists()) {
            check(testRoot!!.mkdirs()) {
                "Could not create the app-private test directory: ${testRoot!!.absolutePath}"
            }
        }
        check(testRoot!!.isDirectory) {
            "The app-private test directory must exist: ${testRoot!!.absolutePath}"
        }
        check(!Files.isSymbolicLink(testRoot!!.toPath())) {
            "The app-private test directory must not be a symbolic link"
        }

        sandbox = File(
            testRoot,
            "comiclab-transfer-e2e-${UUID.randomUUID()}"
        ).canonicalFile
        check(sandbox!!.toPath().startsWith(testRoot!!.toPath())) {
            "The test sandbox must remain inside the app-private test directory"
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

        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
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
                val cleanupRoot = sandbox
                if (cleanupRoot != null && cleanupRoot.exists()) {
                    val parent = testRoot ?: error("Missing app-private test root")
                    check(cleanupRoot.canonicalFile.toPath().startsWith(parent.toPath())) {
                        "Refusing to clean anything outside the app-private test directory"
                    }
                    check(cleanupRoot.deleteRecursively()) {
                        "Test cleanup failed; the remaining data is confined to ${cleanupRoot.absolutePath}"
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
        const val PREFS_NAME = "saf_prefs"
        const val KEY_CURRENT_PATH = "current_path"
        const val KEY_STORAGE_PERMISSION_PROMPTED = "storage_permission_prompted"
        const val TEST_ROOT_NAME = "task32-file-transfer-tests"
        const val SOURCE_DIRECTORY_NAME = "Source"
        const val DESTINATION_DIRECTORY_NAME = "Destination"
        const val UI_TIMEOUT_MS = 15_000L
        const val POLL_INTERVAL_MS = 50L
        const val BOUNDS_SAMPLE_INTERVAL_MS = 75L
        const val REQUIRED_STABLE_SAMPLES = 3
    }
}
