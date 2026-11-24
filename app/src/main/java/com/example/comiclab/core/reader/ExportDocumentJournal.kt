package com.example.comiclab.core.reader

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.comiclab.core.store.PrefsGateway
import java.io.IOException
import java.io.OutputStream
import org.json.JSONObject

/** Keeps a durable record of SAF exports until the stream has closed or the target has been removed. */
internal class ExportDocumentJournal(private val context: Context) {
    private val preferences = PrefsGateway.open(context, "reader_export_documents")

    data class InterruptedExport(val uri: String, val name: String, val writing: Boolean)

    fun reserve(uri: Uri, suggestedName: String): PendingDocument? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty()) return null
        val key = uri.toString()
        val name = if (uri.authority == "com.android.externalstorage.documents") {
            uri.lastPathSegment?.substringAfter(':')?.substringAfterLast('/').orEmpty()
                .ifBlank { suggestedName }
        } else suggestedName
        synchronized(lock) {
            // An old URI may still refer to a valid file. Only start on a fresh picker result.
            if (key in activeDocuments || preferences.contains(key)) return null
            if (!save(key, name, writing = false)) return null
            activeDocuments.add(key)
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return PendingDocument(uri, name)
    }

    fun interruptedExports(): List<InterruptedExport> = synchronized(lock) {
        preferences.all.mapNotNull { (key, value) ->
            if (key in activeDocuments || value !is String) return@mapNotNull null
            runCatching {
                val row = JSONObject(value)
                InterruptedExport(key, row.getString("name"), row.getBoolean("writing"))
            }.getOrNull()
        }.sortedBy { it.name }
    }

    fun acknowledge(records: List<InterruptedExport>) = synchronized(lock) {
        val editor = preferences.edit()
        records.forEach { if (it.uri !in activeDocuments) editor.remove(it.uri) }
        editor.apply()
    }

    private fun save(key: String, name: String, writing: Boolean): Boolean = preferences.edit()
        .putString(key, JSONObject().put("name", name).put("writing", writing).toString())
        .commit()

    inner class PendingDocument internal constructor(private val uri: Uri, private val name: String) {
        private val key = uri.toString()

        fun openOutput(): OutputStream {
            synchronized(lock) {
                check(key in activeDocuments)
                if (!save(key, name, writing = true)) throw IOException("Could not record export write")
            }
            return context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Destination is not writable")
        }

        fun complete() = finish(removeRecord = true)

        fun abandon() {
            if (!synchronized(lock) { key in activeDocuments }) return
            val resolver = context.contentResolver
            val deleted = runCatching {
                DocumentsContract.isDocumentUri(context, uri) && DocumentsContract.deleteDocument(resolver, uri)
            }.getOrDefault(false) || runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
            finish(removeRecord = deleted)
        }

        private fun finish(removeRecord: Boolean) = synchronized(lock) {
            if (removeRecord && !preferences.edit().remove(key).commit()) {
                Log.w("ReaderExport", "Could not clear the export record")
            }
            activeDocuments.remove(key)
            Unit
        }
    }

    private companion object {
        val lock = Any()
        val activeDocuments = mutableSetOf<String>()
    }
}
