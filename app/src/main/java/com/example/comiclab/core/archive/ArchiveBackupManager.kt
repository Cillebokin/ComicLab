package com.example.comiclab.core.archive

import java.io.File

/** 保留旧备份目录定位；新删除流程不再创建完整压缩包副本。 */
internal object ArchiveBackupManager {
    private const val DELETE_BACKUP_DIR_NAME = ".ComicLabBackups"

    fun backupDirectory(file: File): File = File(file.parentFile, DELETE_BACKUP_DIR_NAME)
}
