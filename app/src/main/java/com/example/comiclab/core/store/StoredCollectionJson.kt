package com.example.comiclab.core.store

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 缺少 key 是空集合；读取或解析失败返回 null，写入方必须保留原值。 */
internal fun <T> readStoredCollection(
    context: Context,
    preferencesName: String,
    key: String,
    decode: (JSONObject) -> T
): List<T>? {
    return try {
        val raw = PrefsGateway.open(context, preferencesName).getString(key, null)
            ?: return emptyList()
        val parser = JSONTokener(raw)
        val array = parser.nextValue() as? JSONArray ?: error("Expected an array")
        check(parser.nextClean() == '\u0000') { "Unexpected trailing content" }
        List(array.length()) { index -> decode(array.getJSONObject(index)) }
    } catch (error: Exception) {
        // 不输出原始 JSON 或路径；保留数据，避免把读取失败当成空集合覆盖。
        Log.e("StoredCollection", "Cannot read $preferencesName/$key; data preserved (${error.javaClass.simpleName})")
        null
    }
}

internal fun JSONObject.storedPath(): String =
    (get("path") as? String)?.takeIf { it.isNotBlank() } ?: error("Invalid stored path")
