package com.example.comiclab.core.store

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivacyStorePathsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences().edit().clear().commit()
    }

    @Test
    fun storesTheCanonicalPathAndDoesNotMatchDirectoryNamePrefixes() {
        val parent = temporaryFolder.newFolder("comics")
        val privateDirectory = File(parent, "A").apply { mkdir() }
        val alias = File(parent, "./A/../A")
        val sibling = File(parent, "AB").apply { mkdir() }

        assertTrue(PrivacyStore.addPrivacyDirectory(context, alias))
        assertTrue(PrivacyStore.addPrivacyDirectory(context, privateDirectory))

        assertEquals(setOf(privateDirectory.canonicalPath), PrivacyStore.privacyDirectories(context))
        assertEquals(
            setOf(privateDirectory.canonicalPath),
            preferences().getStringSet("privacy_directories", emptySet())
        )
        assertTrue(PrivacyStore.isPrivacyDirectoryMarked(context, alias.path))
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(context, File(alias, "book.cbz")))
        assertFalse(PrivacyStore.isUnderPrivacyDirectory(context, File(sibling, "book.cbz")))
    }

    @Test
    fun removingTheOuterMarkKeepsTheNestedMarkProtected() {
        val outer = temporaryFolder.newFolder("A")
        val middle = File(outer, "S").apply { mkdir() }
        val inner = File(middle, "B").apply { mkdir() }

        assertTrue(PrivacyStore.addPrivacyDirectory(context, inner))
        assertTrue(PrivacyStore.addPrivacyDirectory(context, outer))
        assertEquals(setOf(outer.canonicalPath, inner.canonicalPath), PrivacyStore.privacyDirectories(context))
        assertEquals(listOf(outer.canonicalFile), PrivacyStore.visibleRootDirectories(context))
        assertTrue(PrivacyStore.containsPrivacyDescendant(context, middle.path))
        assertTrue(PrivacyStore.hasOwnOrDescendantPrivacyMark(context, middle.path))
        assertFalse(PrivacyStore.containsPrivacyDescendant(context, inner.path))
        assertTrue(PrivacyStore.hasOwnOrDescendantPrivacyMark(context, inner.path))

        assertTrue(PrivacyStore.removePrivacyDirectory(context, outer))

        assertEquals(listOf(inner.canonicalFile), PrivacyStore.visibleRootDirectories(context))
        assertFalse(PrivacyStore.isUnderPrivacyDirectory(context, outer))
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(context, inner))
    }

    @Test
    fun deletedDirectoriesDoNotAppearAndExplicitSubtreeCleanupKeepsSiblingMarks() {
        val parent = temporaryFolder.newFolder("comics")
        val deleted = File(parent, "A").apply { mkdir() }
        val child = File(deleted, "B").apply { mkdir() }
        val sibling = File(parent, "AB").apply { mkdir() }
        for (directory in listOf(deleted, child, sibling)) {
            assertTrue(PrivacyStore.addPrivacyDirectory(context, directory))
        }

        assertTrue(child.delete())
        assertTrue(deleted.delete())
        assertEquals(listOf(sibling.canonicalFile), PrivacyStore.visibleRootDirectories(context))
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(context, File(child, "book.cbz")))

        assertEquals(2, PrivacyStore.removeDirectorySubtree(context, deleted))
        assertEquals(setOf(sibling.canonicalPath), PrivacyStore.privacyDirectories(context))
        assertFalse(PrivacyStore.isUnderPrivacyDirectory(context, child))
    }

    @Test
    fun invalidPathsNeverBecomeMarksAndCannotBeTreatedAsPublic() {
        val parent = temporaryFolder.newFolder("comics")

        assertNull(PrivacyStore.resolveCanonicalPath("relative/path"))
        assertNull(PrivacyStore.resolveCanonicalPath(""))
        assertNull(PrivacyStore.resolveCanonicalPath(File(parent, "bad\u0000path")))
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(context, File(parent, "bad\u0000path")))
        assertFalse(PrivacyStore.addPrivacyDirectory(context, File(parent, "not-created")))
        assertFalse(PrivacyStore.addPrivacyDirectory(context, temporaryFolder.newFile("book.cbz")))
        assertTrue(PrivacyStore.privacyDirectories(context).isEmpty())
    }

    @Test
    fun unresolvableSubtreesAreProtectedForDestructiveOperations() {
        assertTrue(PrivacyStore.containsPrivacyDescendant(context, "relative/path"))
        assertTrue(PrivacyStore.hasOwnOrDescendantPrivacyMark(context, "relative/path"))
        val ordinaryDirectory = temporaryFolder.newFolder("ordinary")
        assertFalse(PrivacyStore.containsPrivacyDescendant(context, ordinaryDirectory.path))
        assertFalse(PrivacyStore.hasOwnOrDescendantPrivacyMark(context, ordinaryDirectory.path))
    }

    @Test
    fun interruptedCallerReceivesTheActualPersistedResult() {
        val directory = temporaryFolder.newFolder("private")
        assertTrue(PrivacyStore.addPrivacyDirectory(context, directory))
        val interruptibleContext = contextWithCommit { editor ->
            if (Thread.interrupted()) {
                editor.apply()
                false
            } else {
                editor.commit()
            }
        }

        val (removed, interrupted) = try {
            Thread.currentThread().interrupt()
            PrivacyStore.removePrivacyDirectory(interruptibleContext, directory) to
                Thread.currentThread().isInterrupted
        } finally {
            Thread.interrupted()
        }

        assertTrue(removed)
        assertTrue(interrupted)
        assertFalse(PrivacyStore.isUnderPrivacyDirectory(context, directory))
        assertTrue(persistedPaths().isEmpty())
    }

    @Test
    fun failedRemovalRestoresThePreviousProtectionInMemoryAndOnDisk() {
        val directory = temporaryFolder.newFolder("private")
        assertTrue(PrivacyStore.addPrivacyDirectory(context, directory))
        var firstWrite = true
        // 模拟进程退出前尚未执行的异步恢复。
        val failureContext = contextWithCommit(queueApply = {}) { editor ->
            val written = editor.commit()
            if (firstWrite) {
                firstWrite = false
                false
            } else {
                written
            }
        }

        assertFalse(PrivacyStore.removePrivacyDirectory(failureContext, directory))
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(context, directory))
        assertEquals(setOf(directory.canonicalPath), persistedPaths())
    }

    private fun contextWithCommit(
        queueApply: (SharedPreferences.Editor) -> Unit = { it.apply() },
        commitEditor: (SharedPreferences.Editor) -> Boolean
    ): Context =
        object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = super.getSharedPreferences(name, mode)
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putStringSet(
                                key: String?,
                                values: MutableSet<String>?
                            ): SharedPreferences.Editor {
                                editor.putStringSet(key, values)
                                return this
                            }

                            override fun apply() = queueApply(editor)

                            override fun commit(): Boolean = commitEditor(editor)
                        }
                    }
                }
            }
        }

    private fun persistedPaths(): Set<String> {
        val preferencesFile = File(context.dataDir, "shared_prefs/privacy_prefs.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(preferencesFile)
        val values = document.getElementsByTagName("string")
        return (0 until values.length).map { values.item(it).textContent }.toSet()
    }

    private fun preferences() = PrefsGateway.open(context, "privacy_prefs")
}
