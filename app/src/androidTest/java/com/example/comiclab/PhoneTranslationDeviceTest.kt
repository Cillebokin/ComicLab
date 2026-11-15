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
        assumeTrue(mode in listOf("local", "book", "repair", "detectCancel", "ocrCancel", "repairCancel", "ai", "apiCancel", "ui", "uiReader", "settings"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sampleId = args.getString("sampleId", "")!!
        val ocrConcurrency = args.getString("ocrConcurrency")?.toIntOrNull()
            ?: SinglePageTranslation.DEFAULT_OCR_CONCURRENCY
        val inpaintConcurrency = args.getString("inpaintConcurrency")?.toIntOrNull()
            ?: SinglePageTranslation.DEFAULT_INPAINT_CONCURRENCY
        val inpaintThreads = args.getString("inpaintThreads")?.toIntOrNull()
            ?: SinglePageTranslation.DEFAULT_INPAINT_THREADS
        val inpaintWinograd = args.getString("inpaintWinograd")?.toBooleanStrictOrNull() ?: false
        val inpaintSgemm = args.getString("inpaintSgemm")?.toBooleanStrictOrNull() ?: false
        require(ocrConcurrency in 1..4)
        require(inpaintConcurrency in 1..4)
        require(inpaintThreads in 1..2)
        require(sampleId.matches(Regex("[a-zA-Z0-9_-]*")))
        val root = File(context.filesDir, "translation-phone" + if (sampleId.isEmpty()) "" else "/$sampleId").apply { mkdirs() }
        val source = File(root, "source.png")
        if (!source.isFile && sampleId in listOf("synthetic", "textured")) {
            val page = Bitmap.createBitmap(1200, 2200, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(page); canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 76f }
                listOf("こんにちは世界", "今日はいい天気ですね", "本当にそう思います", "もう一度お願いします",
                    "それはとても面白い", "大丈夫ですか", "少し待ってください", "ありがとう").forEachIndexed { index, text ->
                    val baseline = 170f + index * 250f
                    if (sampleId == "textured") {
                        val texture = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            shader = LinearGradient(40f, baseline - 100f, 1080f, baseline - 100f,
                                Color.rgb(214, 214, 214), Color.rgb(250, 250, 250), Shader.TileMode.CLAMP)
                        }
                        canvas.drawRect(40f, baseline - 100f, 1080f, baseline + 18f, texture)
                    }
                    canvas.drawText(text, 80f, baseline, paint)
                }
            } finally { source.outputStream().use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }; page.recycle() }
        }
        check(source.isFile) { "Supply a sample or use sampleId=synthetic" }
        val archive = File(root, "sample.cbz")
        if (!archive.exists()) ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("001.png")); source.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
        val models = File(context.noBackupFilesDir, "translation-models").apply { mkdirs() }
        File(context.filesDir, "translation-validation-models").listFiles()?.forEach {
            val target = File(models, it.name)
            if (target.length() != it.length()) it.copyTo(target, overwrite = true)
        }
        if (!TranslationModels.hasCompletePackage(context)) {
            val packageFile = File(context.getExternalFilesDir(null), "translation-models.zip")
            require(packageFile.isFile) { "Place the official model package at ${packageFile.absolutePath}" }
            TranslationModels.importPackage(context, packageFile.inputStream(), AtomicBoolean()) { }
        }
        TranslationSettingsStore.setAutomaticTranslationEnabled(context, true)
        if (mode == "book") {
            val pageCount = (args.getString("bookPages")?.toIntOrNull() ?: 4).coerceIn(2, 12)
            val book = File(root, "benchmark-book.cbz")
            ZipOutputStream(book.outputStream()).use { zip ->
                repeat(pageCount) { index ->
                    zip.putNextEntry(ZipEntry("%06d.png".format(index + 1)))
                    source.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val bookDirectory = File(root, "book-$ocrConcurrency-$inpaintConcurrency")
            if (bookDirectory.exists()) check(bookDirectory.deleteRecursively())
            check(bookDirectory.mkdirs() || bookDirectory.isDirectory)
            val cancelled = AtomicBoolean()
            val started = SystemClock.elapsedRealtime()
            val events = JSONArray()
            val baselinePss = Debug.getPss()
            val peakPss = AtomicLong(baselinePss.toLong())
            val samplePss = AtomicBoolean(true)
            val sampler = Thread {
                while (samplePss.get()) {
                    peakPss.updateAndGet { maxOf(it, Debug.getPss().toLong()) }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }.apply { start() }
            val settings = TranslationSettings("https://example.invalid/v1", "local-device-probe", "unused")
            val translator: (TranslationSettings, List<String>, String, AtomicBoolean) -> List<String> =
                { _, texts, _, _ -> texts.mapIndexed { i, _ -> "示例译文${i + 1}" } }
            val result = try {
                ArchiveTranslation.translate(
                    context = context,
                    source = book,
                    directory = bookDirectory,
                    cancelled = cancelled,
                    language = args.getString("language", "English")!!,
                    settings = settings,
                    ocrConcurrency = ocrConcurrency,
                    inpaintConcurrency = inpaintConcurrency,
                    translate = translator
                ) { page, total, percent, stage, translated, kept ->
                    events.put(JSONObject().put("page", page).put("total", total).put("percent", percent)
                        .put("stage", stage).put("translated", translated).put("kept", kept)
                        .put("ms", SystemClock.elapsedRealtime() - started))
                }
            } finally {
                samplePss.set(false); sampler.interrupt(); sampler.join()
            }
            val outputEntries = java.util.zip.ZipFile(result.file).use { zip ->
                zip.entries().asSequence().map { it.name }.toList()
            }
            assertEquals(pageCount, result.translatedPages)
            assertEquals(0, result.keptOriginalPages)
            assertEquals(pageCount, outputEntries.size)
            File(root, "book-$ocrConcurrency-$inpaintConcurrency-report.json").writeText(JSONObject()
                .put("mode", mode).put("ocrConcurrency", ocrConcurrency).put("inpaintConcurrency", inpaintConcurrency)
                .put("pageCount", pageCount)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("outputEntries", JSONArray(outputEntries))
                .put("baselinePssKiB", baselinePss).put("peakPssKiB", peakPss.get())
                .put("events", events).toString(2))
            return
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
            instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SettingsActivity.EXTRA_SETTINGS_SECTION, SettingsActivity.SETTINGS_SECTION_AUTO_TRANSLATION))
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
                press(R.string.reader_enhance_original); Thread.sleep(500)
                press(R.string.translation_result); Thread.sleep(500)
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
        val work = File(root, if (mode == "repair")
            "$mode-$ocrConcurrency-$inpaintConcurrency-$inpaintThreads-$inpaintWinograd-$inpaintSgemm" else mode)
        if (mode == "repair" && work.exists()) check(work.deleteRecursively())
        check(work.mkdirs() || work.isDirectory)
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
        var analysisElapsedMs = -1L
        var analysisPeakPssKiB = -1L
        fun pipeline(cancelPhase: String? = null) = SinglePageTranslation(context, cancelled, ocrConcurrency,
            inpaintConcurrency, inpaintThreads, inpaintWinograd, inpaintSgemm) { stage ->
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
                    pipeline(when (mode) {
                        "detectCancel" -> "detect"
                        "ocrCancel" -> "ocr|2/"
                        "repairCancel" -> "inpaint|1/"
                        else -> "inpaint|"
                    })
                        .process(archive, "001.png", work, settings, targetLanguage, translator)
                }
                report.put("cancelReturnMs", SystemClock.elapsedRealtime() - cancelledAt.get())
                assertTrue(cancelledAt.get() > 0)
                cancelled.set(false)
            }
            val processor = pipeline()
            val analysis = processor.process(archive, "001.png", work, settings, targetLanguage, translator)
            analysisElapsedMs = SystemClock.elapsedRealtime() - start
            analysisPeakPssKiB = peak.get()
            completed = analysis
            if (mode == "repair") assertTrue("Textured test did not exercise AOT", analysis.inpaintCalls > 0)
            val currentOcr = analysis.blocks.joinToString("\n") { "${it.region.sourceText}\u001f${it.region.direction}" }
            val serialReport = File(root, "local-1-report.json")
            if (mode == "local" && ocrConcurrency > 1) {
                assertTrue("Run OCR concurrency 1 first", serialReport.isFile)
                val serialBlocks = JSONObject(serialReport.readText()).getJSONArray("blocks")
                val serialOcr = (0 until serialBlocks.length()).joinToString("\n") { index ->
                    val block = serialBlocks.getJSONObject(index)
                    "${block.getString("source")}\u001f${block.getString("direction")}"
                }
                assertEquals("OCR text/direction changed at concurrency $ocrConcurrency", serialOcr, currentOcr)
            }
            val patchHashes = JSONArray().apply { analysis.patches.forEach { patch ->
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(patch.file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                put(digest)
            } }
            val isAotBaseline = inpaintConcurrency == 2 && inpaintThreads == 2 && !inpaintWinograd && !inpaintSgemm
            if (mode == "repair" && !isAotBaseline) {
                val baselineReport = File(root, "repair-$ocrConcurrency-2-2-false-false-report.json")
                val baselineDirectory = File(root, "repair-$ocrConcurrency-2-2-false-false")
                assertTrue("Run default AOT baseline first", baselineReport.isFile && baselineDirectory.isDirectory)
                val baselineJson = JSONObject(baselineReport.readText())
                val baselineBlocks = baselineJson.getJSONArray("blocks")
                val baselineOcr = (0 until baselineBlocks.length()).joinToString("\n") { index ->
                    val block = baselineBlocks.getJSONObject(index)
                    "${block.getString("source")}\u001f${block.getString("direction")}" 
                }
                assertEquals("OCR text/direction changed", baselineOcr, currentOcr)
                if (!inpaintWinograd && !inpaintSgemm) {
                    assertEquals("Repair patches changed at concurrency $inpaintConcurrency and threads $inpaintThreads",
                        baselineJson.getJSONArray("patchHashes").toString(), patchHashes.toString())
                } else {
                    val baselineCount = baselineJson.getInt("patches")
                    assertEquals("Repair patch count changed", baselineCount, analysis.patches.size)
                    var exactFiles = true
                    var absoluteDelta = 0L
                    var repairedPixels = 0L
                    var changedPixels = 0L
                    var maxChannelDelta = 0
                    analysis.patches.forEach { candidatePatch ->
                        val baseFile = File(baselineDirectory, candidatePatch.file.name)
                        assertTrue("Missing baseline patch ${candidatePatch.file.name}", baseFile.isFile)
                        val baseBytes = baseFile.readBytes()
                        val candidateBytes = candidatePatch.file.readBytes()
                        if (!baseBytes.contentEquals(candidateBytes)) exactFiles = false
                        val baseBitmap = BitmapFactory.decodeByteArray(baseBytes, 0, baseBytes.size)
                            ?: error("Cannot decode baseline patch")
                        val candidateBitmap = BitmapFactory.decodeByteArray(candidateBytes, 0, candidateBytes.size)
                            ?: run { baseBitmap.recycle(); error("Cannot decode candidate patch") }
                        try {
                            assertEquals("Patch width changed", baseBitmap.width, candidateBitmap.width)
                            assertEquals("Patch height changed", baseBitmap.height, candidateBitmap.height)
                            val basePixels = IntArray(baseBitmap.width * baseBitmap.height)
                            val candidatePixels = IntArray(basePixels.size)
                            baseBitmap.getPixels(basePixels, 0, baseBitmap.width, 0, 0, baseBitmap.width, baseBitmap.height)
                            candidateBitmap.getPixels(candidatePixels, 0, candidateBitmap.width, 0, 0,
                                candidateBitmap.width, candidateBitmap.height)
                            basePixels.indices.forEach { pixelIndex ->
                                val basePixel = basePixels[pixelIndex]
                                val candidatePixel = candidatePixels[pixelIndex]
                                val basePainted = basePixel ushr 24 != 0
                                val candidatePainted = candidatePixel ushr 24 != 0
                                assertEquals("Repair mask changed in ${candidatePatch.file.name}", basePainted, candidatePainted)
                                if (basePainted) {
                                    val deltaR = kotlin.math.abs(((basePixel ushr 16) and 255) - ((candidatePixel ushr 16) and 255))
                                    val deltaG = kotlin.math.abs(((basePixel ushr 8) and 255) - ((candidatePixel ushr 8) and 255))
                                    val deltaB = kotlin.math.abs((basePixel and 255) - (candidatePixel and 255))
                                    absoluteDelta += deltaR + deltaG + deltaB
                                    maxChannelDelta = maxOf(maxChannelDelta, deltaR, deltaG, deltaB)
                                    val pixelChanged = deltaR != 0 || deltaG != 0 || deltaB != 0
                                    repairedPixels++
                                    if (pixelChanged) changedPixels++
                                }
                            }
                        } finally { baseBitmap.recycle(); candidateBitmap.recycle() }
                    }
                    report.put("aotExactPatchFiles", exactFiles)
                        .put("aotRepairedPixelMae", if (repairedPixels == 0L) 0.0 else absoluteDelta.toDouble() / (repairedPixels * 3))
                        .put("aotChangedPixelFraction", if (repairedPixels == 0L) 0.0 else changedPixels.toDouble() / repairedPixels)
                        .put("aotMaxChannelDelta", maxChannelDelta)
                }
            }
            report.put("width", analysis.width).put("height", analysis.height).put("ocrConcurrency", ocrConcurrency)
                .put("inpaintConcurrency", inpaintConcurrency).put("inpaintThreads", inpaintThreads)
                .put("inpaintWinograd", inpaintWinograd).put("inpaintSgemm", inpaintSgemm)
                .put("inpaintCalls", analysis.inpaintCalls)
                .put("repairSetupMs", analysis.repairTiming.setupNanos / 1_000_000.0)
                .put("aotInputMs", analysis.repairTiming.inputNanos / 1_000_000.0)
                .put("aotInferenceMs", analysis.repairTiming.inferenceNanos / 1_000_000.0)
                .put("aotOutputMs", analysis.repairTiming.outputNanos / 1_000_000.0)
                .put("patchWriteMs", analysis.repairTiming.patchNanos / 1_000_000.0)
                .put("patchHashes", patchHashes)
            report.put("blocks", JSONArray().apply { analysis.blocks.forEach { block ->
                val r = block.region
                put(JSONObject().put("source", r.sourceText).put("translation", block.text).put("direction", r.direction)
                    .put("left", r.x0).put("top", r.y0).put("right", r.x1).put("bottom", r.y1))
            } })
            // Preview generation is excluded from measured PSS and elapsed time.
            val output = processor.render(analysis, work, TranslationStyle())
            report.put("output", output.name).put("patches", analysis.patches.size)
            val ocrStart = (0 until events.length()).map { events.getJSONObject(it) }
                .firstOrNull { it.optString("stage").startsWith("ocr|0/") }
            val ocrFinish = (0 until events.length()).map { events.getJSONObject(it) }
                .lastOrNull { event ->
                    val stage = event.optString("stage")
                    if (!stage.startsWith("ocr|")) false else {
                        val counts = stage.removePrefix("ocr|").split("/")
                        counts.size == 2 && counts[0] == counts[1]
                    }
                }
            if (ocrStart != null && ocrFinish != null) {
                report.put("ocrElapsedMs", ocrFinish.getLong("ms") - ocrStart.getLong("ms"))
            }
            val inpaintStart = (0 until events.length()).map { events.getJSONObject(it) }
                .firstOrNull { it.optString("stage").startsWith("inpaint|0/") }
            val inpaintFinish = (0 until events.length()).map { events.getJSONObject(it) }
                .firstOrNull { it.optString("stage") == "render" }
            if (inpaintStart != null && inpaintFinish != null) {
                report.put("inpaintElapsedMs", inpaintFinish.getLong("ms") - inpaintStart.getLong("ms"))
            }
            if (mode == "repairCancel") assertEquals("Paid translation must survive repair cancellation", 1, calls)
            report.put("success", true)
        } catch (error: Throwable) {
            report.put("success", false).put("errorType", error.javaClass.simpleName)
            throw error
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - start).put("events", events)
                .put("analysisElapsedMs", analysisElapsedMs).put("analysisPeakPssKiB", analysisPeakPssKiB)
                .put("baselinePssKiB", baseline).put("peakPssKiB", peak.get()).put("translatorCalls", calls)
                .put("nativePeakAllocatedBytes", nativePeak.get()).put("nativeEndAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            active.set(false); sampler.interrupt(); sampler.join()
            File(root, when (mode) {
                "local" -> "local-$ocrConcurrency-report.json"
                "repair" -> "repair-$ocrConcurrency-$inpaintConcurrency-$inpaintThreads-$inpaintWinograd-$inpaintSgemm-report.json"
                else -> "$mode-report.json"
            })
                .writeText(report.toString(2))
        }
        // Separate diagnostic output; excluded from timing/memory benchmark and original remains untouched.
        val erased = SinglePageTranslation(context, AtomicBoolean(), {}).render(checkNotNull(completed).copy(blocks = emptyList()), work, TranslationStyle())
        erased.copyTo(File(work, "erased-only.png"), overwrite = true); erased.delete()
    }
}
