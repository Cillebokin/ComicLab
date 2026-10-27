package com.example.comiclab

import android.content.Intent
import android.graphics.*
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.example.comiclab.core.reader.translation.*
import com.example.comiclab.ui.reader.translation.PageTranslationActivity
import com.example.comiclab.ui.reader.translation.PageTranslationViewModel
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Explicit device-only probe; no bundled user image, key or paid request in ordinary test runs. */
@RunWith(AndroidJUnit4::class)
class PhoneTranslationDeviceTest {
    @Test fun singlePageProbe() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("phoneMode") ?: ""
        assumeTrue(mode in listOf("local", "detectCancel", "ocrCancel", "repairCancel", "ai", "apiCancel", "ui", "uiReader", "settings"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sampleId = args.getString("sampleId", "")!!
        require(sampleId.matches(Regex("[a-zA-Z0-9_-]*")))
        val root = File(context.filesDir, "translation-phone" + if (sampleId.isEmpty()) "" else "/$sampleId").apply { mkdirs() }
        val source = File(root, "source.png")
        check(source.isFile) { "Supply an explicit device sample first" }
        val archive = File(root, "sample.cbz")
        if (!archive.exists()) ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.png")); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
        val models = File(context.noBackupFilesDir, "translation-models").apply { mkdirs() }
        File(context.filesDir, "translation-validation-models").listFiles()?.forEach {
            val target = File(models, it.name)
            if (target.length() != it.length()) it.copyTo(target, overwrite = true)
        }
        var readerActivity: android.app.Activity? = null
        fun openPage(): PageTranslationActivity {
            // MIUI rejects app-originated launches from a background instrumentation process.
            // Enter through the existing exported launcher using the test shell identity first.
            instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.example.comiclab.MainActivity")
                .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
            if (mode == "uiReader") {
                val reader = instrumentation.startActivitySync(Intent(context, MangaReaderActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MangaReaderActivity.EXTRA_ARCHIVE_PATH, archive.path))
                readerActivity = reader
                instrumentation.waitForIdleSync(); Thread.sleep(2000)
                val monitor = instrumentation.addMonitor(PageTranslationActivity::class.java.name, null, false)
                try {
                    instrumentation.runOnMainSync { reader.findViewById<android.widget.Button>(R.id.btnReaderTranslate).performClick() }
                    val page = monitor.waitForActivityWithTimeout(10_000) as? PageTranslationActivity
                        ?: error("Reader translation button did not open a page")
                    instrumentation.waitForIdleSync()
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    var focused = false
                    while (!focused && SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync { focused = page.hasWindowFocus() }
                        if (!focused) Thread.sleep(50)
                    }
                    check(focused) { "Translation page did not receive window focus" }
                    return page
                } finally { instrumentation.removeMonitor(monitor) }
            }
            val activity = instrumentation.startActivitySync(Intent(context, PageTranslationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("archive", archive.path).putExtra("entry", "001.png").putExtra("page", 1)) as PageTranslationActivity
            instrumentation.waitForIdleSync()
            return activity
        }
        if (mode == "settings") {
            openPage()
            onView(withText(R.string.translation_settings)).perform(click())
            val deadline = SystemClock.elapsedRealtime() + 10 * 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (runCatching { TranslationSettingsStore.load(context).key.isNotBlank() }.getOrDefault(false)) {
                    File(root, "settings-ready.txt").writeText("Configured on device; key never exported")
                    return
                }
                Thread.sleep(500)
            }
            error("Device settings were not supplied within ten minutes")
        }
        if (mode == "ui" || mode == "uiReader") {
            val activity = openPage()
            lateinit var model: PageTranslationViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[PageTranslationViewModel::class.java] }
            val report = JSONObject().put("baselinePssKiB", Debug.getPss()).put("readerResident", readerActivity != null)
            val active = AtomicBoolean(true); val peak = AtomicLong()
            val sampler = Thread {
                while (active.get()) {
                    peak.updateAndGet { maxOf(it, Debug.getPss()) }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }.apply { start() }
            fun press(title: Int) {
                instrumentation.runOnMainSync {
                    fun buttons(view: android.view.View): List<android.widget.Button> {
                        if (view is android.widget.Button) return listOf(view)
                        if (view !is android.view.ViewGroup) return emptyList()
                        return (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
                    }
                    val matches = buttons(activity.window.decorView).filter { it.text.toString() == activity.getString(title) }
                    check(matches.size == 1) { "Expected one translation control" }
                    check(matches.single().performClick())
                }
            }
            fun awaitState(predicate: (String, Boolean) -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 120_000
                var last = ""
                while (SystemClock.elapsedRealtime() < deadline) {
                    var matched = false
                    instrumentation.runOnMainSync { model.state.value?.let {
                        last = "${it.stage} busy=${it.busy} error=${it.error}"
                        matched = predicate(it.stage, it.busy)
                    } }
                    File(root, "$mode-state.txt").writeText(last)
                    if (matched) return
                    Thread.sleep(50)
                }
                error("Timed out waiting for translation UI state: $last")
            }
            try {
                press(R.string.translation_start)
                awaitState { stage, _ -> stage == "detect" }
                val cancelAt = SystemClock.elapsedRealtime()
                press(android.R.string.cancel)
                awaitState { stage, busy -> !busy && stage == "cancelled" }
                report.put("cancelReturnMs", SystemClock.elapsedRealtime() - cancelAt)
                val retryAt = SystemClock.elapsedRealtime()
                press(R.string.translation_start)
                awaitState { stage, busy -> !busy && stage in listOf("done", "failed") }
                report.put("retryMs", SystemClock.elapsedRealtime() - retryAt)
                var output: File? = null
                instrumentation.runOnMainSync {
                    assertEquals("UI pipeline failed: ${model.state.value?.error}", "done", model.state.value?.stage)
                    output = model.state.value?.result
                }
                // Let the real region-decoded preview load, then compare and return to translated page.
                Thread.sleep(2000)
                press(R.string.translation_compare); Thread.sleep(500)
                press(R.string.translation_compare); Thread.sleep(500)
                checkNotNull(output).copyTo(File(root, "$mode-output.png"), overwrite = true)
                report.put("success", true)
            } finally {
                active.set(false); sampler.interrupt(); sampler.join()
                report.put("peakPssKiB", peak.get())
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (model.directory.exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                report.put("taskDirectoryReleased", !model.directory.exists())
                File(root, "$mode-report.json").writeText(report.toString(2))
                instrumentation.runOnMainSync { readerActivity?.finish() }
            }
            return
        }
        if (mode == "apiCancel") {
            val flag = AtomicBoolean()
            val sentAt = AtomicLong()
            val thread = Thread {
                Thread.sleep(500); sentAt.set(SystemClock.elapsedRealtime()); flag.set(true)
            }.apply { start() }
            try {
                assertThrows(CancellationException::class.java) {
                    TranslationApi.translate(TranslationSettingsStore.load(context), listOf("こんにちは"), "English", flag)
                }
                File(root, "apiCancel-report.json").writeText(JSONObject().put("success", true)
                    .put("cancelReturnMs", SystemClock.elapsedRealtime() - sentAt.get()).toString(2))
            } finally { thread.interrupt(); thread.join() }
            return
        }
        val work = File(root, mode).apply { mkdirs() }
        val report = JSONObject().put("mode", mode).put("abi", android.os.Build.SUPPORTED_ABIS.first())
        val events = JSONArray()
        val active = AtomicBoolean(true); val cancelled = AtomicBoolean()
        val peak = AtomicLong(); val nativePeak = AtomicLong(); val baseline = Debug.getPss()
        val start = SystemClock.elapsedRealtime()
        val sampler = Thread {
            while (active.get()) {
                peak.updateAndGet { maxOf(it, Debug.getPss()) }
                nativePeak.updateAndGet { maxOf(it, Debug.getNativeHeapAllocatedSize()) }
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
        }.apply { start() }
        var calls = 0
        val cancelledAt = AtomicLong()
        fun pipeline(cancelPhase: String? = null) = SinglePageTranslation(context, cancelled) { stage ->
            events.put(JSONObject().put("stage", stage).put("ms", SystemClock.elapsedRealtime() - start)
                .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()))
            if (cancelPhase != null && stage.startsWith(cancelPhase)) {
                if (mode == "detectCancel") Thread {
                    Thread.sleep(80)
                    cancelledAt.set(SystemClock.elapsedRealtime()); cancelled.set(true)
                }.apply { isDaemon = true; start() }
                else { cancelledAt.set(SystemClock.elapsedRealtime()); cancelled.set(true) }
            }
        }
        val settings = if (mode == "ai") TranslationSettingsStore.load(context)
            else TranslationSettings("https://example.invalid/v1", "local-device-probe", "unused")
        val targetLanguage = args.getString("language", "English")!!
        val translator: (TranslationSettings, List<String>, String, AtomicBoolean) -> List<String> = { config, texts, language, flag ->
            calls++
            if (mode == "ai") TranslationApi.translate(config, texts, language, flag)
            else texts.mapIndexed { i, _ -> if (targetLanguage == "Simplified Chinese") "示例译文${i + 1}" else "Sample ${i + 1}" }
        }
        var completed: TranslationAnalysis? = null
        try {
            if (mode.endsWith("Cancel")) {
                assertThrows(CancellationException::class.java) {
                    pipeline(when (mode) { "detectCancel" -> "detect"; "ocrCancel" -> "ocr|2/"; else -> "inpaint|" })
                        .process(archive, "001.png", work, settings, targetLanguage, translator)
                }
                report.put("cancelReturnMs", SystemClock.elapsedRealtime() - cancelledAt.get())
                assertTrue(cancelledAt.get() > 0)
                cancelled.set(false)
            }
            val processor = pipeline()
            val analysis = processor.process(archive, "001.png", work, settings, targetLanguage, translator)
            completed = analysis
            report.put("width", analysis.width).put("height", analysis.height)
            report.put("blocks", JSONArray().apply { analysis.blocks.forEach { block ->
                val r = block.region
                put(JSONObject().put("source", r.sourceText).put("translation", block.text).put("direction", r.direction)
                    .put("left", r.x0).put("top", r.y0).put("right", r.x1).put("bottom", r.y1))
            } })
            // Preview generation is excluded from measured PSS and elapsed time.
            val output = processor.render(analysis, work, TranslationStyle())
            report.put("output", output.name).put("patches", analysis.patches.size)
            if (mode == "repairCancel") assertEquals("Paid translation must survive repair cancellation", 1, calls)
            report.put("success", true)
        } catch (error: Throwable) {
            report.put("success", false).put("errorType", error.javaClass.simpleName)
            throw error
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - start).put("events", events)
                .put("baselinePssKiB", baseline).put("peakPssKiB", peak.get()).put("translatorCalls", calls)
                .put("nativePeakAllocatedBytes", nativePeak.get()).put("nativeEndAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            active.set(false); sampler.interrupt(); sampler.join()
            File(root, "$mode-report.json").writeText(report.toString(2))
        }
        // Separate diagnostic output; excluded from timing/memory benchmark and original remains untouched.
        val erased = SinglePageTranslation(context, AtomicBoolean(), {}).render(checkNotNull(completed).copy(blocks = emptyList()), work, TranslationStyle())
        erased.copyTo(File(work, "erased-only.png"), overwrite = true); erased.delete()
    }
}
