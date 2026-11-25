package com.example.comiclab.core.reader

import android.content.Context
import android.net.Uri
import com.example.comiclab.R
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.core.store.PrivacyStore
import java.io.File
import java.util.concurrent.CancellationException

/** Source identity shared by translation, conversion, and enhanced-image exports. */
internal object ReaderDerivationPrivacy {
    const val SOURCE_PATH = "derivation_source_path"

    fun ordinaryPath(context: Context, file: File?): String? = file?.let(PrivacyStore::resolveCanonicalPath)
        ?.takeIf { File(it).isFile && !PrivacyStore.isCanonicalPathUnderPrivacyDirectory(context, it) }

    fun allows(context: Context, file: File?, expectedPath: String?): Boolean =
        expectedPath != null && ordinaryPath(context, file) == expectedPath

    fun check(context: Context, file: File?, expectedPath: String?) {
        if (!allows(context, file, expectedPath)) throw CancellationException()
    }

    fun abandonRejectedDestination(context: Context, destination: Uri) {
        val pending = ExportDocumentJournal(context.applicationContext)
            .reserve(destination, context.getString(R.string.reader_export_interrupted_title)) ?: return
        EnhancementRuntime.io.execute { pending.abandonIfEmpty() }
    }
}
