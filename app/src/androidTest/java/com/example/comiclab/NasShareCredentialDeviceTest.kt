package com.example.comiclab

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Environment
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withInputType
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.core.nas.NasDirectoryEntry
import com.example.comiclab.core.nas.NasSharePasswordRequiredException
import com.example.comiclab.core.nas.NasShareProfile
import com.example.comiclab.core.nas.NasShareStore
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NasShareCredentialDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "cl002_${UUID.randomUUID()}_"
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            app.getSharedPreferences(namespace + name, mode)
    }

    @After
    fun clearIsolatedPreferences() {
        app.deleteSharedPreferences(namespace + "nas_share_settings")
    }

    @Test
    fun changingHostStoresOnlyTheNewPasswordAndKeepsTheActiveShare() {
        val before = seedShare()
        NasShareStore.setActiveShare(context, before.id)
        val changed = before.copy(host = "changed.invalid")

        assertThrows(NasSharePasswordRequiredException::class.java) {
            NasShareStore.save(context, changed, "", rememberPassword = true)
        }
        assertEquals(before, NasShareStore.load(context).single())

        NasShareStore.save(context, changed, "fixture-new-password", rememberPassword = true)
        val saved = NasShareStore.load(context).single()
        assertEquals(changed.host, saved.host)
        assertNotEquals(before.encryptedPassword, saved.encryptedPassword)
        assertEquals("fixture-new-password", NasShareStore.rememberedPassword(saved))
        assertEquals(before.id, NasShareStore.activeShareId(context))
    }

    @Test
    fun changingShareKeepsPasswordThenChangingAccountReplacesItAndAnonymousClearsIt() {
        val before = seedShare()
        NasShareStore.save(context, before.copy(name = "renamed", shareName = "other-books"), "", true)
        val renamed = NasShareStore.load(context).single()
        assertEquals(before.encryptedPassword, renamed.encryptedPassword)
        assertEquals("fixture-old-password", NasShareStore.rememberedPassword(renamed))

        NasShareStore.save(context, renamed.copy(username = "other-reader"), "fixture-account-password", true)
        val changed = NasShareStore.load(context).single()
        assertEquals("other-reader", changed.username)
        assertEquals("fixture-account-password", NasShareStore.rememberedPassword(changed))

        NasShareStore.save(context, changed.copy(username = ""), "", rememberPassword = true)
        val anonymous = NasShareStore.load(context).single()
        assertEquals("", anonymous.username)
        assertNull(anonymous.encryptedPassword)
        assertNull(NasShareStore.rememberedPassword(anonymous))
    }

    @Test
    fun browsesAndDownloadsFromIsolatedSmbFixture() {
        val mode = InstrumentationRegistry.getArguments().getString("nasFixtureMode")
        org.junit.Assume.assumeTrue(mode in listOf("saved-session", "prompt", "anonymous"))
        val password = "comiclab-fixture-only"
        val fixture = NasShareProfile(UUID.randomUUID().toString(), "Fixture", "127.0.0.1",
            "BOOKS", if (mode == "anonymous") "" else "fixture", false)
        val preferences = app.getSharedPreferences("nas_share_settings", Context.MODE_PRIVATE)
        check(preferences.all.isEmpty()) { "This test requires an isolated app instance" }
        val destination = "Download/ComicLab-CL027-${fixture.id}"
        val outputDir = File(Environment.getExternalStorageDirectory(), destination)
        val output = File(outputDir, "sample.cbz")
        try {
            NasShareStore.save(app, fixture, if (mode == "saved-session") password else "", false)
            NasShareStore.setActiveShare(app, fixture.id)
            NasShareStore.setNasEnabled(app, true)
            NasShareStore.setDownloadPath(app, destination)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.btnSourceNas).performClick() }
                if (mode == "prompt") {
                    awaitFixture("password entry button is unavailable") {
                        var visible = false
                        scenario.onActivity { visible = it.findViewById<View>(R.id.btnNasRetry).isShown }
                        visible
                    }
                    scenario.onActivity { it.findViewById<View>(R.id.btnNasRetry).performClick() }
                    onView(withInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
                        .inRoot(isDialog()).perform(replaceText(password))
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                }
                awaitFixture("SMB subdirectory did not appear") { clickFixtureEntry(scenario, "shelf") }
                awaitFixture("SMB archive did not appear") { clickFixtureEntry(scenario, "sample.cbz") }
                onView(withId(R.id.btnDownloadNasArchiveToLocal)).inRoot(isDialog()).perform(object : ViewAction {
                    override fun getConstraints() = isClickable()
                    override fun getDescription() = "Select download from the NAS action sheet"
                    override fun perform(uiController: UiController, view: View) {
                        check(Environment.isExternalStorageManager()) { "Isolated emulator needs storage access" }
                        check(view.performClick())
                    }
                })
                awaitFixture("SMB archive was not downloaded", timeoutMs = 35_000) {
                    output.isFile && output.length() > 0
                }
                ZipFile(output).use { zip ->
                    assertEquals("CL-027 fixture", zip.getInputStream(zip.getEntry("sample.txt")).bufferedReader().use { it.readText() })
                }
                assertNull(NasShareStore.load(app).single().encryptedPassword)
                check(!preferences.getString("shares_json", "").orEmpty().contains(password))
            }
        } finally {
            output.delete()
            outputDir.delete()
            NasShareStore.delete(app, fixture.id)
            check(preferences.edit().clear().commit())
        }
    }

    private fun clickFixtureEntry(scenario: ActivityScenario<MainActivity>, name: String): Boolean {
        var clicked = false
        scenario.onActivity { activity ->
            val list = activity.findViewById<ListView>(R.id.listNasFiles)
            val adapter = list.adapter
            if (!list.isShown || adapter == null) return@onActivity
            val index = (0 until adapter.count).firstOrNull {
                (adapter.getItem(it) as? NasDirectoryEntry)?.name == name
            } ?: return@onActivity
            val entry = adapter.getItem(index) as NasDirectoryEntry
            check(entry.isDirectory || entry.sizeBytes > 0) { "SMB fixture reports an empty archive" }
            clicked = list.performItemClick(list.getChildAt(index), index, adapter.getItemId(index))
        }
        return clicked
    }

    private fun awaitFixture(message: String, timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        check(condition()) { message }
    }

    private fun seedShare(): NasShareProfile {
        val profile = NasShareProfile(
            id = "fixture",
            name = "test share",
            host = "original.invalid",
            shareName = "books",
            username = "reader",
            rememberPassword = true
        )
        NasShareStore.save(context, profile, "fixture-old-password", rememberPassword = true)
        return NasShareStore.load(context).single()
    }
}
