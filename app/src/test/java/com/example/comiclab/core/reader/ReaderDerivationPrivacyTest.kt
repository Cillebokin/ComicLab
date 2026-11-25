package com.example.comiclab.core.reader

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import com.example.comiclab.R
import com.example.comiclab.core.archive.ComicConversionTarget
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.core.reader.translation.ArchiveTranslation
import com.example.comiclab.core.reader.translation.TranslationSettings
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ui.reader.conversion.ComicFormatConversionViewModel
import com.example.comiclab.ui.reader.enhance.EnhancementExportViewModel
import com.example.comiclab.ui.reader.enhance.EnhancementExportActivity
import com.example.comiclab.ui.privacy.PrivacySessionManager
import com.example.comiclab.ui.reader.translation.ArchiveTranslationExportViewModel
import com.example.comiclab.ui.reader.translation.PageTranslationActivity
import androidx.lifecycle.ViewModelProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderDerivationPrivacyTest {
    @get:Rule val storage = TemporaryFolder()

    @After fun resetSession() {
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test fun privatePageTranslationActivityRejectsTheSourceBeforePreview() {
        val app = RuntimeEnvironment.getApplication()
        val archive = storage.newFile("hidden.cbz")
        assertTrue(PrivacyStore.addPrivacyDirectory(app, archive.parentFile!!))
        val intent = Intent(app, PageTranslationActivity::class.java)
            .putExtra("archive", archive.absolutePath).putExtra("entry", "page.png")
        Robolectric.buildActivity(PageTranslationActivity::class.java, intent).setup().use {
            assertTrue(it.get().isFinishing)
        }
    }

    @Test fun blockedExportCallbacksDeleteOnlyConfirmedEmptyDestinations() {
        val app = RuntimeEnvironment.getApplication()
        val archive = storage.newFile("hidden.cbz")
        assertTrue(PrivacyStore.addPrivacyDirectory(app, archive.parentFile!!))
        val deleted = AtomicInteger()
        val authority = "derivation.fixture"
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                deleted.incrementAndGet()
                return 1
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun getType(uri: Uri): String? = null
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply {
                    addRow(arrayOf(when (uri.lastPathSegment) {
                        "existing" -> 2048L
                        "unknown" -> null
                        else -> 0L
                    }))
                }
        })
        val translation = ArchiveTranslationExportViewModel(app)
        val conversion = ComicFormatConversionViewModel(app)
        val enhancement = EnhancementExportViewModel(app)
        try {
            translation.start(archive, Uri.parse("content://$authority/document/empty1"), "zh")
            conversion.start(archive, Uri.parse("content://$authority/document/empty2"), ComicConversionTarget.PDF)
            enhancement.start(archive, Uri.parse("content://$authority/document/existing"), book = true)
            ReaderDerivationPrivacy.abandonRejectedDestination(app, Uri.parse("content://$authority/document/unknown"))
            assertEquals(R.string.privacy_operation_denied, translation.state.value?.issueMessage)
            assertEquals(R.string.privacy_operation_denied, conversion.state.value?.issueMessage)
            assertEquals(R.string.privacy_operation_denied, enhancement.state.value?.issueMessage)
            EnhancementRuntime.io.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(2, deleted.get())
            assertEquals(setOf("content://$authority/document/existing", "content://$authority/document/unknown"),
                ExportDocumentJournal(app).interruptedExports().map { it.uri }.toSet())
        } finally {
            translation.cancelled.set(true)
            conversion.cancelled.set(true)
            enhancement.cancelled.set(true)
            EnhancementRuntime.worker.submit {}.get(5, TimeUnit.SECONDS)
            EnhancementRuntime.io.submit {}.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun privateArchiveIsRejectedBeforeTranslationRequests() {
        val app = RuntimeEnvironment.getApplication()
        val source = storage.newFile("hidden.cbz")
        assertTrue(PrivacyStore.addPrivacyDirectory(app, source.parentFile!!))
        val destination = storage.newFolder("translation-task")
        var requests = 0
        assertThrows(CancellationException::class.java) {
            ArchiveTranslation.translate(app, source, destination, AtomicBoolean(), "English",
                TranslationSettings("https://fixture.invalid", "fixture", "fixture"),
                translate = { _, _, _, _ -> requests++; emptyList() }) { _, _, _, _, _, _ -> }
        }
        assertEquals(0, requests)
        assertTrue(destination.list().orEmpty().isEmpty())
    }

    @Test fun lateImagePickerResultRevalidatesOriginalAndDoesNotWrite() {
        verifyImagePickerResult(markSourcePrivate = true)
    }

    @Test fun ordinaryImagePickerResultKeepsExistingExportBehavior() {
        verifyImagePickerResult(markSourcePrivate = false)
    }

    private fun verifyImagePickerResult(markSourcePrivate: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val shelf = storage.newFolder("shelf")
        val archive = File(shelf, "book.cbz").apply { writeText("fixture") }
        val staging = File(app.cacheDir, "enhancement-exports").apply { mkdirs() }
        val pixels = byteArrayOf(1, 2, 3)
        val image = File(staging, "page.png").apply { writeBytes(pixels) }
        val authority = if (markSourcePrivate) "blocked-image-picker.fixture" else "ordinary-image-picker.fixture"
        val destination = Uri.parse("content://$authority/document/page")
        val written = ByteArrayOutputStream()
        val opens = AtomicInteger()
        val deletes = AtomicInteger()
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                deletes.incrementAndGet()
                return 1
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun getType(uri: Uri): String? = null
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf(0L)) }
        })
        shadowOf(app.contentResolver).registerOutputStreamSupplier(destination) { opens.incrementAndGet(); written }
        val launch = Robolectric.buildActivity(Activity::class.java).setup().use {
            val activity = it.get()
            EnhancementExportActivity.launchImage(activity, image, "page.png", archive, archive.canonicalPath)
            checkNotNull(shadowOf(activity).nextStartedActivity)
        }

        Robolectric.buildActivity(EnhancementExportActivity::class.java, launch).setup().use { controller ->
            val activity = controller.get()
            val shadow = shadowOf(activity)
            val model = ViewModelProvider(activity)[EnhancementExportViewModel::class.java]
            val picker = checkNotNull(shadow.nextStartedActivityForResult)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.action)
            if (markSourcePrivate) {
                assertTrue(PrivacyStore.addPrivacyDirectory(app, shelf))
                assertEquals(null, ReaderDerivationPrivacy.ordinaryPath(activity, archive))
            }
            PrivacySessionManager.lock()
            assertEquals(0, opens.get())
            assertTrue(image.isFile)
            assertEquals(archive.absolutePath, activity.intent.getStringExtra("enhancement_original_source"))
            shadow.receiveResult(picker.intent, Activity.RESULT_OK, Intent().setData(destination))
            shadowOf(Looper.getMainLooper()).idle()
            EnhancementRuntime.io.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()

            if (markSourcePrivate) {
                assertEquals(R.string.privacy_operation_denied, model.state.value?.issueMessage)
                assertEquals(0, opens.get())
                assertEquals(1, deletes.get())
                assertTrue(written.toByteArray().isEmpty())
            } else {
                assertEquals(1, opens.get())
                assertEquals(0, deletes.get())
                assertArrayEquals(pixels, written.toByteArray())
            }
            assertFalse(image.exists())
            assertTrue(archive.isFile)
            assertTrue(ExportDocumentJournal(app).interruptedExports().isEmpty())
        }
    }
}
