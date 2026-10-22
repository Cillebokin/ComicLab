package com.example.comiclab.ui.reader.manga

import android.app.Activity
import android.app.Dialog
import android.graphics.PointF
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.example.comiclab.AppSettings
import com.example.comiclab.ComicArchive
import com.example.comiclab.R
import com.example.comiclab.core.reader.enhance.PageSuperResolution
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.ui.reader.enhance.EnhancementExportActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** 任务拥有独立原图副本，不持有阅读缓存租约，不修改压缩包或阅读页码 */
internal class PageEnhancementDialog private constructor(
    private val activity: Activity,
    private val archive: File,
    private val entry: String,
    private val pageNumber: Int,
    private val onClosed: () -> Unit
) {
    private val scale = AppSettings.getEnhancementScale(activity)
    private val app = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val directory = File(app.cacheDir, "page-enhancement/${UUID.randomUUID()}")
    private val dialog = Dialog(activity)
    private lateinit var image: ReaderSubsamplingImageView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var originalButton: Button
    private lateinit var resultButton: Button
    private lateinit var closeButton: Button
    private lateinit var saveButton: Button
    private var exporting = false
    private var source: File? = null
    private var result: File? = null
    private var orientation = 0
    private var showingResult = false
    private var dismissed = false
    private var workerFinished = false
    private var imageClosed = false
    private var cleaned = false
    private var lastProgress = -1
    private var lastGpu: Boolean? = null
    private var switching = false
    private var displayGeneration = 0

    private fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_page_enhancement)
        image = dialog.findViewById(R.id.imageEnhancement)
        status = dialog.findViewById(R.id.tvEnhancementStatus)
        progress = dialog.findViewById(R.id.progressEnhancement)
        originalButton = dialog.findViewById(R.id.btnEnhancementOriginal)
        resultButton = dialog.findViewById(R.id.btnEnhancementResult)
        closeButton = dialog.findViewById(R.id.btnEnhancementClose)
        saveButton = dialog.findViewById(R.id.btnEnhancementSave)
        dialog.findViewById<TextView>(R.id.tvEnhancementTitle).text =
            activity.getString(R.string.reader_enhance_title, pageNumber, scale)
        status.setText(R.string.reader_enhance_preparing)
        progress.isIndeterminate = true
        originalButton.setOnClickListener { display(false) }
        resultButton.setOnClickListener { display(true) }
        closeButton.setOnClickListener { close() }
        saveButton.setOnClickListener { saveImage() }
        dialog.setOnDismissListener {
            dismissed = true
            cancelled.set(true)
            image.closeReaderImage {
                imageClosed = it.isSuccess
                cleanupIfReleased()
            }
            onClosed()
            cleanupIfReleased()
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.black)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        EnhancementRuntime.worker.execute {
            try {
                check(directory.mkdirs()) { "Could not create enhancement cache" }
                val original = File(directory, "original.${entry.substringAfterLast('.', "img")}")
                check(ComicArchive.extractImageEntryToFile(archive, entry, original)) {
                    "Could not extract original page"
                }
                if (cancelled.get()) throw CancellationException()
                val rotation = runCatching {
                    when (ExifInterface(original.absolutePath).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }.getOrDefault(0)
                main.post {
                    if (!dismissed) {
                        source = original
                        orientation = rotation
                        display(false)
                    }
                }
                val enhanced = PageSuperResolution(app, scale = scale).enhance(original, directory, cancelled) { percent, gpu ->
                    // 回调在工作线程，去重后再投递，避免大量编码行挤占主线程
                    if (percent != lastProgress || gpu != lastGpu) {
                        lastProgress = percent
                        lastGpu = gpu
                        main.post {
                            if (!dismissed) {
                                progress.isIndeterminate = false
                                progress.progress = percent
                                status.text = activity.getString(R.string.reader_enhance_progress,
                                    percent, if (gpu) "GPU" else "CPU")
                            }
                        }
                    }
                }
                main.post {
                    if (!dismissed) {
                        result = enhanced
                        saveButton.isEnabled = true
                        status.setText(R.string.reader_enhance_done)
                        closeButton.setText(R.string.reader_enhance_close)
                        display(true)
                    }
                }
            } catch (_: CancellationException) {
                // 用户关闭、旋转或离开阅读器：结果不再交给界面
            } catch (failure: Exception) {
                android.util.Log.e("PageEnhancement", "Single-page enhancement failed", failure)
                main.post { if (!dismissed) showFailure() }
            } catch (failure: LinkageError) {
                android.util.Log.e("PageEnhancement", "Native enhancement unavailable", failure)
                main.post { if (!dismissed) showFailure() }
            } catch (failure: OutOfMemoryError) {
                android.util.Log.e("PageEnhancement", "Enhancement memory exhausted", failure)
                main.post { if (!dismissed) showFailure() }
            } finally {
                main.post {
                    workerFinished = true
                    EnhancementRuntime.busy.set(false)
                    cleanupIfReleased()
                }
            }
        }
    }

    private fun showFailure() {
        progress.isIndeterminate = false
        progress.visibility = View.GONE
        status.setText(R.string.reader_enhance_failed)
        closeButton.setText(R.string.reader_enhance_close)
    }

    private fun display(enhanced: Boolean) {
        val file = (if (enhanced) result else source) ?: return
        val generation = ++displayGeneration
        val center = image.center
        val oldWidth = image.sWidth.toFloat()
        val oldHeight = image.sHeight.toFloat()
        val normalizedCenter = if (image.isReady && center != null && oldWidth > 0 && oldHeight > 0) {
            PointF(center.x / oldWidth, center.y / oldHeight)
        } else null
        val normalizedScale = image.scale * oldWidth
        switching = true
        originalButton.isEnabled = false
        resultButton.isEnabled = false
        showingResult = enhanced
        // 等待旧解码任务结束后换图，保留对应的缩放区域作原图对比
        image.clearReaderImage { cleared ->
            if (dismissed || generation != displayGeneration) return@clearReaderImage
            if (cleared.isFailure) { showFailure(); return@clearReaderImage }
            image.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    if (dismissed || generation != displayGeneration) return
                    if (normalizedCenter != null) {
                        image.setScaleAndCenter(normalizedScale / image.sWidth,
                            PointF(normalizedCenter.x * image.sWidth, normalizedCenter.y * image.sHeight))
                    }
                    switching = false
                    refreshComparisonButtons()
                }
                override fun onImageLoadError(e: Exception) {
                    if (dismissed || generation != displayGeneration) return
                    switching = false
                    showFailure()
                    refreshComparisonButtons()
                }
            })
            image.setMaxScale(if (enhanced) 6f else 6f * scale)
            image.setOrientation(orientation)
            image.setReaderImage(ImageSource.uri(Uri.fromFile(file)).tilingEnabled())
        }
    }

    private fun refreshComparisonButtons() {
        originalButton.isEnabled = !switching && source != null && showingResult
        resultButton.isEnabled = !switching && result != null && !showingResult
        originalButton.isSelected = !showingResult
        resultButton.isSelected = showingResult
    }

    fun close() {
        cancelled.set(true)
        dialog.dismiss()
    }

    private fun saveImage() {
        val file = result ?: return
        if (exporting) return
        exporting = true
        saveButton.isEnabled = false
        // 单独暂存，系统文件选择器打开后即使阅读器旋转/关闭，也不依赖预览缓存
        EnhancementRuntime.io.execute {
            var staged: File? = null
            try {
                val root = File(app.cacheDir, "enhancement-exports").apply { check(mkdirs() || isDirectory) }
                val copy = File(root, "${UUID.randomUUID()}.png")
                staged = copy
                file.copyTo(copy)
                main.post {
                    exporting = false
                    if (!dismissed && !activity.isFinishing && !activity.isDestroyed) {
                        try {
                            EnhancementExportActivity.launchImage(activity, copy,
                                "${archive.nameWithoutExtension}_${pageNumber}_HD.png")
                            saveButton.isEnabled = true
                        } catch (_: Exception) {
                            copy.delete()
                            Toast.makeText(activity, R.string.enhancement_export_failed, Toast.LENGTH_LONG).show()
                        }
                    } else copy.delete()
                    cleanupIfReleased()
                }
            } catch (_: Exception) {
                staged?.delete()
                main.post {
                    exporting = false
                    if (!dismissed) {
                        saveButton.isEnabled = true
                        Toast.makeText(activity, R.string.enhancement_export_failed, Toast.LENGTH_LONG).show()
                    }
                    cleanupIfReleased()
                }
            }
        }
    }

    private fun cleanupIfReleased() {
        if (!dismissed || !workerFinished || !imageClosed || exporting || cleaned) return
        cleaned = true
        // 目录仅由本任务创建；不删除原压缩包、预解压缓存或其他任务文件
        Thread({ directory.deleteRecursively() }, "page-enhancement-cleanup").start()
    }

    companion object {
        fun open(activity: Activity, archive: File, entry: String, pageNumber: Int,
            onClosed: () -> Unit): PageEnhancementDialog? {
            if (archive.extension.equals("pdf", true)) {
                Toast.makeText(activity, R.string.reader_enhance_pdf_unsupported, Toast.LENGTH_LONG).show()
                return null
            }
            if (!EnhancementRuntime.busy.compareAndSet(false, true)) {
                Toast.makeText(activity, R.string.reader_enhance_busy, Toast.LENGTH_SHORT).show()
                return null
            }
            return PageEnhancementDialog(activity, archive, entry, pageNumber, onClosed).also { it.show() }
        }
    }
}
