package com.example.comiclab

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.comiclab.core.reader.enhance.ArchiveSuperResolution
import com.example.comiclab.ui.reader.enhance.ArchiveEnhancementDialog
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveSuperResolutionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val temp = TemporaryFolder(context.cacheDir)

    @Test fun cancellingExportRemovesOnlyTheNewDestination() {
        val source = archive()
        val original = source.readBytes()
        val root = File(context.getExternalFilesDir(null), "cancelled-export-${System.nanoTime()}").apply { mkdirs() }
        val destination = File(root, "cancelled.cbz").apply { createNewFile() }
        val unrelated = File(root, "keep.txt").apply { writeText("keep") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
        val model = com.example.comiclab.ui.reader.enhance.EnhancementExportViewModel(context.applicationContext as android.app.Application)
        try {
            main {
                model.start(source, uri, true)
                model.cancelled.set(true)
            }
            await { !destination.exists() && !com.example.comiclab.core.reader.enhance.EnhancementRuntime.busy.get() }
            assertArrayEquals(original, source.readBytes())
            assertEquals("keep", unrelated.readText())
        } finally { model.cancelled.set(true); root.deleteRecursively() }
    }

    @Test fun realSystemPickerKeepsCbzExtension() {
        val source = archive()
        val chosenName = "${source.nameWithoutExtension}_HD.cbz"
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        var activity: SettingsActivity? = null
        var destination: Uri? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            main { ArchiveEnhancementDialog.show(activity!!, source) }
            var save: android.view.accessibility.AccessibilityNodeInfo? = null
            await {
                val root = automation.rootInActiveWindow ?: return@await false
                val candidates = root.findAccessibilityNodeInfosByViewId("android:id/button1") +
                    root.findAccessibilityNodeInfosByViewId("com.google.android.documentsui:id/button1") +
                    root.findAccessibilityNodeInfosByText("SAVE") + root.findAccessibilityNodeInfosByText("Save")
                save = candidates.firstOrNull { it.isClickable && it.isEnabled && it.packageName?.toString() == "com.google.android.documentsui" }
                save != null
            }
            assertTrue(save!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await { main {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                activity = monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<SettingsActivity>().firstOrNull()
                val host = activity ?: return@main false
                val fragment = host.supportFragmentManager.findFragmentByTag("archive-enhancement") ?: return@main false
                val model = androidx.lifecycle.ViewModelProvider(fragment)[com.example.comiclab.ui.reader.enhance.EnhancementExportViewModel::class.java]
                destination = model.target
                model.state.value?.done == true
            } }
            context.contentResolver.query(destination!!, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(chosenName, it.getString(0))
            }
            val local = temp.newFile("picker-result.cbz")
            context.contentResolver.openInputStream(destination!!)!!.use { input -> local.outputStream().use { input.copyTo(it) } }
            ZipFile(local).use { assertEquals(2, it.size()) }
        } finally {
            destination?.let { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, it) } }
            activity?.let { host -> main { host.finish() } }
        }
    }

    @Test fun savedPngAndCbzKeepRotatedJpegOrientation() {
        val jpeg = temp.newFile("rotated.jpg")
        val bitmap = Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { drawColor(Color.WHITE); drawRect(0f, 0f, 20f, 24f, Paint().apply { color = Color.BLACK }) }
            jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally { bitmap.recycle() }
        ExifInterface(jpeg.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            saveAttributes()
        }
        val page = com.example.comiclab.core.reader.enhance.PageSuperResolution(context)
            .enhance(jpeg, temp.newFolder(), AtomicBoolean(), false) { _, _ -> }
        assertEquals(6, ExifInterface(page.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
        val archive = temp.newFile("rotated.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.jpg"))
            jpeg.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        val result = ArchiveSuperResolution.enhance(context, archive, temp.newFolder(), AtomicBoolean(), false) { _, _, _ -> }
        ZipFile(result).use { zip ->
            val extracted = temp.newFile("exported.png")
            zip.getInputStream(zip.getEntry("000001.png")).use { input -> extracted.outputStream().use { input.copyTo(it) } }
            assertEquals(6, ExifInterface(extracted.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
        }
    }

    @Test fun realWholeBookKeepsPageOrderAndCancellationRemovesPartialBook() {
        val source = archive()
        val original = source.readBytes()
        val cancel = AtomicBoolean()
        val cancelledDir = temp.newFolder()
        assertThrows(CancellationException::class.java) {
            ArchiveSuperResolution.enhance(context, source, cancelledDir, cancel, preferGpu = false) { page, _, p ->
                if (page == 1 && p == 100) cancel.set(true)
            }
        }
        assertFalse(File(cancelledDir, "enhanced.cbz").exists())
        val completed = ArchiveSuperResolution.enhance(context, source, temp.newFolder(), AtomicBoolean(), false) { _, _, _ -> }
        ZipFile(completed).use { zip ->
            val pages = zip.entries().toList()
            assertEquals(listOf("000001.png", "000002.png"), pages.map { it.name })
            val images = pages.map { zip.getInputStream(it).use(BitmapFactory::decodeStream) }
            try {
                assertEquals(130, images[0].width)
                assertEquals(194, images[0].height)
                assertEquals(258, images[1].width)
                assertEquals(154, images[1].height)
            } finally { images.forEach(Bitmap::recycle) }
        }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun documentPickerUsesRequestedNameAndSavesCompleteNewComic() {
        val source = archive()
        val previousScale = AppSettings.getEnhancementScale(context)
        val original = source.readBytes()
        val targetDir = File(context.getExternalFilesDir(null), "enhancement-test-${System.nanoTime()}").apply { mkdirs() }
        val target = File(targetDir, "chosen-name.cbz").apply { createNewFile() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        val requested = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                requested.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        var export: Activity? = null
        try {
            export = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main {
                AppSettings.setEnhancementScale(context, 3)
                ArchiveEnhancementDialog.show(export as SettingsActivity, source)
                (export as SettingsActivity).supportFragmentManager.executePendingTransactions()
                // 已启动任务固定使用3倍，之后更改设置仅影响下次任务
                AppSettings.setEnhancementScale(context, 4)
            }
            await { target.length() > 0 && main {
                (export as SettingsActivity).supportFragmentManager.findFragmentByTag("archive-enhancement")
                    ?.let { (it as ArchiveEnhancementDialog).dialog?.findViewById<TextView>(R.id.tvExportStatus)?.text.toString() } == context.getString(R.string.enhancement_export_done)
            } }
            assertEquals(android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension("cbz")
                ?: "application/vnd.comicbook+zip", requested.get().type)
            assertEquals("${source.nameWithoutExtension}_HD.cbz", requested.get().getStringExtra(Intent.EXTRA_TITLE))
            ZipFile(target).use {
                assertEquals(2, it.size())
                val image = it.getInputStream(it.getEntry("000001.png")).use(BitmapFactory::decodeStream)
                try { assertEquals(65 * 3, image.width); assertEquals(97 * 3, image.height) }
                finally { image.recycle() }
            }
            main {
                val dialog = (export as SettingsActivity).supportFragmentManager
                    .findFragmentByTag("archive-enhancement") as ArchiveEnhancementDialog
                assertTrue(dialog.dialog?.isShowing == true)
            }
            val screenshots = File(context.getExternalFilesDir(null), "enhancement-acceptance").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { File(screenshots, "book-dialog.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { screenshot.recycle() }
            }
            assertArrayEquals(original, source.readBytes())
        } finally {
            main { AppSettings.setEnhancementScale(context, previousScale) }
            export?.let { activity -> main { activity.finish() } }
            instrumentation.removeMonitor(monitor)
            targetDir.deleteRecursively()
        }
    }

    private fun archive(): File {
        val archive = temp.newFile("synthetic-${System.nanoTime()}.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, size) in listOf("page10.png" to (129 to 77), "page2.png" to (65 to 97))) {
                val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bitmap).apply {
                        drawColor(Color.WHITE)
                        drawCircle(30f, 30f, 15f, Paint().apply { color = Color.BLACK })
                    }
                    zip.putNextEntry(ZipEntry(name))
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    zip.closeEntry()
                } finally { bitmap.recycle() }
            }
        }
        return archive
    }

    private fun <T> main(block: () -> T): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("export did not complete", condition())
    }
}
