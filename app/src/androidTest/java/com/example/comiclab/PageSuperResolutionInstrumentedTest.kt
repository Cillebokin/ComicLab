package com.example.comiclab

import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.example.comiclab.core.reader.enhance.PageSuperResolution
import com.example.comiclab.ui.reader.manga.ReaderSubsamplingImageView
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageSuperResolutionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val temp = TemporaryFolder(context.cacheDir)

    @Test fun profileRepresentativeWholeBookPage() {
        val source = fixture(1024, 1536)
        PageSuperResolution(context, reuseModel = true, scale = 2).use { engine ->
            repeat(2) { iteration ->
                val started = SystemClock.elapsedRealtime()
                var ready = started
                var encoded = started
                var actualGpu = false
                var inferenceFinished = false
                val result = engine.enhance(source, temp.newFolder(), AtomicBoolean()) { percent, gpu ->
                    actualGpu = gpu
                    if (percent == 0) ready = SystemClock.elapsedRealtime()
                    if (percent >= 90 && !inferenceFinished) {
                        inferenceFinished = true
                        encoded = SystemClock.elapsedRealtime()
                    }
                }
                val finished = SystemClock.elapsedRealtime()
                assertTrue(result.length() > 0)
                val report = "page=1024x1536 scale=2 iteration=$iteration gpu=$actualGpu " +
                    "setupMs=${ready - started} inferenceAndTileIoMs=${encoded - ready} " +
                    "pngAndExifMs=${finished - encoded} totalMs=${finished - started} bytes=${result.length()}"
                android.util.Log.i("EnhancementProfile", report)
                println("EnhancementProfile $report")
            }
        }
    }

    @Test fun allScaleModelsProduceNeuralOutputAtRequestedDimensions() {
        val source = fixture(65, 79)
        for (scale in 2..4) {
            val result = PageSuperResolution(context, scale = scale).enhance(
                source, temp.newFolder(), AtomicBoolean(), preferGpu = false) { _, _ -> }
            val output = BitmapFactory.decodeFile(result.absolutePath)
            val input = BitmapFactory.decodeFile(source.absolutePath)
            val interpolated = Bitmap.createScaledBitmap(input, 65 * scale, 79 * scale, true)
            try {
                assertEquals(65 * scale, output.width)
                assertEquals(79 * scale, output.height)
                assertFalse("scale $scale must execute its neural model", output.sameAs(interpolated))
            } finally { output.recycle(); input.recycle(); interpolated.recycle() }
        }
    }

    @Test fun benchmarkRepresentativePage() {
        val source = fixture(512, 768)
        val label = InstrumentationRegistry.getArguments().getString("enhancementBenchmarkLabel", "current")
        for (gpu in listOf(false, true, true)) {
            val started = SystemClock.elapsedRealtime()
            val output = PageSuperResolution(context).enhance(source, temp.newFolder(), AtomicBoolean(), gpu) { _, _ -> }
            assertTrue(output.length() > 0)
            android.util.Log.i("EnhancementBenchmark", "$label preferGpu=$gpu totalMs=${SystemClock.elapsedRealtime() - started}")
            val dir = File(context.getExternalFilesDir(null), "enhancement-benchmark").apply { mkdirs() }
            if (!gpu) output.copyTo(File(dir, "$label.png"), overwrite = true)
        }
    }

    @Test fun realCpuModelProducesNonInterpolatedTiledPngAndCancellationAllowsRetry() {
        val source = fixture(145, 177)
        val dir = temp.newFolder()
        val cancel = AtomicBoolean(false)
        try {
            PageSuperResolution(context).enhance(source, dir, cancel, preferGpu = false) { percent, _ ->
                if (percent > 0) cancel.set(true)
            }
            fail("cancellation must not publish a result")
        } catch (_: CancellationException) { }
        assertFalse(File(dir, "enhanced.png").exists())
        assertFalse(File(dir, "enhanced.rgb").exists())
        assertFalse(File(dir, "enhanced.pending.png").exists())
        val updates = mutableListOf<Int>()
        val result = PageSuperResolution(context).enhance(source, dir, AtomicBoolean(), preferGpu = false) { p, gpu ->
            assertFalse(gpu)
            updates += p
        }
        assertEquals(0, updates.first())
        assertEquals(100, updates.last())
        assertEquals(updates.sorted(), updates)
        val output = BitmapFactory.decodeFile(result.absolutePath)
        val input = BitmapFactory.decodeFile(source.absolutePath)
        try {
            assertEquals(290, output.width)
            assertEquals(354, output.height)
            val interpolated = Bitmap.createScaledBitmap(input, 290, 354, true)
            try { assertFalse("must execute the neural model", output.sameAs(interpolated)) }
            finally { interpolated.recycle() }
            assertTrue(Color.red(output.getPixel(0, 0)) > 200)
            val artifacts = File(context.getExternalFilesDir(null), "enhancement-acceptance").apply { mkdirs() }
            source.copyTo(File(artifacts, "original.png"), overwrite = true)
            result.copyTo(File(artifacts, "enhanced.png"), overwrite = true)
        } finally { input.recycle(); output.recycle() }
    }

    @Test fun readerButtonRunsGpuOrCpuAndSwitchesOriginalWithSameViewport() {
        val source = fixture(145, 177)
        val archive = temp.newFile("single-page.cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.png"))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        val before = archive.readBytes()
        val previewRoot = java.io.File(context.cacheDir, "page-enhancement")
        val previousPaths = previewRoot.listFiles().orEmpty().map { it.absolutePath }.toSet()
        fun taskDirectories() = previewRoot.listFiles().orEmpty().filter { it.absolutePath !in previousPaths }
        val immersiveConfirmation = shell("settings get secure immersive_mode_confirmations").trim()
        shell("settings put secure immersive_mode_confirmations confirmed")
        val activity = instrumentation.startActivitySync(Intent(context, MangaReaderActivity::class.java)
            .putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archive.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MangaReaderActivity
        try {
            await { main { activity.imageEntriesForTest.size == 1 } }
            onView(withId(R.id.btnReaderEnhance)).perform(click())
            await {
                runCatching { onView(withId(R.id.tvEnhancementStatus)).check { view, failure ->
                    if (failure != null) throw failure
                    assertEquals(context.getString(R.string.reader_enhance_done), (view as TextView).text.toString())
                }; true }.getOrDefault(false)
            }
            lateinit var image: ReaderSubsamplingImageView
            onView(withId(R.id.imageEnhancement)).check { view, _ -> image = view as ReaderSubsamplingImageView }
            await { main { image.isReady && image.sWidth == 290 } }
            val artifacts = File(context.getExternalFilesDir(null), "enhancement-acceptance").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                try { File(artifacts, "preview.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                } } finally { screenshot.recycle() }
            }
            main { image.setScaleAndCenter(5f, android.graphics.PointF(160f, 180f)) }
            onView(withId(R.id.btnEnhancementOriginal)).perform(click())
            await { main { image.isReady && image.sWidth == 145 } }
            main { assertEquals(10f, image.scale, 0.05f) }
            onView(withId(R.id.btnEnhancementResult)).perform(click())
            await { main { image.isReady && image.sWidth == 290 } }
            main { assertEquals(5f, image.scale, 0.05f) }
            val targetDir = java.io.File(context.getExternalFilesDir(null), "single-export-${System.nanoTime()}").apply { mkdirs() }
            val saved = java.io.File(targetDir, "chosen-page.png").apply { createNewFile() }
            val expected = taskDirectories()
                .map { java.io.File(it, "enhanced.png") }.single { it.isFile }.readBytes()
            val saveUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", saved)
            val requested = AtomicReference<Intent>()
            val saveMonitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                    requested.set(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(saveUri))
                }
            }
            instrumentation.addMonitor(saveMonitor)
            try {
                onView(withId(R.id.btnEnhancementSave)).perform(click())
                await { saved.length() == expected.size.toLong() && main {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).contains(activity)
                } }
                assertArrayEquals(expected, saved.readBytes())
                assertEquals("image/png", requested.get().type)
                assertTrue(requested.get().getStringExtra(Intent.EXTRA_TITLE)!!.endsWith("_HD.png"))
            } finally {
                instrumentation.removeMonitor(saveMonitor)
                targetDir.deleteRecursively()
            }
            onView(withId(R.id.btnEnhancementClose)).perform(click())
            assertArrayEquals(before, archive.readBytes())
            await { taskDirectories().isEmpty() }
            await { main { activity.hasWindowFocus() } }
            // 完成一次后立刻取消下一次，再开始第三次，不能让旧任务结果回到新窗口
            clickEnhance(activity)
            await { taskDirectories().isNotEmpty() }
            onView(withId(R.id.btnEnhancementClose)).perform(click())
            await { taskDirectories().isEmpty() }
            await { main { activity.hasWindowFocus() } }
            clickEnhance(activity)
            await {
                runCatching { onView(withId(R.id.tvEnhancementStatus)).check { view, failure ->
                    if (failure != null) throw failure
                    assertEquals(context.getString(R.string.reader_enhance_done), (view as TextView).text.toString())
                }; true }.getOrDefault(false)
            }
            // 销毁Activity必须关闭窗口并等解码器释放后清理临时图片
            main { activity.finish() }
            await { taskDirectories().isEmpty() }
            assertArrayEquals(before, archive.readBytes())
        } finally {
            main { activity.finish() }
            if (immersiveConfirmation.isEmpty() || immersiveConfirmation == "null") shell("settings delete secure immersive_mode_confirmations")
            else shell("settings put secure immersive_mode_confirmations $immersiveConfirmation")
        }
    }

    private fun fixture(width: Int, height: Int): File {
        val file = temp.newFile("page-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }
            canvas.drawRect(7f, 7f, width - 7f, height - 7f, ink)
            canvas.drawOval(20f, 25f, width - 20f, height - 30f, ink)
            for (x in 23 until width - 25 step 4) canvas.drawLine(x.toFloat(), 70f, x + 15f, 99f, ink)
            ink.style = Paint.Style.FILL
            ink.textSize = 11f
            canvas.drawText("ComicLab 漫画", 12f, 21f, ink)
            canvas.drawText("高清化测试 123", 12f, height - 14f, ink)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun clickEnhance(activity: MangaReaderActivity) {
        // 首次打开已验证触摸入口；连续任务直接触发真实按钮回调，避开控制栏隐藏计时
        main { assertTrue(activity.findViewById<Button>(R.id.btnReaderEnhance).performClick()) }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 120_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("enhancement did not reach the expected state", condition())
    }
}
