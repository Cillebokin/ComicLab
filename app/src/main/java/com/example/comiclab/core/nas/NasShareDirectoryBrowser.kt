package com.example.comiclab.core.nas

import com.example.comiclab.core.archive.ArchiveFormatDetector
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class NasDirectoryEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val isParent: Boolean = false
)

/** Reads directory metadata only. This client never creates, writes, renames, or deletes files. */
internal object NasShareDirectoryBrowser {
    fun list(
        profile: NasShareProfile,
        password: String,
        relativePath: String
    ): List<NasDirectoryEntry> {
        val directoryPath = normalizeRelativePath(relativePath)
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()

        return SMBClient(config).use { client ->
            client.connect(profile.host).use { connection ->
                val username = profile.username.trim()
                val separator = username.indexOf('\\')
                val authentication = if (username.isEmpty()) {
                    AuthenticationContext.anonymous()
                } else {
                    val domain = if (separator > 0) username.substring(0, separator) else null
                    val account = if (separator > 0) username.substring(separator + 1) else username
                    AuthenticationContext(account, password.toCharArray(), domain)
                }
                val session = connection.authenticate(authentication)
                try {
                    val share = session.connectShare(profile.shareName) as? DiskShare
                        ?: throw IllegalStateException("The SMB share is not a disk share")
                    share.use {
                        it.list(directoryPath)
                            .asSequence()
                            .mapNotNull { entry ->
                                val name = entry.fileName
                                if (name.isBlank() || name == "." || name == ".." ||
                                    name.startsWith(".") || name.contains('\\') || name.contains('/')
                                ) {
                                    return@mapNotNull null
                                }

                                val attributes = entry.fileAttributes
                                if ((attributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L ||
                                    (attributes and FileAttributes.FILE_ATTRIBUTE_SYSTEM.value) != 0L
                                ) {
                                    return@mapNotNull null
                                }

                                val isDirectory = (attributes and
                                    FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                                if (!isDirectory && !ArchiveFormatDetector.isSupportedArchiveExtension(
                                        File(name).extension
                                    )
                                ) {
                                    return@mapNotNull null
                                }

                                NasDirectoryEntry(
                                    name = name,
                                    relativePath = joinRelativePath(directoryPath, name),
                                    isDirectory = isDirectory,
                                    sizeBytes = if (isDirectory) 0L else entry.endOfFile,
                                    modifiedAt = entry.lastWriteTime.toEpochMillis()
                                )
                            }
                            .sortedWith(
                                compareByDescending<NasDirectoryEntry> { it.isDirectory }
                                    .thenBy { it.name.lowercase(Locale.ROOT) }
                                    .thenBy { it.name }
                            )
                            .toList()
                    }
                } finally {
                    session.close()
                }
            }
        }
    }

    fun parentPath(relativePath: String): String {
        val normalized = normalizeRelativePath(relativePath)
        val separator = normalized.lastIndexOf('\\')
        return if (separator < 0) "" else normalized.substring(0, separator)
    }

    private fun normalizeRelativePath(path: String): String {
        val segments = path.split('/', '\\').filter(String::isNotBlank)
        require(segments.none { it == "." || it == ".." || it.contains(':') }) {
            "Invalid path inside the SMB share"
        }
        return segments.joinToString("\\")
    }

    private fun joinRelativePath(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent\\$child"
}
