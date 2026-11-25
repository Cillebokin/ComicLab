package com.example.comiclab

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.comiclab.core.archive.cache.PreparedReaderCacheLeaseRegistry
import com.example.comiclab.core.store.EbookProgressStore
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.ebook.EbookProgress
import com.example.comiclab.ui.privacy.PrivacySessionManager
import com.example.comiclab.ui.privacy.ReaderSourcePrivacy
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
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
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderPrivacyRobolectricTest {
    @get:Rule val storage = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    @After fun resetSession() {
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test fun allReaderPagesRejectPrivateSourceWhenLocked() {
        val privateFolder = privateFolder()
        for ((page, extension) in pages) {
            val source = File(privateFolder, "locked.$extension").apply { writeText("fixture") }
            Robolectric.buildActivity(page, readerIntent(page, source)).setup().use { controller ->
                assertTrue(page.simpleName, controller.get().isFinishing)
                assertHidden(controller.get())
            }
        }
    }

    @Test fun allPrivateReaderPagesSecureTheWindowAndCloseOnLock() {
        val privateFolder = privateFolder()
        for ((page, extension) in pages) {
            val epoch = enterPrivacySpace()
            val source = File(privateFolder, "reading.$extension").apply { writeText("fixture") }
            Robolectric.buildActivity(page, readerIntent(page, source, epoch)).setup().use { controller ->
                val activity = controller.get()
                assertFalse(page.simpleName, activity.isFinishing)
                assertTrue(page.simpleName, isSecure(activity))
                PrivacySessionManager.lock()
                assertTrue(page.simpleName, activity.isFinishing)
                assertEquals(View.INVISIBLE, activity.window.decorView.visibility)
                assertTrue(isSecure(activity))
            }
            PrivacySessionManager.consumePendingNormalRestorePath()
        }
    }

    @Test fun newSessionRejectsOldIntentEvenAfterUnlock() {
        val source = File(privateFolder(), "restored.txt").apply { writeText("fixture") }
        val oldIntent = readerIntent(EbookPreviewActivity::class.java, source, enterPrivacySpace())
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
        enterPrivacySpace()
        Robolectric.buildActivity(EbookPreviewActivity::class.java, oldIntent).setup().use {
            assertTrue(it.get().isFinishing)
            assertHidden(it.get())
        }
    }

    @Test fun staleProcessStampCannotOpenPrivateSourceWithMatchingEpoch() {
        val source = File(privateFolder(), "restored.txt").apply { writeText("fixture") }
        val intent = readerIntent(EbookPreviewActivity::class.java, source, enterPrivacySpace())
            .putExtra("reader_privacy_process", "previous-process")
        Robolectric.buildActivity(EbookPreviewActivity::class.java, intent).setup().use {
            assertTrue(it.get().isFinishing)
            assertHidden(it.get())
        }
    }

    @Test fun ebookPreviewRecreationAndReaderLaunchUseTheSamePrivateSession() {
        val source = File(privateFolder(), "chapter.txt").apply { writeText("Private chapter content.") }
        val intent = readerIntent(EbookPreviewActivity::class.java, source, enterPrivacySpace())
        Robolectric.buildActivity(EbookPreviewActivity::class.java, intent).setup().use { preview ->
            preview.recreate()
            val activity = preview.get()
            val previewExecutor = ReflectionHelpers.getField<ExecutorService>(activity, "previewExecutor")
            awaitWorker(previewExecutor)
            assertFalse(activity.isFinishing)
            assertTrue(isSecure(activity))
            val read = activity.findViewById<View>(R.id.btnEbookReadFromBeginning)
            assertEquals(View.VISIBLE, read.visibility)
            read.performClick()
            val reader = checkNotNull(shadowOf(activity).nextStartedActivity)
            Robolectric.buildActivity(EbookReaderActivity::class.java, reader).setup().use {
                assertFalse(it.get().isFinishing)
                assertTrue(isSecure(it.get()))
            }
            PrivacySessionManager.lock()
            assertTrue(activity.isFinishing)
            assertHidden(activity)
        }
    }

    @Test fun privatePreparedArchiveHandsOffToSecureReader() {
        val source = writePrivateArchive()
        val intent = readerIntent(MangaReaderPrepareActivity::class.java, source, enterPrivacySpace())
        Robolectric.buildActivity(MangaReaderPrepareActivity::class.java, intent).setup().use { prepare ->
            val activity = prepare.get()
            awaitWorker(activity.prepareExecutorForTest)
            val readerIntent = checkNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(source.path, readerIntent.getStringExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH))
            assertTrue(isSecure(activity))
            Robolectric.buildActivity(MangaReaderActivity::class.java, readerIntent).setup().use { reader ->
                assertFalse(reader.get().isFinishing)
                assertTrue(isSecure(reader.get()))
                PrivacySessionManager.lock()
                assertTrue(reader.get().isFinishing)
                assertHidden(reader.get())
            }
        }
    }

    @Test fun lockedReaderRejectsOldPreparedHandoffAndReleasesItsCache() {
        withPendingPrivateHandoff { source, handoff ->
            PrivacySessionManager.lock()

            Robolectric.buildActivity(MangaReaderActivity::class.java, handoff).setup().use { reader ->
                assertTrue(reader.get().isFinishing)
                assertHidden(reader.get())
            }
            assertTrue(source.isFile)
        }
    }

    @Test fun missingArchiveAfterPreparationShowsErrorAndReleasesItsCache() {
        withPendingPrivateHandoff { source, handoff ->
            val movedArchive = File(source.parentFile, "moved.cbz")
            assertTrue(source.renameTo(movedArchive))
            assertTrue(PrivacySessionManager.isUnlocked)

            Robolectric.buildActivity(MangaReaderActivity::class.java, handoff).setup().use { reader ->
                val activity = reader.get()
                assertFalse(activity.isFinishing)
                assertTrue(isSecure(activity))
                val status = activity.findViewById<TextView>(R.id.tvReaderStatus)
                assertEquals(View.VISIBLE, status.visibility)
                assertEquals(activity.getString(R.string.message_invalid_file), status.text.toString())
            }
            assertTrue(movedArchive.isFile)
        }
    }

    private fun withPendingPrivateHandoff(rejectReader: (File, Intent) -> Unit) {
        val source = writePrivateArchive()
        val opening = readerIntent(MangaReaderPrepareActivity::class.java, source, enterPrivacySpace())
        val handoff = Robolectric.buildActivity(MangaReaderPrepareActivity::class.java, opening).setup().use { prepare ->
            val activity = prepare.get()
            awaitWorker(activity.prepareExecutorForTest)
            assertTrue(activity.isFinishing)
            checkNotNull(shadowOf(activity).nextStartedActivity)
        }
        val cache = File(checkNotNull(handoff.getStringExtra(MangaReaderActivity.EXTRA_PREPARED_READER_CACHE_DIR)))
        try {
            assertTrue("The preparation must have written private pages", cache.listFiles().orEmpty().any { it.extension == "png" })
            rejectReader(source, handoff)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (cache.exists() && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse("Rejected readers must release the cached private pages", cache.exists())
        } finally {
            if (cache.exists()) {
                PreparedReaderCacheLeaseRegistry.process.acquireReaderOwner(cache)?.requestDeleteAndClose()
            }
        }
    }

    @Test fun markingAnOpenOrdinaryBookClosesItWithoutWaitingForResume() {
        val ordinaryFolder = File(privateFolder().parentFile, "ordinary").apply { mkdir() }
        val source = File(ordinaryFolder, "open.txt").apply { writeText("fixture") }
        Robolectric.buildActivity(EbookPreviewActivity::class.java, readerIntent(EbookPreviewActivity::class.java, source)).setup().use {
            assertFalse(isSecure(it.get()))
            assertTrue(PrivacyStore.addPrivacyDirectory(context, ordinaryFolder))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(it.get().isFinishing)
            assertTrue(isSecure(it.get()))
            assertEquals(View.INVISIBLE, it.get().window.decorView.visibility)
        }
    }

    @Test fun ordinaryReaderOpenedDuringPrivateSessionKeepsOrdinaryProgressRules() {
        val source = File(privateFolder().parentFile, "ordinary.txt").apply { writeText("fixture") }
        enterPrivacySpace()
        AppSettings.setIncognitoModeEnabled(context, true)
        Robolectric.buildActivity(EbookReaderActivity::class.java, readerIntent(EbookReaderActivity::class.java, source)).setup().use {
            val reader = it.get()
            assertFalse(isSecure(reader))
            reader.progressForTest = EbookProgress(0, 0.25f)
            reader.saveProgressForTest()
            assertNull(EbookProgressStore.load(context, source))
            PrivacySessionManager.lock()
            assertFalse(reader.isFinishing)
            assertFalse(isSecure(reader))
            AppSettings.setIncognitoModeEnabled(context, false)
            reader.saveProgressForTest()
            assertEquals(0.25f, EbookProgressStore.load(context, source)?.scrollFraction ?: -1f, 0.001f)
        }
    }

    @Test fun lockingPrivateReaderSavesItsLastAcceptedPositionOnlyWhenIncognitoIsOff() {
        val directory = privateFolder()
        for (incognito in listOf(false, true)) {
            val source = File(directory, "progress-$incognito.txt").apply { writeText("fixture") }
            AppSettings.setIncognitoModeEnabled(context, incognito)
            val intent = readerIntent(EbookReaderActivity::class.java, source, enterPrivacySpace())
            Robolectric.buildActivity(EbookReaderActivity::class.java, intent).setup().use {
                it.get().progressForTest = EbookProgress(0, 0.25f)
                PrivacySessionManager.lock()
                assertHidden(it.get())
                val saved = EbookProgressStore.load(context, source)
                if (incognito) assertNull(saved)
                else assertEquals(0.25f, saved?.scrollFraction ?: -1f, 0.001f)
            }
            PrivacySessionManager.consumePendingNormalRestorePath()
        }
    }

    private fun privateFolder(): File {
        val root = storage.newFolder("library")
        ShadowEnvironment.setExternalStorageDirectory(root.toPath())
        return File(root, "private").apply {
            assertTrue(mkdir())
            assertTrue(PrivacyStore.addPrivacyDirectory(context, this))
        }
    }

    private fun enterPrivacySpace(): Long {
        assertTrue(PrivacySessionManager.enterPrivacySpace(
            Environment.getExternalStorageDirectory().path, PrivacySessionManager.sessionEpoch
        ))
        return PrivacySessionManager.sessionEpoch
    }

    private fun readerIntent(page: Class<out Activity>, source: File, epoch: Long? = null): Intent =
        Intent(context, page).apply {
            putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, source.absolutePath)
            if (epoch != null) {
                assertEquals(epoch, PrivacySessionManager.sessionEpoch)
                assertTrue(ReaderSourcePrivacy.authorizeLaunch(context, this, source))
            }
        }

    private fun isSecure(activity: Activity) =
        activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun assertHidden(activity: Activity) {
        assertTrue(isSecure(activity))
        assertEquals(View.INVISIBLE, activity.findViewById<View>(android.R.id.content).visibility)
    }

    private fun writePrivateArchive(): File {
        val archive = File(privateFolder(), "private.cbz")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page.png"))
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                zip.closeEntry()
            }
        } finally {
            bitmap.recycle()
        }
        return archive
    }

    private fun awaitWorker(executor: ExecutorService) {
        executor.submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val pages = listOf(
        MangaPreviewActivity::class.java to "cbz",
        MangaReaderPrepareActivity::class.java to "cbz",
        MangaReaderActivity::class.java to "cbz",
        EbookPreviewActivity::class.java to "txt",
        EbookReaderActivity::class.java to "txt"
    )
}
