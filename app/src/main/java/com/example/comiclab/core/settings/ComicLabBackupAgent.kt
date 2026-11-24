package com.example.comiclab.core.settings

import android.app.backup.BackupAgentHelper
import android.os.ParcelFileDescriptor
import com.example.comiclab.core.diagnostics.CrashLogManager
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

class ComicLabBackupAgent : BackupAgentHelper() {
    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File,
        type: Int,
        mode: Long,
        mtime: Long
    ) {
        val crashLogs = File(filesDir, CrashLogManager.CRASH_LOG_DIR_NAME).canonicalFile.toPath()
        // 系统按目录排除旧备份的子项时，要求该目录已经存在。
        if (!destination.canonicalFile.toPath().startsWith(crashLogs)) {
            super.onRestoreFile(data, size, destination, type, mode, mtime)
            return
        }
        if (size == 0L) return

        // 描述符由系统持有，与框架默认恢复一样只借用它读取本条记录。
        val input = FileInputStream(data.fileDescriptor)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Incomplete backup entry")
            remaining -= count
        }
    }
}
