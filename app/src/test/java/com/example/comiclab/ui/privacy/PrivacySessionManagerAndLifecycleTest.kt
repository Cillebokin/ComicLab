package com.example.comiclab.ui.privacy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.InitializationProvider
import com.example.comiclab.core.nas.NasShareArchiveDownloader
import com.example.comiclab.core.store.PrivacyStore
import java.io.File
import java.io.IOException
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class PrivacySessionManagerAndLifecycleTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var storageRoot: File
    private lateinit var ordinaryDirectory: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storageRoot = temporaryFolder.newFolder("storage").canonicalFile
        ordinaryDirectory = File(storageRoot, "ordinary").apply { mkdir() }
        ShadowEnvironment.setExternalStorageDirectory(storageRoot.toPath())
    }

    @After
    fun tearDown() {
        PrivacySessionManager.lock()
        PrivacySessionManager.consumePendingNormalRestorePath()
    }

    @Test
    fun repeatLockInvalidatesSessionsAndKeepsTheOriginalRestorePathOnce() {
        val beforeUnlock = PrivacySessionManager.sessionEpoch
        assertTrue(PrivacySessionManager.enterPrivacySpace(ordinaryDirectory.path, beforeUnlock))
        val opened = PrivacySessionManager.sessionEpoch
        assertTrue(PrivacySessionManager.isCurrentSession(opened))
        assertEquals(ordinaryDirectory.path, PrivacySessionManager.returnNormalPath)

        PrivacySessionManager.lock()
        PrivacySessionManager.lock()

        assertFalse(PrivacySessionManager.isUnlocked)
        assertFalse(PrivacySessionManager.isInPrivacySpace)
        assertFalse(PrivacySessionManager.isCurrentSession(opened))
        assertTrue(PrivacySessionManager.sessionEpoch > opened)
        assertEquals(ordinaryDirectory.path, PrivacySessionManager.consumePendingNormalRestorePath())
        assertNull(PrivacySessionManager.consumePendingNormalRestorePath())

        enterPrivacySpace()
        assertFalse(PrivacySessionManager.isCurrentSession(opened))
        assertTrue(PrivacySessionManager.isCurrentSession(PrivacySessionManager.sessionEpoch))
    }

    @Test
    fun ordinaryLockRejectsPendingAuthenticationWithoutSchedulingAPathRestore() {
        val pendingAuthentication = PrivacySessionManager.sessionEpoch
        var notifications = 0
        val listener = PrivacySessionManager.Listener { notifications++ }
        PrivacySessionManager.addListener(listener)
        try {
            PrivacySessionManager.lock()

            assertFalse(PrivacySessionManager.enterPrivacySpace(ordinaryDirectory.path, pendingAuthentication))
            assertFalse(PrivacySessionManager.isUnlocked)
            assertEquals(1, notifications)
            assertNull(PrivacySessionManager.consumePendingNormalRestorePath())
        } finally {
            PrivacySessionManager.removeListener(listener)
        }
    }

    @Test
    fun regularDirectoryMustBeRestoredBeforeEnteringAgain() {
        enterPrivacySpace()
        PrivacySessionManager.lock()
        val lockedEpoch = PrivacySessionManager.sessionEpoch

        assertFalse(PrivacySessionManager.enterPrivacySpace(null, lockedEpoch))
        assertFalse(PrivacySessionManager.isUnlocked)
        assertEquals(lockedEpoch, PrivacySessionManager.sessionEpoch)
        assertEquals(ordinaryDirectory.path, PrivacySessionManager.consumePendingNormalRestorePath())
        assertNull(PrivacySessionManager.consumePendingNormalRestorePath())

        assertTrue(PrivacySessionManager.enterPrivacySpace(ordinaryDirectory.path, lockedEpoch))
        assertEquals(ordinaryDirectory.path, PrivacySessionManager.exitPrivacySpace())
    }

    @Test
    fun manualExitRevokesTheSessionAndReturnsThePathWithoutQueuingAnotherRestore() {
        enterPrivacySpace()

        assertEquals(ordinaryDirectory.path, PrivacySessionManager.exitPrivacySpace())
        assertFalse(PrivacySessionManager.isUnlocked)
        assertNull(PrivacySessionManager.consumePendingNormalRestorePath())
    }

    @Test
    fun normalDirectoryRestoreFallsBackWhenTheOldPathIsUnavailableOrPrivate() {
        val privateDirectory = File(storageRoot, "private").apply { mkdir() }
        assertTrue(PrivacyStore.addPrivacyDirectory(context, privateDirectory))

        assertEquals(ordinaryDirectory, PrivacyAccessGuard.resolveNormalRestoreDirectory(context, ordinaryDirectory.path))
        for (invalid in listOf(File(storageRoot, "gone").path, privateDirectory.path, "relative")) {
            assertEquals(storageRoot, PrivacyAccessGuard.resolveNormalRestoreDirectory(context, invalid))
        }
    }

    @Test
    fun localPathsAndTheVirtualRootRequireTheirOwnAuthorization() {
        val privateDirectory = File(storageRoot, "secret").apply { mkdir() }
        val privateFile = File(privateDirectory, "book.cbz").apply { createNewFile() }
        val outside = temporaryFolder.newFolder("storage-sibling")
        val rootRoute = PrivacyAccessGuard.PRIVACY_VIRTUAL_ROOT_PATH
        assertTrue(PrivacyStore.addPrivacyDirectory(context, privateDirectory))

        assertTrue(PrivacyAccessGuard.canAccessPath(context, ordinaryDirectory.path))
        assertFalse(PrivacyAccessGuard.canAccessPath(context, File(privateDirectory, "../secret/book.cbz").path))
        assertFalse(PrivacyAccessGuard.canAccessFile(context, privateFile))
        assertFalse(PrivacyAccessGuard.canAccessPath(context, rootRoute))

        enterPrivacySpace()

        assertTrue(PrivacyAccessGuard.canAccessPath(context, rootRoute))
        assertTrue(PrivacyAccessGuard.canAccessFile(context, privateFile))
        assertFalse(PrivacyAccessGuard.canAccessPath(context, outside.path))
        assertFalse(PrivacyAccessGuard.canAccessPath(context, "relative"))
        assertFalse(PrivacyAccessGuard.canAccessPath(context, File(storageRoot, "bad\u0000name").path))
    }

    @Test
    fun localFileAuthorizationClassifiesTheFirstSuccessfulResolution() {
        // 模拟首次解析成功，但相同路径在再次解析时不可用。
        val snapshot = File(ordinaryDirectory, "a".repeat(256))
        assumeTrue(PrivacyStore.resolveCanonicalPath(snapshot) == null)
        val file = object : File(ordinaryDirectory, "book.cbz") {
            override fun getCanonicalPath(): String = snapshot.path
        }

        assertTrue(PrivacyAccessGuard.canAccessFile(context, file))
    }

    @Test
    fun firstCanonicalResolutionFailureDeniesAccessEvenWhenUnlocked() {
        val file = object : File(ordinaryDirectory, "book.cbz") {
            override fun getCanonicalPath(): String = throw IOException("Canonical path unavailable")
        }
        enterPrivacySpace()

        assertFalse(PrivacyAccessGuard.canAccessFile(context, file))
    }

    @Test
    fun onlyRealDescendantDirectoriesCanBeMarked() {
        val nonDirectory = File(storageRoot, "file.cbz").apply { createNewFile() }
        val outside = temporaryFolder.newFolder("outside")

        assertTrue(PrivacyAccessGuard.canMarkDirectory(ordinaryDirectory))
        assertFalse(PrivacyAccessGuard.canMarkDirectory(storageRoot))
        assertFalse(PrivacyAccessGuard.canMarkDirectory(outside))
        assertFalse(PrivacyAccessGuard.canMarkDirectory(nonDirectory))
        assertFalse(PrivacyAccessGuard.canMarkDirectory(File(storageRoot, "missing")))
    }

    @Test
    fun ordinaryNasCacheFilesRemainReadableButAreNotLocalBrowserPaths() {
        val version = File(NasShareArchiveDownloader.cacheDirectory(context.cacheDir), "a".repeat(64))
            .apply { mkdirs() }
        val cachedArchive = File(version, "book.cbz").apply { createNewFile() }
        val missingArchive = File(version, "evicted.cbz")
        val otherFile = File(context.cacheDir, "other.cbz").apply { createNewFile() }

        assertTrue(PrivacyAccessGuard.canAccessFile(context, cachedArchive))
        assertTrue(PrivacyAccessGuard.canAccessFile(context, missingArchive))
        assertFalse(PrivacyAccessGuard.canAccessFile(context, otherFile))
        assertFalse(PrivacyAccessGuard.canAccessPath(context, version.path))
        assertFalse(PrivacyAccessGuard.canMarkDirectory(version))
    }

    @Test
    fun markedNasCacheFilesRequireAnUnlockedSession() {
        val version = File(NasShareArchiveDownloader.cacheDirectory(context.cacheDir), "b".repeat(64))
            .apply { mkdirs() }
        val archive = File(version, "book.cbz").apply { createNewFile() }
        assertTrue(PrivacyStore.addPrivacyDirectory(context, version))

        assertFalse(PrivacyAccessGuard.canAccessFile(context, archive))
        enterPrivacySpace()
        assertTrue(PrivacyAccessGuard.canAccessFile(context, archive))
        PrivacySessionManager.lock()
        assertFalse(PrivacyAccessGuard.canAccessFile(context, archive))
    }

    @Test
    fun systemScreenOffRevokesTheSessionBeforeBackgroundDebounce() {
        enterPrivacySpace()
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(PrivacySessionManager.isUnlocked)
        assertEquals(ordinaryDirectory.path, PrivacySessionManager.consumePendingNormalRestorePath())
    }

    @Test
    fun processBackgroundLocksButActivityRecreationDoesNot() {
        val process = ProcessLifecycleOwner.get().lifecycle
        if (process.currentState == Lifecycle.State.INITIALIZED) {
            // Robolectric 不一定自动创建库提供的 Startup provider。
            Robolectric.buildContentProvider(InitializationProvider::class.java).create()
        }
        assertEquals("while no activity is started", Lifecycle.State.CREATED, process.currentState)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals("after the first activity is resumed", Lifecycle.State.RESUMED, process.currentState)
        enterPrivacySpace()
        activity.recreate()
        assertEquals("after recreation", Lifecycle.State.RESUMED, process.currentState)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertTrue(PrivacySessionManager.isUnlocked)
        assertNull(PrivacySessionManager.consumePendingNormalRestorePath())

        activity.pause().stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(PrivacySessionManager.isUnlocked)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("after debounce", Lifecycle.State.CREATED, process.currentState)
        assertFalse(PrivacySessionManager.isUnlocked)
        assertEquals(ordinaryDirectory.path, PrivacySessionManager.consumePendingNormalRestorePath())
        activity.destroy()
    }

    private fun enterPrivacySpace() {
        assertTrue(PrivacySessionManager.enterPrivacySpace(ordinaryDirectory.path, PrivacySessionManager.sessionEpoch))
    }
}
