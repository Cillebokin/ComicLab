package com.example.comiclab.core.reader

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.example.comiclab.core.store.PrefsGateway
import com.example.comiclab.core.store.PrivacyStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.json.JSONObject

/** Keeps a durable record of SAF exports until the stream has closed or the target has been removed. */
internal class ExportDocumentJournal(private val context: Context) {
    private val preferences = PrefsGateway.open(context, "reader_export_documents")

    data class InterruptedExport(val uri: String, val name: String, val writing: Boolean, val sourcePath: String? = null)

    fun reserve(uri: Uri, suggestedName: String, sourcePath: String? = null): PendingDocument? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty()) return null
        val key = uri.toString()
        val name = if (uri.authority == "com.android.externalstorage.documents") {
            uri.lastPathSegment?.substringAfter(':')?.substringAfterLast('/').orEmpty()
                .ifBlank { suggestedName }
        } else suggestedName
        synchronized(lock) {
            // An old URI may still refer to a valid file. Only start on a fresh picker result.
            if (key in activeDocuments || preferences.contains(key)) return null
            if (!save(key, name, writing = false, sourcePath)) return null
            activeDocuments.add(key)
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return PendingDocument(uri, name, sourcePath)
    }

    fun interruptedExports(): List<InterruptedExport> = synchronized(lock) {
        preferences.all.mapNotNull { (key, value) ->
            if (key in activeDocuments || value !is String) return@mapNotNull null
            runCatching {
                val row = JSONObject(value)
                InterruptedExport(key, row.getString("name"), row.getBoolean("writing"),
                    row.optString("sourcePath").takeIf(String::isNotBlank))
            }.getOrNull()
        }.sortedBy { it.name }
    }

    fun canShowName(record: InterruptedExport): Boolean {
        val path = record.sourcePath ?: return false
        if (!ReaderDerivationPrivacy.allows(context, File(path), path)) return false
        val uri = Uri.parse(record.uri)
        val target = when {
            uri.scheme == ContentResolver.SCHEME_FILE -> uri.path?.let(::File) ?: return false
            uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty() -> return false
            uri.authority == "com.android.externalstorage.documents" -> localDocument(uri) ?: return false
            uri.authority == "com.android.providers.downloads.documents" -> {
                val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false
                if (!id.startsWith("raw:")) return true
                File(id.removePrefix("raw:"))
            }
            else -> return true
        }
        val canonical = PrivacyStore.resolveCanonicalPath(target) ?: return false
        return !PrivacyStore.isCanonicalPathUnderPrivacyDirectory(context, canonical)
    }

    private fun localDocument(uri: Uri): File? {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = id.split(':', limit = 2)
        if (parts.size != 2) return null
        val root = when (val volume = parts[0]) {
            "primary" -> Environment.getExternalStorageDirectory()
            "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            else -> context.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.directory
        } ?: return null
        val directory = PrivacyStore.resolveCanonicalPath(root)?.let(::File) ?: return null
        return PrivacyStore.resolveCanonicalPath(File(directory, parts[1]))?.let(::File)
            ?.takeIf { it.toPath().startsWith(directory.toPath()) }
    }

    fun acknowledge(records: List<InterruptedExport>) = synchronized(lock) {
        val editor = preferences.edit()
        records.forEach { if (it.uri !in activeDocuments) editor.remove(it.uri) }
        editor.apply()
    }

    private fun save(key: String, name: String, writing: Boolean, sourcePath: String?): Boolean = preferences.edit()
        .putString(key, JSONObject().put("name", name).put("writing", writing).put("sourcePath", sourcePath).toString())
        .commit()

    inner class PendingDocument internal constructor(
        private val uri: Uri, private val name: String, private val sourcePath: String?
    ) {
        private val key = uri.toString()

        fun openOutput(): OutputStream {
            val original = sourcePath?.let(::File)
            ReaderDerivationPrivacy.check(context, original, sourcePath)
            synchronized(lock) {
                check(key in activeDocuments)
                if (!save(key, name, writing = true, sourcePath)) throw IOException("Could not record export write")
            }
            ReaderDerivationPrivacy.check(context, original, sourcePath)
            return context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Destination is not writable")
        }

        fun complete() = finish(removeRecord = true)

        fun abandonIfEmpty() {
            val isEmpty = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { row ->
                    val index = row.getColumnIndex(OpenableColumns.SIZE)
                    row.moveToFirst() && index >= 0 && row.getString(index)?.toLongOrNull() == 0L
                } == true
            }.getOrDefault(false)
            if (isEmpty) abandon() else finish(removeRecord = false)
        }

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
