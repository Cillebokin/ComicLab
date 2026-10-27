package com.example.comiclab.translation

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.example.comiclab.core.reader.translation.*
import com.example.comiclab.ui.reader.translation.PageTranslationViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationLifecycleTest {
    @Test fun rerenderRetiresOldResultsOnlyAfterPreviewAndExportReferencesEnd() {
        val model = PageTranslationViewModel(RuntimeEnvironment.getApplication())
        val store = ViewModelStore().apply { put("translation", model) }
        model.acquirePreview()
        try {
            model.directory.mkdirs()
            val source = File(model.directory, "source.png")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            PageTranslationViewModel::class.java.getDeclaredField("analysis").apply { isAccessible = true }
                .set(model, TranslationAnalysis(source, 64, 64, 1, emptyList(), emptyList()))
            fun render(): File {
                model.start(File("unused.cbz"), "unused.png", "English", TranslationStyle())
                val deadline = System.nanoTime() + 5_000_000_000
                while (model.state.value?.busy == true && System.nanoTime() < deadline) {
                    Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle()
                }
                assertEquals("done", model.state.value?.stage)
                return requireNotNull(model.state.value?.result)
            }
            val first = render()
            model.retainFile(first) // Active decoder and independent export copy hold separate references.
            model.retainFile(first)
            val second = render()
            assertTrue(first.isFile); assertTrue(second.isFile)
            val third = render()
            assertTrue(third.isFile); assertFalse(second.exists()); assertTrue(first.exists())
            model.releaseFile(first); assertTrue(first.exists())
            model.releaseFile(first); assertFalse(first.exists())
            assertTrue(source.isFile); assertTrue(third.isFile)
        } finally { store.clear(); model.releasePreview() }
    }
}
