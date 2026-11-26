package com.example.comiclab.core.settings

import android.app.backup.BackupAgent
import android.app.backup.BackupAgentHelper
import android.content.Context
import android.os.ParcelFileDescriptor
import com.example.comiclab.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class PrivacyBackupRulesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val privacyExcludes = setOf("sharedpref" to ".", "file" to "crash_logs")

    @Test
    fun cloudBackupExcludesPreferencesAndCrashLogs() {
        assertPrivacyExcludes(R.xml.data_extraction_rules, "cloud-backup", 2)
    }

    @Test
    fun deviceTransferExcludesPreferencesAndCrashLogs() {
        assertPrivacyExcludes(R.xml.data_extraction_rules, "device-transfer", 2)
    }

    @Test
    fun legacyBackupExcludesPreferencesAndCrashLogs() {
        assertPrivacyExcludes(R.xml.backup_rules, "full-backup-content", 1)
    }

    @Test
    fun oldBackupCannotRestoreLogsIntoAnAbsentDirectoryAndOrdinaryPayloadStillRestores() {
        val context = RuntimeEnvironment.getApplication()
        val agent = context.applicationInfo.backupAgentName?.let { name ->
            Class.forName(name).asSubclass(BackupAgent::class.java).getDeclaredConstructor().newInstance()
        } ?: BackupAgentHelper()
        ReflectionHelpers.callInstanceMethod<Unit>(
            agent, "attach", ReflectionHelpers.ClassParameter.from(Context::class.java, context)
        )
        agent.onCreate()
        val logDirectory = File(context.filesDir, "crash_logs")
        val log = File(logDirectory, "nested/crash_legacy.txt")
        val ordinary = File(context.filesDir, "crash_logs_other/ordinary.txt")
        assertFalse(logDirectory.exists())

        val legacyBytes = ByteArray(DEFAULT_BUFFER_SIZE + 3) { 42 }
        val ordinaryBytes = "ordinary content".toByteArray()
        val payload = temporaryFolder.newFile().apply { writeBytes(legacyBytes + ordinaryBytes) }
        ParcelFileDescriptor.open(payload, ParcelFileDescriptor.MODE_READ_ONLY).use { data ->
            agent.onRestoreFile(data, 0, logDirectory, BackupAgent.TYPE_DIRECTORY, -1, 0)
            agent.onRestoreFile(data, legacyBytes.size.toLong(), log, BackupAgent.TYPE_FILE, -1, 0)
            assertFalse(logDirectory.exists())

            agent.onRestoreFile(data, ordinaryBytes.size.toLong(), ordinary, BackupAgent.TYPE_FILE, -1, 0)
            assertEquals("ordinary content", ordinary.readText())
        }
    }

    private fun assertPrivacyExcludes(resource: Int, section: String, sectionDepth: Int) {
        val excludes = mutableSetOf<Pair<String?, String?>>()
        RuntimeEnvironment.getApplication().resources.getXml(resource).use { parser ->
            var inSection = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.END_TAG && parser.depth == sectionDepth && inSection) break
                if (parser.eventType != XmlPullParser.START_TAG) continue
                if (parser.depth == sectionDepth) inSection = parser.name == section
                if (inSection && parser.depth == sectionDepth + 1 && parser.name == "exclude") {
                    excludes += parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                }
            }
        }
        assertTrue("$section exclusions: $excludes", excludes.containsAll(privacyExcludes))
    }
}
