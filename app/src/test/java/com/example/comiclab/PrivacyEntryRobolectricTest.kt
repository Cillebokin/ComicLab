package com.example.comiclab

import android.content.Intent
import android.app.AlertDialog
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import com.example.comiclab.core.nas.NasShareStore
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.browser.model.FileItem
import com.example.comiclab.ui.privacy.PatternLockView
import com.example.comiclab.ui.privacy.PrivacyAccessGuard.PRIVACY_VIRTUAL_ROOT_PATH
import com.example.comiclab.ui.privacy.PrivacyAuthDialogController
import com.example.comiclab.ui.privacy.PrivacyPullDownLayout
import com.example.comiclab.ui.privacy.PrivacySessionManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class PrivacyEntryRobolectricTest {
    @get:Rule val folder = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private val pin = "012345"
    private val pattern = listOf(0, 1, 2, 5)
    private val otherPattern = listOf(0, 3, 6, 7)
    private val settingsIntent get() = Intent(app, SettingsActivity::class.java)
        .putExtra(SettingsActivity.EXTRA_SETTINGS_SECTION, SettingsActivity.SETTINGS_SECTION_PRIVACY)

    @After fun resetPrivacy() {
        ShadowManageExternalStorage.allowed = true
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test fun settingsShowsAnIndependentPrivacySectionAndLockClosesCredentialDialog() {
        Robolectric.buildActivity(SettingsActivity::class.java, settingsIntent).setup().use { controller ->
            val activity = controller.get()
            val setup = activity.findViewById<View>(R.id.btnPrivacySetupCredentials)
            assertTrue(setup.isShown)
            assertTrue(setup.performClick())
            val dialog = authDialog()
            assertTrue(isSecure(activity.window))
            assertTrue(isSecure(checkNotNull(dialog.window)))
            PrivacySessionManager.lock()
            assertFalse(dialog.isShowing)
            assertFalse(isSecure(activity.window))
            assertEquals(PrivacyStore.CredentialState.UNINITIALIZED, PrivacyStore.credentialState(app))
        }
    }

    @Test fun setupAndCredentialChangesRequireConfirmationAndTheCurrentCredential() {
        Robolectric.buildActivity(SettingsActivity::class.java, settingsIntent).setup().use { controller ->
            val activity = controller.get()
            activity.findViewById<View>(R.id.btnPrivacySetupCredentials).performClick()
            initializeFromDialogs(activity)
            assertTrue(activity.findViewById<View>(R.id.btnPrivacyChangePin).isShown)
            assertTrue(activity.findViewById<View>(R.id.btnPrivacyChangePattern).isShown)
            assertTrue(activity.findViewById<View>(R.id.tvPrivacyResetHelp).isShown)

            activity.findViewById<View>(R.id.btnPrivacyChangePin).performClick()
            awaitAuth(activity)
            typePin("999999")
            awaitAuth(activity)
            assertEquals(activity.getString(R.string.privacy_wrong_credential), status())
            typePin(pin)
            awaitAuth(activity)
            typePin("654321")
            typePin("654321")
            awaitAuth(activity)
            assertEquals(PrivacyStore.VerifyResult.SUCCESS, PrivacyStore.verifyPin(app, "654321"))

            activity.findViewById<View>(R.id.btnPrivacyChangePattern).performClick()
            awaitAuth(activity)
            draw(pattern)
            awaitAuth(activity)
            draw(otherPattern)
            draw(otherPattern)
            awaitAuth(activity)
            assertEquals(PrivacyStore.VerifyResult.SUCCESS, PrivacyStore.verifyPattern(app, otherPattern))
            assertFalse(PrivacySessionManager.isUnlocked)
        }
    }

    @Test fun recoverableSettingsUsesPinAndCorruptedSettingsOffersNoResetAction() {
        assertTrue(PrivacyStore.initializeCredentials(app, pin, pattern))
        val prefs = app.getSharedPreferences("privacy_prefs", 0)
        assertTrue(prefs.edit().remove("pattern_hash").commit())
        Robolectric.buildActivity(SettingsActivity::class.java, settingsIntent).setup().use { controller ->
            val activity = controller.get()
            assertFalse(activity.findViewById<View>(R.id.btnPrivacySetupCredentials).isShown)
            val repair = activity.findViewById<View>(R.id.btnPrivacyRepairPattern)
            assertTrue(repair.isShown)
            repair.performClick()
            awaitAuth(activity)
            typePin(pin)
            awaitAuth(activity)
            draw(otherPattern)
            draw(otherPattern)
            awaitAuth(activity)
            assertFalse(repair.isShown)
            assertTrue(activity.findViewById<View>(R.id.btnPrivacyChangePattern).isShown)
            assertFalse(PrivacySessionManager.isUnlocked)
        }
        assertTrue(prefs.edit().remove("pin_hash").commit())
        Robolectric.buildActivity(SettingsActivity::class.java, settingsIntent).setup().use { controller ->
            val activity = controller.get()
            for (id in listOf(R.id.btnPrivacySetupCredentials, R.id.btnPrivacyRepairPattern,
                R.id.btnPrivacyChangePin, R.id.btnPrivacyChangePattern)) {
                assertFalse(activity.findViewById<View>(id).isShown)
            }
            assertEquals(activity.getString(R.string.privacy_credentials_corrupted),
                activity.findViewById<TextView>(R.id.tvPrivacyCredentialState).text.toString())
        }
        withMain(root()) { activity ->
            assertTrue(pull(activity))
            val blocked = authDialog()
            assertEquals(activity.getString(R.string.privacy_credentials_corrupted),
                blocked.findViewById<TextView>(android.R.id.message).text.toString())
            assertNull(blocked.findViewById<View>(R.id.inputPrivacyPin))
            assertFalse(PrivacySessionManager.isUnlocked)
        }
    }

    @Test fun directoryMenuInitializesAtomicallyAndUnlockAllowsRemovingOnlyTheTopLevelMark() {
        val root = root()
        val privateFolder = File(root, "private shelf").apply { check(mkdir()) }
        val nested = File(privateFolder, "independent child").apply { check(mkdir()) }
        withMain(root) { activity ->
            assertFalse(pull(activity))
            privacyDirectoryAction(activity, privateFolder).performClick()
            typePin(pin)
            typePin(pin)
            draw(pattern)
            authDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            idle()
            assertEquals(PrivacyStore.CredentialState.UNINITIALIZED, PrivacyStore.credentialState(app))
            assertTrue(PrivacyStore.privacyDirectories(app).isEmpty())

            privacyDirectoryAction(activity, privateFolder).performClick()
            initializeFromDialogs(activity)
            awaitDirectory(activity)
            assertEquals(setOf(privateFolder.canonicalPath), PrivacyStore.privacyDirectories(app))
            assertFalse(listedFiles(activity).contains(privateFolder))
            assertTrue(PrivacyStore.addPrivacyDirectory(app, nested))
            idle()
            awaitDirectory(activity)

            assertTrue(pull(activity))
            awaitAuth(activity)
            draw(pattern)
            awaitAuth(activity)
            awaitDirectory(activity)
            assertEquals(PRIVACY_VIRTUAL_ROOT_PATH, activity.currentPathForTest)
            assertTrue(PrivacySessionManager.isUnlocked)
            assertTrue(isSecure(activity.window))
            assertEquals(listOf(privateFolder), listedFiles(activity))
            assertEquals(View.GONE, activity.findViewById<View>(R.id.btnClassify).visibility)

            val list = activity.findViewById<ListView>(R.id.listFiles)
            assertTrue(list.performItemClick(null, 0, list.getItemIdAtPosition(0)))
            awaitDirectory(activity)
            val clearIndependent = privacyDirectoryAction(activity, nested)
            assertEquals(activity.getString(R.string.privacy_clear_independent_mark), clearIndependent.text.toString())
            clearIndependent.performClick()
            awaitDirectory(activity)
            assertEquals(setOf(privateFolder.canonicalPath), PrivacyStore.privacyDirectories(app))
            assertTrue(PrivacyStore.isUnderPrivacyDirectory(app, nested))
            assertTrue(PrivacyStore.addPrivacyDirectory(app, nested))
            idle()
            awaitDirectory(activity)
            activity.onBackPressedDispatcher.onBackPressed()
            awaitDirectory(activity)
            assertEquals(PRIVACY_VIRTUAL_ROOT_PATH, activity.currentPathForTest)

            val remove = privacyDirectoryAction(activity, privateFolder)
            assertEquals(activity.getString(R.string.privacy_remove_directory), remove.text.toString())
            assertTrue(isSecure(checkNotNull(currentMenu(activity).window)))
            remove.performClick()
            awaitDirectory(activity)
            assertEquals(setOf(nested.canonicalPath), PrivacyStore.privacyDirectories(app))
            assertEquals(listOf(nested), listedFiles(activity))

            activity.findViewById<View>(R.id.btnBack).performClick()
            assertFalse(PrivacySessionManager.isUnlocked)
            assertTrue(isSecure(activity.window))
            awaitDirectory(activity)
            activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
            idle()
            assertEquals(root.path, activity.currentPathForTest)
            assertFalse(isSecure(activity.window))
        }
    }

    @Test fun forgotPatternVerifiesPinAndUnlocksOnlyAfterTheReplacementIsConfirmed() {
        val root = root()
        assertTrue(PrivacyStore.initializeCredentials(app, pin, pattern))
        withMain(root) { activity ->
            assertTrue(pull(activity))
            awaitAuth(activity)
            authDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            awaitAuth(activity)
            typePin(pin)
            awaitAuth(activity)
            draw(otherPattern)
            assertFalse(PrivacySessionManager.isUnlocked)
            draw(otherPattern)
            awaitAuth(activity)
            awaitDirectory(activity)
            assertEquals(PRIVACY_VIRTUAL_ROOT_PATH, activity.currentPathForTest)
            assertTrue(PrivacySessionManager.isUnlocked)
            assertEquals(PrivacyStore.VerifyResult.SUCCESS, PrivacyStore.verifyPattern(app, otherPattern))
        }
    }

    @Test fun lockDiscardsAVerifiedPatternResultQueuedForTheUi() {
        val root = root()
        val original = File(root, "ordinary").apply { check(mkdir()) }
        assertTrue(PrivacyStore.initializeCredentials(app, pin, pattern))
        withMain(original) { activity ->
            assertTrue(pull(activity))
            awaitAuth(activity)
            val dialog = authDialog()
            draw(pattern)
            awaitAuthWorker(activity)
            PrivacySessionManager.lock()
            assertFalse(dialog.isShowing)
            idle()
            assertFalse(PrivacySessionManager.isUnlocked)
            assertEquals(original.path, activity.currentPathForTest)
            assertFalse(isSecure(activity.window))
        }
    }

    @Test fun systemBackCancelsQueuedInitializationAndTheDirectoryMark() {
        val root = root()
        val candidate = File(root, "private shelf").apply { check(mkdir()) }
        withMain(root) { activity ->
            privacyDirectoryAction(activity, candidate).performClick()
            typePin(pin)
            typePin(pin)
            draw(pattern)

            cancelQueuedAuthWithSystemBack(activity) { draw(pattern) }

            assertEquals(PrivacyStore.CredentialState.UNINITIALIZED, PrivacyStore.credentialState(app))
            assertTrue(PrivacyStore.privacyDirectories(app).isEmpty())
            assertTrue(listedFiles(activity).contains(candidate))
            assertFalse(isSecure(activity.window))
        }
    }

    @Test fun systemBackCancelsAQueuedPinReplacement() {
        assertTrue(PrivacyStore.initializeCredentials(app, pin, pattern))
        Robolectric.buildActivity(SettingsActivity::class.java, settingsIntent).setup().use { controller ->
            val activity = controller.get()
            activity.findViewById<View>(R.id.btnPrivacyChangePin).performClick()
            awaitAuth(activity)
            typePin(pin)
            awaitAuth(activity)
            typePin("654321")

            cancelQueuedAuthWithSystemBack(activity) { typePin("654321") }

            assertEquals(PrivacyStore.VerifyResult.SUCCESS, PrivacyStore.verifyPin(app, pin))
            assertFalse(isSecure(activity.window))
        }
    }

    @Test fun fiveWrongAttemptsDisableThePatternAndLockDismissesCooldown() {
        val root = root()
        assertTrue(PrivacyStore.initializeCredentials(app, pin, pattern))
        repeat(4) { assertEquals(PrivacyStore.VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPin(app, "999999")) }
        withMain(root) { activity ->
            assertTrue(pull(activity))
            awaitAuth(activity)
            val dialog = authDialog()
            draw(otherPattern)
            awaitAuth(activity)
            assertEquals(activity.getString(R.string.privacy_cooldown, 30L), status())
            assertFalse(dialog.findViewById<PatternLockView>(R.id.privacyPattern).isEnabled)
            PrivacySessionManager.lock()
            assertFalse(dialog.isShowing)
            assertNull(ReflectionHelpers.getField<Runnable?>(authController(activity), "countdown"))
        }
    }

    @Test fun pullRejectsHorizontalMotionAndStorageOrNasGates() {
        val root = root()
        assertTrue(PrivacyStore.initializeCredentials(app, pin, pattern))
        NasShareStore.setNasEnabled(app, true)
        withMain(root) { activity ->
            assertFalse(pull(activity, horizontal = true))
            ShadowManageExternalStorage.allowed = false
            assertFalse(pull(activity))
            ShadowManageExternalStorage.allowed = true
            activity.findViewById<View>(R.id.btnSourceNas).performClick()
            assertFalse(pull(activity))
        }
    }

    @Test fun touchDispatchOnlyStartsAuthenticationFromTheTopOfTheLocalList() {
        val root = root()
        repeat(30) { File(root, "page%02d.cbz".format(it)).writeText("fixture") }
        assertTrue(PrivacyStore.initializeCredentials(app, pin, pattern))
        withMain(root) { activity ->
            val view = activity.findViewById<PrivacyPullDownLayout>(R.id.privacyPullDownLayout)
            val list = activity.findViewById<ListView>(R.id.listFiles)
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 400, 520)
            assertEquals(30, list.adapter.count)
            list.setSelectionFromTop(9, -14)
            idle()
            list.layout(0, 0, 400, 520)
            assertEquals(9, list.firstVisiblePosition)
            assertTrue(list.canScrollVertically(-1))
            assertFalse(pull(activity))
            list.setSelectionFromTop(0, 0)
            idle()
            list.layout(0, 0, 400, 520)
            assertFalse(list.canScrollVertically(-1))

            withTouch(MotionEvent.ACTION_DOWN, 35f, 40f, view::dispatchTouchEvent)
            withTouch(MotionEvent.ACTION_MOVE, 38f, 92f, view::dispatchTouchEvent)
            withTouch(MotionEvent.ACTION_MOVE, 39f, 330f, view::dispatchTouchEvent)
            withTouch(MotionEvent.ACTION_UP, 39f, 330f, view::dispatchTouchEvent)
            assertTrue(authDialog().isShowing)
            assertTrue(isSecure(activity.window))
        }
    }

    private fun root(): File = folder.newFolder("storage").also {
        ShadowEnvironment.setExternalStorageDirectory(it.toPath())
    }

    private fun withMain(directory: File, block: (MainActivity) -> Unit) {
        assertTrue(app.getSharedPreferences("saf_prefs", 0).edit()
            .putString("current_path", directory.path).putBoolean("storage_permission_prompted", true).commit())
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller: ActivityController<MainActivity> ->
            awaitDirectory(controller.get())
            block(controller.get())
        }
    }

    private fun initializeFromDialogs(activity: androidx.appcompat.app.AppCompatActivity) {
        typePin(pin)
        typePin("654321")
        assertEquals(activity.getString(R.string.privacy_pin_mismatch), status())
        typePin(pin)
        draw(pattern.take(3))
        assertEquals(activity.getString(R.string.privacy_pattern_length), status())
        draw(pattern)
        draw(otherPattern)
        assertEquals(activity.getString(R.string.privacy_pattern_mismatch), status())
        draw(pattern)
        awaitAuth(activity)
        assertEquals(PrivacyStore.CredentialState.READY, PrivacyStore.credentialState(app))
    }

    private fun listedFiles(activity: MainActivity): List<File> {
        val list = activity.findViewById<ListView>(R.id.listFiles)
        return (0 until list.adapter.count).mapNotNull { (list.getItemAtPosition(it) as FileItem).file }
    }

    private fun privacyDirectoryAction(activity: MainActivity, directory: File): TextView {
        val list = activity.findViewById<ListView>(R.id.listFiles)
        val position = (0 until list.adapter.count)
            .first { (list.getItemAtPosition(it) as FileItem).file == directory }
        assertTrue(list.onItemLongClickListener.onItemLongClick(list, null, position, position.toLong()))
        return currentMenu(activity).findViewById<TextView>(R.id.btnPrivacyDirectoryAction)!!.also {
            assertEquals(View.VISIBLE, it.visibility)
        }
    }

    private fun currentMenu(activity: MainActivity): BottomSheetDialog =
        ReflectionHelpers.getField(activity, "browserItemMenu")

    private fun authController(activity: androidx.appcompat.app.AppCompatActivity): PrivacyAuthDialogController =
        ReflectionHelpers.callInstanceMethod(activity, "getPrivacyAuth")

    private fun awaitAuthWorker(activity: androidx.appcompat.app.AppCompatActivity) {
        val worker = ReflectionHelpers.getField<ExecutorService>(authController(activity), "worker")
        worker.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun awaitAuth(activity: androidx.appcompat.app.AppCompatActivity) {
        awaitAuthWorker(activity)
        idle()
    }

    private fun cancelQueuedAuthWithSystemBack(
        activity: androidx.appcompat.app.AppCompatActivity,
        submit: () -> Unit
    ) {
        val worker = ReflectionHelpers.getField<ExecutorService>(authController(activity), "worker")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = worker.submit {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val dialog = authDialog()
            submit()
            dialog.onBackPressed()
            assertFalse(dialog.isShowing)
        } finally {
            release.countDown()
        }
        blocker.get(10, TimeUnit.SECONDS)
        // The write gets to run before any posted cancel or dismiss listeners.
        awaitAuthWorker(activity)
    }

    private fun awaitDirectory(activity: MainActivity) {
        activity.directoryLoadExecutorForTest.submit {}.get(10, TimeUnit.SECONDS)
        idle()
    }

    private fun authDialog(): AlertDialog = (ShadowDialog.getLatestDialog() as AlertDialog).also {
        assertTrue(it.isShowing)
    }

    private fun typePin(value: String) {
        val dialog = authDialog()
        dialog.findViewById<EditText>(R.id.inputPrivacyPin).setText(value)
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertTrue(submit.isEnabled)
        submit.performClick()
    }

    private fun status(): String =
        authDialog().findViewById<TextView>(R.id.tvPrivacyCredentialMessage).text.toString()

    private fun draw(nodes: List<Int>) {
        val view = authDialog().findViewById<PatternLockView>(R.id.privacyPattern)
        assertTrue(view.isEnabled)
        view.layout(0, 0, 300, 300)
        nodes.forEachIndexed { index, node ->
            val action = if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            withTouch(action, 50f + 100 * (node % 3), 50f + 100 * (node / 3), view::onTouchEvent)
        }
        val last = nodes.last()
        withTouch(MotionEvent.ACTION_UP, 50f + 100 * (last % 3), 50f + 100 * (last / 3), view::onTouchEvent)
    }

    private fun pull(activity: MainActivity, horizontal: Boolean = false): Boolean {
        val layout = activity.findViewById<PrivacyPullDownLayout>(R.id.privacyPullDownLayout)
        withTouch(MotionEvent.ACTION_DOWN, 30f, 30f, layout::onInterceptTouchEvent)
        val x = if (horizontal) 310f else 32f
        val y = if (horizontal) 45f else 310f
        val intercept = withTouch(MotionEvent.ACTION_MOVE, x, y, layout::onInterceptTouchEvent)
        if (intercept) withTouch(MotionEvent.ACTION_UP, x, y, layout::onTouchEvent)
        return intercept
    }

    private fun withTouch(action: Int, x: Float, y: Float, deliver: (MotionEvent) -> Boolean): Boolean {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        return try { deliver(event) } finally { event.recycle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun isSecure(window: android.view.Window) =
        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
