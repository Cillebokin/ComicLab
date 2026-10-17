package com.example.comiclab.ui.reader.manga

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.example.comiclab.ui.reader.manga.ReaderSubsamplingImageView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSubsamplingImageViewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val temporaryFolder = TemporaryFolder(context.cacheDir)

    @Test
    fun nativeTiledInitializationCanCloseFromReadyCallbackAndReleaseSource() {
        val imageFile = createTiledFixture()
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeResult = AtomicReference<Result<Unit>?>(null)
        lateinit var view: ReaderSubsamplingImageView

        instrumentation.runOnMainSync {
            view = ReaderSubsamplingImageView(context)
            view.setMaxTileSize(512)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, 320, 480)
            view.setOnImageEventListener(object : SubsamplingScaleImageView.OnImageEventListener {
                override fun onReady() {
                    ready.countDown()
                    view.closeReaderImage {
                        closeResult.set(it)
                        closed.countDown()
                    }
                }

                override fun onImageLoaded() = Unit

                override fun onPreviewLoadError(e: Exception) = Unit

                override fun onImageLoadError(e: Exception) = Unit

                override fun onTileLoadError(e: Exception) = Unit

                override fun onPreviewReleased() = Unit
            })
            view.setReaderImage(
                com.davemorrissey.labs.subscaleview.ImageSource
                    .uri(Uri.fromFile(imageFile))
                    .tilingEnabled()
            )
        }

        assertTrue("the native tiled decoder should become ready", ready.await(15, TimeUnit.SECONDS))
        assertTrue("close should finish after native decoder initialization and queued tile callbacks", closed.await(15, TimeUnit.SECONDS))
        assertTrue("the native decoder must recycle successfully", closeResult.get()?.isSuccess == true)
        assertTrue("the temporary tiled source can be deleted after close", imageFile.delete())
    }

    private fun createTiledFixture(): File {
        val imageFile = temporaryFolder.newFile("large-tiled.png")
        val bitmap = Bitmap.createBitmap(1536, 2304, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(35, 45, 55))
            for (index in 0 until 24) {
                val inset = index * 48f
                val paint = android.graphics.Paint().apply {
                    color = Color.rgb((index * 37) % 255, (index * 71) % 255, (index * 97) % 255)
                }
                canvas.drawRect(inset, inset, 1536f - inset, 2304f - inset, paint)
            }
            imageFile.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write tiled test image"
                }
            }
        } finally {
            bitmap.recycle()
        }
        return imageFile
    }
}
