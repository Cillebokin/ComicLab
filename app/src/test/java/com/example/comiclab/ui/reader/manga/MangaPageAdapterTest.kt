package com.example.comiclab.ui.reader.manga

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.comiclab.AppSettings
import com.example.comiclab.ComicArchive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MangaPageAdapterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun doublePageCoverSlotDoesNotChangeSourceEntryCount() {
        val session = FakeReaderSession()
        val adapter = newAdapter(
            session = session,
            entries = listOf("cover.png", "page-1.png", "page-2.png"),
            doublePageReading = true,
            coverSinglePage = true
        )

        try {
            assertEquals(4, adapter.itemCount)
            assertEquals(3, adapter.readerItemCount())
            assertEquals(1L, adapter.getItemId(1))
        } finally {
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun horizontalPagesKeepTheCapturedViewportHeightInBothDirections() {
        val context = RuntimeEnvironment.getApplication()
        listOf(
            AppSettings.READING_DIRECTION_LEFT_TO_RIGHT,
            AppSettings.READING_DIRECTION_RIGHT_TO_LEFT
        ).forEach { direction ->
            val session = FakeReaderSession()
            val adapter = newAdapter(
                session = session,
                entries = listOf("page.png"),
                readingDirection = direction,
                archiveFileName = "reader-$direction.cbz"
            )
            val holder = createHolder(adapter, RecyclerView(context))

            try {
                bind(adapter, holder, 0)

                assertEquals(
                    "horizontal page height should use the captured reader viewport for $direction",
                    1_800,
                    holder.itemView.layoutParams.height
                )
            } finally {
                closeAndAwait(adapter, session)
            }
        }
    }

    @Test
    fun successfulPreviewIsStoredUnderItsSourceEntryPosition() {
        val decodedBitmap = AtomicReference<Bitmap>()
        val session = FakeReaderSession(previewDecoder = {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(decodedBitmap::set)
        })
        val adapter = newAdapter(session, listOf("page.png"))

        try {
            requestPreview(adapter, 0)

            assertTrue("decoded preview should enter the source-position cache", awaitCondition {
                val bitmap = decodedBitmap.get()
                bitmap != null && cachedPreview(adapter, 0) === bitmap
            })
        } finally {
            closeAndAwait(adapter, session)
            decodedBitmap.get()?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    fun missingAndCorruptPreviewsAreRecordedWithoutStoppingOtherDecodeTasks() {
        val requests = CountDownLatch(2)
        val session = FakeReaderSession(previewDecoder = { entryName ->
            requests.countDown()
            when (entryName) {
                "missing.png" -> null
                "corrupt.png" -> error("corrupt image")
                else -> error("unexpected entry: $entryName")
            }
        })
        val adapter = newAdapter(session, listOf("missing.png", "corrupt.png"))

        try {
            requestPreview(adapter, 0)
            requestPreview(adapter, 1)

            assertTrue("both entries should reach the fake reader session", requests.await(5, TimeUnit.SECONDS))
            assertTrue("failed previews should be recorded and loading state cleared", awaitCondition {
                val failed = privateSet(adapter, "failedPreviewPages")
                val loading = privateSet(adapter, "loadingPreviewPages")
                failed.containsAll(listOf(0, 1)) && loading.isEmpty()
            })
        } finally {
            closeAndAwait(adapter, session)
        }
    }

    @Test
    fun recycledHolderDoesNotShowPreviewThatFinishesForItsPreviousPage() {
        val decodeStarted = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val oldPageBitmap = AtomicReference<Bitmap>()
        val session = FakeReaderSession(previewDecoder = { entryName ->
            if (entryName == "old-page.png") {
                decodeStarted.countDown()
                awaitUninterruptibly(releaseDecode)
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(oldPageBitmap::set)
            } else {
                null
            }
        })
        val adapter = newAdapter(session, listOf("old-page.png", "current-page.png"))
        val recyclerView = RecyclerView(RuntimeEnvironment.getApplication())
        val holder = createHolder(adapter, recyclerView)

        try {
            bind(adapter, holder, 0)
            assertTrue("the old page decode should start", decodeStarted.await(5, TimeUnit.SECONDS))

            recycle(adapter, holder)
            bind(adapter, holder, 1)
            releaseDecode.countDown()

            assertTrue("late decode should finish and clear loading state", awaitCondition {
                privateSet(adapter, "loadingPreviewPages").isEmpty()
            })
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val imageView = privateField(holder, "imageView") as ImageView
            val displayedBitmap = (imageView.drawable as? BitmapDrawable)?.bitmap
            assertNull("the current page has no preview in this fixture", displayedBitmap)
        } finally {
            releaseDecode.countDown()
            closeAndAwait(adapter, session)
            oldPageBitmap.get()?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    @Test
    fun closeWaitsForInflightPreviewAndDiscardsItsLateBitmap() {
        val decodeStarted = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val lateBitmap = AtomicReference<Bitmap>()
        val session = FakeReaderSession(previewDecoder = {
            decodeStarted.countDown()
            awaitUninterruptibly(releaseDecode)
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(lateBitmap::set)
        })
        val adapter = newAdapter(session, listOf("slow.png"))

        try {
            requestPreview(adapter, 0)
            assertTrue("preview decode should start", decodeStarted.await(5, TimeUnit.SECONDS))

            adapter.close()
            adapter.close()
            assertFalse("the reader session must remain open while decode is active", session.closed.await(50, TimeUnit.MILLISECONDS))

            releaseDecode.countDown()
            assertTrue("the session should close after decode stops", session.closed.await(5, TimeUnit.SECONDS))
            assertEquals(1, session.closeCount.get())
            assertTrue("the late bitmap must be discarded after close", lateBitmap.get()?.isRecycled == true)
        } finally {
            releaseDecode.countDown()
            closeAndAwait(adapter, session)
            lateBitmap.get()?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun newAdapter(
        session: FakeReaderSession,
        entries: List<String>,
        doublePageReading: Boolean = false,
        coverSinglePage: Boolean = false,
        readingDirection: String = AppSettings.READING_DIRECTION_LEFT_TO_RIGHT,
        archiveFileName: String = "reader-test.cbz",
    ): MangaPageAdapter {
        val context = RuntimeEnvironment.getApplication()
        return MangaPageAdapter(
            context,
            RecyclerView(context),
            temporaryFolder.newFile(archiveFileName),
            session,
            entries,
            2_400,
            1_800,
            7_200,
            5_400,
            readingDirection,
            doublePageReading,
            coverSinglePage,
            {},
            {}
        )
    }

    private fun requestPreview(adapter: MangaPageAdapter, position: Int) {
        adapter.javaClass.getDeclaredMethod(
            "ensurePreview",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
            .invoke(adapter, position, 100, 0, false)
    }

    private fun cachedPreview(adapter: MangaPageAdapter, position: Int): Bitmap? {
        return adapter.javaClass.getDeclaredMethod("previewBitmap", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(adapter, position) as Bitmap?
    }

    private fun createHolder(adapter: MangaPageAdapter, parent: ViewGroup): RecyclerView.ViewHolder {
        val method = adapter.javaClass.getDeclaredMethod(
            "onCreateViewHolder",
            ViewGroup::class.java,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        return method.invoke(adapter, parent, 0) as RecyclerView.ViewHolder
    }

    private fun bind(adapter: MangaPageAdapter, holder: RecyclerView.ViewHolder, position: Int) {
        adapter.javaClass.methods.first {
            it.name == "onBindViewHolder" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == RecyclerView.ViewHolder::class.java
        }.invoke(adapter, holder, position)
    }

    private fun recycle(adapter: MangaPageAdapter, holder: RecyclerView.ViewHolder) {
        adapter.javaClass.methods.first {
            it.name == "onViewRecycled" && it.parameterTypes.singleOrNull() == RecyclerView.ViewHolder::class.java
        }.invoke(adapter, holder)
    }

    private fun privateSet(adapter: MangaPageAdapter, name: String): Set<*> {
        return adapter.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(adapter) as Set<*>
    }

    private fun privateField(target: Any, name: String): Any? {
        return target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Keep the fake decode active to exercise the adapter's shutdown drain.
            }
        }
    }

    private fun closeAndAwait(adapter: MangaPageAdapter, session: FakeReaderSession) {
        adapter.close()
        assertTrue("reader session should close", session.closed.await(5, TimeUnit.SECONDS))
        assertEquals(1, session.closeCount.get())
    }

    private class FakeReaderSession(
        private val previewDecoder: (String) -> Bitmap? = { null }
    ) : ComicArchive.ImageReaderSession {
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger(0)

        override fun readBounds(entryName: String): ComicArchive.ImageBounds? = null

        override fun decodePreviewForWidth(entryName: String, targetWidth: Int): Bitmap? =
            previewDecoder(entryName)

        override fun decodeImageForWidth(entryName: String, targetWidth: Int): Bitmap? = null

        override fun decodeImageForPage(entryName: String, targetWidth: Int, targetHeight: Int): Bitmap? = null

        override fun decodeRegionForWidth(entryName: String, sourceRect: Rect, targetWidth: Int): Bitmap? = null

        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
        }
    }
}
