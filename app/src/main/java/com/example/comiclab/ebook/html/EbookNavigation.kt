package com.example.comiclab.ebook.html

import android.net.Uri

internal const val EBOOK_BASE_URL = "https://comiclab.invalid/"

/** 仅接受清洗后的本书锚点，跳转由阅读脚本完成，不重新加载文档。 */
internal fun ebookAnchorFromUrl(uri: Uri): String? {
    val localFragment = uri.scheme == null && uri.authority == null && uri.path.isNullOrEmpty()
    val bookUrl = uri.scheme == "https" && uri.host == "comiclab.invalid" && uri.port == -1 &&
        uri.userInfo == null && uri.path in listOf("", "/")
    if ((!localFragment && !bookUrl) || uri.query != null) return null
    return uri.fragment?.takeIf { it.matches(Regex("chapter-[0-9]+(?:-a-[0-9a-f]+)?")) }
}
