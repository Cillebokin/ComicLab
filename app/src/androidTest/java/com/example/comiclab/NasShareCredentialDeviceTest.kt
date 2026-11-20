package com.example.comiclab

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.core.nas.NasSharePasswordRequiredException
import com.example.comiclab.core.nas.NasShareProfile
import com.example.comiclab.core.nas.NasShareStore
import java.util.UUID
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
