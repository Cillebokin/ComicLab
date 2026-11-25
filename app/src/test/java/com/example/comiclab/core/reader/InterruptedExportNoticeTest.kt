package com.example.comiclab.core.reader

import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.provider.DocumentsContract
import com.example.comiclab.MainActivity
import com.example.comiclab.R
import com.example.comiclab.ShadowManageExternalStorage
import com.example.comiclab.core.store.PrivacyStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val root = folder.newFolder("external")
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val source = File(root, "ordinary.cbz").apply { writeText("fixture") }
        val preferences = app.getSharedPreferences("reader_export_documents", 0)
        val document = "content://fixture.documents/document/export"
        val record = JSONObject().put("name", "interrupted.cbz").put("writing", false)
            .put("sourcePath", source.canonicalPath)
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

    @Test fun onlyOrdinaryIdentifiableRecordsDisplayTheirName() {
        val app = RuntimeEnvironment.getApplication()
        val root = folder.newFolder("external")
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val hidden = File(root, "private").apply { mkdir() }
        val privateSource = File(hidden, "book.cbz").apply { writeText("fixture") }
        val ordinarySource = File(root, "ordinary.cbz").apply { writeText("fixture") }
        val preferences = app.getSharedPreferences("reader_export_documents", 0)
        fun record(key: String, name: String, path: String? = null) {
            val value = JSONObject().put("name", name).put("writing", true)
            if (path != null) value.put("sourcePath", path)
            assertTrue(preferences.edit().putString(key, value.toString()).commit())
        }
        record("content://fixture.documents/document/public", "visible.cbz", ordinarySource.canonicalPath)
        record("content://fixture.documents/document/legacy", "legacy-secret.cbz")
        record("content://fixture.documents/document/changed", "changed-secret.cbz", privateSource.canonicalPath)
        record("content://fixture.documents/document/invalid", "invalid-secret.cbz", "relative.cbz")
        val localTarget = Uri.Builder().scheme("content").authority("com.android.externalstorage.documents")
            .appendPath("document").appendPath("primary:private/target-secret.cbz").build()
        record(localTarget.toString(), "target-secret.cbz", ordinarySource.canonicalPath)
        assertTrue(PrivacyStore.addPrivacyDirectory(app, hidden))

        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val message = dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("visible.cbz"))
            assertFalse(message.contains("secret"))
            assertFalse(message.contains("content://"))
            assertTrue(message.contains("4"))
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ExportDocumentJournal(app).interruptedExports().isEmpty())
        }
    }

    @Test
    fun downloadsTargetsWithLocalPathsAreCheckedBeforeDisplayingNames() {
        val app = RuntimeEnvironment.getApplication()
        val root = folder.newFolder("external")
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        val source = File(root, "ordinary.cbz").apply { writeText("fixture") }
        val downloads = File(root, "Download")
        val privateFolder = File(downloads, "private folder").apply { check(mkdirs()) }
        val preferences = app.getSharedPreferences("reader_export_documents", 0)
        fun record(documentId: String, name: String) {
            val uri = DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", documentId)
            val value = JSONObject().put("name", name).put("writing", true)
                .put("sourcePath", source.canonicalPath)
            assertTrue(preferences.edit().putString(uri.toString(), value.toString()).commit())
        }
        record("raw:${File(privateFolder, "target.cbz").absolutePath}", "private-target-export.cbz")
        record("raw:${File(downloads, "target.cbz").absolutePath}", "ordinary-target-export.cbz")
        record("raw:relative.cbz", "invalid-target-export.cbz")
        record("123", "opaque-target-export.cbz")
        assertTrue(PrivacyStore.addPrivacyDirectory(app, privateFolder))

        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val message = dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("ordinary-target-export.cbz"))
            assertTrue(message.contains("opaque-target-export.cbz"))
            assertFalse(message, message.contains("private-target-export.cbz"))
            assertFalse(message, message.contains("invalid-target-export.cbz"))
            assertTrue(message.contains(app.getString(R.string.reader_export_hidden_count, 2)))
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
