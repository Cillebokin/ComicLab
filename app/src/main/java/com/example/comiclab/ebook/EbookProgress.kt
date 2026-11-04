package com.example.comiclab.ebook

data class EbookProgress(
    val chapterIndex: Int,
    val scrollFraction: Float,
    // 1：旧版扣除视口高度的比例；2：完整章节区间，末章以全书底部为终点。
    val coordinateVersion: Int = 2
) {
    fun normalized(): EbookProgress {
        return copy(
            chapterIndex = chapterIndex.coerceAtLeast(0),
            scrollFraction = scrollFraction.coerceIn(0f, 1f),
            coordinateVersion = coordinateVersion.coerceIn(1, 2)
        )
    }
}
