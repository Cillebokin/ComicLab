package com.example.comiclab.ebook

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** 预览仅保留显示所需数据；完整章节和资源会话在准备完成时释放。 */
internal data class EbookPreview(
    val title: String,
    val author: String?,
    val chapterCount: Int,
    val cover: Bitmap?
)

internal fun prepareEbookPreview(session: EbookSession, coverMaxDimension: Int): EbookPreview {
    var cover: Bitmap? = null
    try {
        return session.use {
            checkEbookCancellation()
            val book = session.book
            book.coverResourceId?.let { id ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                session.openResource(id)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                checkEbookCancellation()
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    val maxDimension = coverMaxDimension.coerceIn(1, 720)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension && sample < (1 shl 30)) {
                        sample *= 2
                    }
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    session.openResource(id)?.use { cover = BitmapFactory.decodeStream(it, null, options) }
                }
            }
            checkEbookCancellation()
            EbookPreview(book.title, book.author, book.chapters.size, cover)
        }
    } catch (error: Throwable) {
        cover?.recycle()
        throw error
    }
}
