package com.example.comiclab.core.reader

import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import com.example.comiclab.MainActivity
import com.example.comiclab.ShadowManageExternalStorage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ShadowManageExternalStorage::class])
class InterruptedExportNoticeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun previouslyReservedExportIsReportedOnLibraryResume() {
        val app = RuntimeEnvironment.getApplication()
        ShadowEnvironment.setExternalStorageDirectory(folder.newFolder("external").toPath())
        val preferences = app.getSharedPreferences("reader_export_documents", 0)
        val document = "content://fixture.documents/document/export"
        val record = JSONObject().put("name", "interrupted.cbz").put("writing", false)
        check(preferences.edit().putString(document, record.toString()).commit())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
            val message = dialog?.findViewById<android.widget.TextView>(android.R.id.message)
            assertTrue(message?.text?.contains("interrupted.cbz") == true)
            dialog!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ExportDocumentJournal(app).interruptedExports().isEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun activeExportsAreExcludedAndFailedCleanupRemainsReportable() {
        val journal = ExportDocumentJournal(RuntimeEnvironment.getApplication())
        val authority = "undeletable.fixture"
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun getType(uri: Uri): String? = null
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        })
        val destination = Uri.parse("content://$authority/document/reserved")
        val pending = checkNotNull(journal.reserve(destination, "reserved.cbz"))
        assertTrue(journal.interruptedExports().isEmpty())
        assertNull(journal.reserve(destination, "duplicate.cbz"))
        pending.abandon()
        val interrupted = journal.interruptedExports()
        assertEquals(listOf(ExportDocumentJournal.InterruptedExport(destination.toString(), "reserved.cbz", false)), interrupted)
        assertNull(journal.reserve(destination, "must-not-overwrite.cbz"))
        journal.acknowledge(interrupted)
        assertTrue(journal.interruptedExports().isEmpty())
    }
}
