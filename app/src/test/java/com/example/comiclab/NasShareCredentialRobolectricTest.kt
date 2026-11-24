package com.example.comiclab

import android.content.Context
import android.content.Intent
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.comiclab.core.nas.NasShareStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NasShareCredentialRobolectricTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun seedShare() {
        val share = JSONObject()
            .put("id", "nas-test")
            .put("name", "before")
            .put("host", "original.invalid")
            .put("shareName", "books")
            .put("username", "LAB\\reader")
            .put("rememberPassword", true)
            .put("encryptedPassword", "test-marker")
        val preferences = context.getSharedPreferences("nas_share_settings", Context.MODE_PRIVATE)
        check(preferences.edit().clear().putString("shares_json", JSONArray().put(share).toString()).commit())
    }

    @Test
    fun changingHostWithoutEnteringPasswordKeepsStoredShare() {
        val before = NasShareStore.load(context).single()
        assertThrows(IllegalArgumentException::class.java) {
            NasShareStore.save(context, before.copy(host = "other.invalid"), "", rememberPassword = true)
        }
        assertEquals(before, NasShareStore.load(context).single())
    }

    @Test
    fun changingAccountRequiresPasswordEvenWhenTurningOffRemembering() {
        val before = NasShareStore.load(context).single()
        assertThrows(IllegalArgumentException::class.java) {
            NasShareStore.save(context, before.copy(username = "OTHER\\reader"), "", rememberPassword = false)
        }
        assertEquals(before, NasShareStore.load(context).single())
    }

    @Test
    fun keepsPasswordForDisplayNameOrShareFolderEditOnSameAccount() {
        val before = NasShareStore.load(context).single()
        val renamed = before.copy(name = "after", shareName = "other-books")
        NasShareStore.save(context, renamed, "", rememberPassword = true)
        assertEquals(renamed, NasShareStore.load(context).single())
    }

    @Test
    fun switchingToAnonymousClearsSavedPasswordWithoutRequiringNewPassword() {
        val before = NasShareStore.load(context).single()
        NasShareStore.save(context, before.copy(username = ""), "", rememberPassword = true)
        val saved = NasShareStore.load(context).single()
        assertEquals("", saved.username)
        assertNull(saved.encryptedPassword)
    }

    @Test
    fun unrememberedPasswordIsSharedInMemoryOnlyAndScopedToTheCurrentAccount() {
        val before = NasShareStore.load(context).single()
        val password = "session-only-fixture"
        try {
            NasShareStore.save(context, before, password, rememberPassword = false)
            val saved = NasShareStore.load(context).single()
            assertNull(saved.encryptedPassword)
            assertEquals(password, NasShareStore.accessPassword(saved))
            assertNull(NasShareStore.accessPassword(saved.copy(host = "different.invalid")))
            assertNull(NasShareStore.accessPassword(saved.copy(username = "different")))
            NasShareStore.save(context, saved.copy(name = "renamed"), "", rememberPassword = false)
            assertEquals(password, NasShareStore.accessPassword(NasShareStore.load(context).single()))

            NasShareStore.setSessionPassword(saved, "new-session-password")
            NasShareStore.discardRejectedSessionPassword(saved, password)
            assertEquals("new-session-password", NasShareStore.accessPassword(saved))
            NasShareStore.discardRejectedSessionPassword(saved, "new-session-password")
            assertNull(NasShareStore.accessPassword(saved))

            NasShareStore.setSessionPassword(saved, password)
            NasShareStore.save(context, saved.copy(username = ""), "", rememberPassword = false)
            assertEquals("", NasShareStore.accessPassword(NasShareStore.load(context).single()))
            assertNull(NasShareStore.accessPassword(saved))
        } finally {
            NasShareStore.delete(context, before.id)
        }
    }

    @Test
    fun editorDoesNotTestOrSaveChangedHostWithBlankPassword() {
        withEditor { activity, dialog ->
            val host = checkNotNull(dialog.findViewById<EditText>(R.id.inputNasHost))
            val status = checkNotNull(dialog.findViewById<TextView>(R.id.tvNasFormStatus))
            val before = NasShareStore.load(context).single()
            host.setText("other.invalid")

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals(activity.getString(R.string.nas_test_password_required), status.text.toString())
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(activity.getString(R.string.nas_changed_credentials_password_required), status.text.toString())
            assertTrue(dialog.isShowing)
            assertEquals(before, NasShareStore.load(context).single())
        }
    }

    @Test
    fun editorHintFollowsAccountAndRememberingChoice() {
        withEditor { activity, dialog ->
            val username = checkNotNull(dialog.findViewById<EditText>(R.id.inputNasUsername))
            val password = checkNotNull(dialog.findViewById<EditText>(R.id.inputNasPassword))
            val remember = checkNotNull(dialog.findViewById<CheckBox>(R.id.checkRememberNasPassword))
            val originalUsername = username.text.toString()
            assertEquals(activity.getString(R.string.nas_password_keep_hint), password.hint.toString())

            username.setText("OTHER\\reader")
            assertEquals(activity.getString(R.string.nas_password_hint), password.hint.toString())
            username.setText(originalUsername)
            assertEquals(activity.getString(R.string.nas_password_keep_hint), password.hint.toString())

            remember.isChecked = false
            assertEquals(activity.getString(R.string.nas_password_hint), password.hint.toString())
        }
    }

    private fun withEditor(block: (SettingsActivity, AlertDialog) -> Unit) {
        val intent = Intent(context, SettingsActivity::class.java).putExtra(
            SettingsActivity.EXTRA_SETTINGS_SECTION,
            SettingsActivity.SETTINGS_SECTION_NAS
        )
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        val activity = controller.get()
        try {
            activity.findViewById<TextView>(R.id.btnNasShareEdit).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            try {
                block(activity, dialog)
            } finally {
                dialog.dismiss()
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
