package com.example.comiclab.core.nas

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.comiclab.core.store.PrefsGateway
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Records public staging files outside the public directory before they can be created. */
internal class NasPublicDownloadPartJournal(private val context: Context, storageRoot: File) {
    private val preferences = PrefsGateway.open(context, PREFS_NAME)
    private val root = storageRoot.canonicalFile.toPath()

    fun requireOrdinaryDestination(destination: File) {
        NasShareArchiveDownloader.requireOrdinaryLocalPath(context, destination, root)
    }

    fun <T> withOrdinaryDestination(destination: File, action: () -> T): T =
        NasShareArchiveDownloader.withOrdinaryLocalPath(context, destination, root, action)

    fun createPart(file: File): Closeable = synchronized(lock) {
        val part = resolvePart(file.absolutePath)
            ?: throw IOException("Invalid public NAS staging path")
        val path = part.toPath()
        if (part.path in activePaths || !Files.notExists(path, NOFOLLOW_LINKS)) {
            throw IOException("Public NAS staging path is already in use")
        }
        if (!preferences.edit().putBoolean(part.path, true).commit()) {
            throw IOException("Could not register public NAS staging file")
        }
        try {
            withOrdinaryDestination(part) { Files.createFile(path) }
        } catch (exception: IOException) {
            if (exception is FileAlreadyExistsException || exception is NasShareArchiveDownloader.LocalDownloadPathException) {
                preferences.edit().remove(part.path).apply()
            }
            throw exception
        }
        activePaths += part.path
        Closeable {
            synchronized(lock) {
                activePaths.remove(part.path)
                if (Files.notExists(path, NOFOLLOW_LINKS)) preferences.edit().remove(part.path).apply()
            }
        }
    }

    fun recover(): Int = synchronized(lock) {
        if (!Files.isDirectory(root)) return@synchronized 0
        val editor = preferences.edit()
        var removed = 0
        for (record in preferences.all.keys) {
            if (record in activePaths) continue
            try {
                val part = resolvePart(record)
                if (part != null) {
                    val path = part.toPath()
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (attributes.isRegularFile && Files.deleteIfExists(path)) removed++
                }
                editor.remove(record)
            } catch (_: NoSuchFileException) {
                editor.remove(record)
            } catch (exception: IOException) {
                Log.w("ComicLabNAS", "Could not recover a registered public NAS staging file", exception)
            } catch (exception: SecurityException) {
                Log.w("ComicLabNAS", "Public NAS staging file is unavailable", exception)
            }
        }
        editor.apply()
        removed
    }

    private fun resolvePart(record: String): File? {
        val file = File(record)
        if (!file.isAbsolute || !partName.matches(file.name) || '\u0000' in record) return null
        val path = file.toPath()
        if (path.normalize() != path || !path.startsWith(root) || path == root) return null
        return file.takeIf { it.canonicalFile.toPath() == path }
    }

    companion object {
        private const val PREFS_NAME = "nas_public_download_parts"
        private val partName = Regex("\\.comiclab-nas-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.part")
        private val lock = Any()
        private val activePaths = mutableSetOf<String>()

        fun openIfWritable(context: Context): NasPublicDownloadPartJournal? {
            val root = Environment.getExternalStorageDirectory()
            if (!Environment.isExternalStorageManager() ||
                Environment.getExternalStorageState(root) != Environment.MEDIA_MOUNTED
            ) return null
            return NasPublicDownloadPartJournal(context, root)
        }
    }
}
