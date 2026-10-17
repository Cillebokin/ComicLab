package com.example.comiclab

import com.example.comiclab.ui.browser.DirectoryBrowserController
import com.example.comiclab.core.concurrent.LatestTaskDispatcher
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DirectoryBrowserControllerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val generation = AtomicInteger()
    private val counts = DirectoryChildCountCache()
    private val scrollStates = DirectoryScrollStateCache()
    private val pending = mutableSetOf<String>()
    private val uiTasks = ConcurrentLinkedQueue<() -> Unit>()
    private val childTasks = ArrayDeque<Runnable>()
    private val results = mutableListOf<DirectoryBrowserController.Result>()
    private val root by lazy { temporaryFolder.newFolder("storage") }
    private val browser by lazy {
        DirectoryBrowserController(root.absolutePath, LatestTaskDispatcher(executor),
            Executor { childTasks.add(it) }, generation, counts, scrollStates, pending)
    }

    @After fun stopExecutor() { executor.shutdownNow() }

    @Test fun resolvesInvalidPathToRootFiltersHiddenFilesAndSortsDirectoriesFirst() {
        File(root, "z-folder").mkdir()
        File(root, "b.txt").writeText("b")
        File(root, "A.txt").writeText("a")
        File(root, ".hidden").writeText("hidden")
        load(File(root, "missing").absolutePath)
        drainLoad()
        val result = results.single() as DirectoryBrowserController.Result.Loaded
        assertEquals(root.absolutePath, result.resolvedPath)
        assertEquals(listOf("z-folder", "A.txt", "b.txt"), result.items.map { it.file!!.name })
        assertFalse(result.items.any { it.isParent })
    }

    @Test fun subdirectoryIncludesParentRowCachedCountsAndRequestedScrollState() {
        val directory = File(root, "parent").apply { mkdir() }
        val child = File(directory, "child").apply { mkdir() }
        counts.put(child.absolutePath, child.lastModified(), 7)
        val state = FileListScrollState(directory.absolutePath, 1, -13, child.absolutePath, false)
        load(directory.absolutePath, scrollState = state)
        drainLoad()
        val result = results.single() as DirectoryBrowserController.Result.Loaded
        assertTrue(result.items.first().isParent)
        assertEquals(7, result.items[1].childCount)
        assertEquals(state, result.scrollState)
    }

    @Test fun newerDirectorySuppressesAlreadyPostedResult() {
        val first = File(root, "first").apply { mkdir() }
        val second = File(root, "second").apply { mkdir() }
        load(first.absolutePath)
        awaitWorker()
        load(second.absolutePath)
        drainLoad()
        assertEquals(listOf(second.absolutePath), results.map {
            (it as DirectoryBrowserController.Result.Loaded).resolvedPath
        })
    }

    @Test fun replacesQueuedDirectoryLoadsWithNewestRequest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute {
            started.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        load(File(root, "first").apply { mkdir() }.absolutePath)
        val second = File(root, "second").apply { mkdir() }
        load(second.absolutePath)
        release.countDown()
        drainLoad()
        assertEquals(second.absolutePath,
            (results.single() as DirectoryBrowserController.Result.Loaded).resolvedPath)
    }

    @Test fun supersededRunningSortCannotPublishItsDirectory() {
        val first = File(root, "first").apply { mkdir() }
        val second = File(root, "second").apply { mkdir() }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        load(first.absolutePath, order = {
            started.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            DirectorySortOrder.NAME_ASC
        })
        assertTrue(started.await(10, TimeUnit.SECONDS))
        load(second.absolutePath)
        release.countDown()
        drainLoad()
        assertEquals(second.absolutePath,
            (results.single() as DirectoryBrowserController.Result.Loaded).resolvedPath)
    }

    @Test fun permissionDenialReturnsPresentableErrorWithoutSchedulingWork() {
        load(root.absolutePath, hasPermission = false)
        assertEquals(DirectoryBrowserController.Result.PermissionRequired, results.single())
        assertEquals(0, executor.queue.size)
        assertTrue(uiTasks.isEmpty())
    }

    @Test fun parentNavigationRestoresCachedAnchorOtherwiseCentersExitedDirectory() {
        val parent = File(root, "parent").apply { mkdir() }
        val current = File(parent, "current").apply { mkdir() }
        val uncached = browser.parentNavigation(current.absolutePath)
        assertEquals(parent.absolutePath, uncached.path)
        assertEquals(current.absolutePath, uncached.pathToCenter)
        assertNull(uncached.scrollState)
        val state = FileListScrollState(parent.absolutePath, 2, -19, current.absolutePath, false)
        scrollStates.save(state)
        val cached = browser.parentNavigation(current.absolutePath)
        assertEquals(state, cached.scrollState)
        assertNull(cached.pathToCenter)
    }

    @Test fun invalidatedChildCountIsRequeuedAndPendingRequestsAreDeduplicated() {
        val directory = File(root, "child").apply { mkdir() }
        File(directory, "page.txt").writeText("page")
        val updates = mutableListOf<Int>()
        repeat(2) { browser.requestChildCount(directory, { true }, { root.absolutePath },
            { uiTasks.add(it) }, { _, count -> updates.add(count) }) }
        assertEquals(1, childTasks.size)
        childTasks.removeFirst().run()
        counts.invalidate(directory.absolutePath)
        drainUi()
        assertTrue(updates.isEmpty())
        assertEquals(1, childTasks.size)
        childTasks.removeFirst().run()
        drainUi()
        assertEquals(listOf(1), updates)
        assertTrue(pending.isEmpty())
    }

    @Test fun changedParentAndStoppedOwnerSuppressChildCountUpdates() {
        val directory = File(root, "child").apply { mkdir() }
        val updates = mutableListOf<Int>()
        var currentPath = root.absolutePath
        var active = true
        fun request() = browser.requestChildCount(directory, { active }, { currentPath },
            { uiTasks.add(it) }, { _, count -> updates.add(count) })
        request()
        childTasks.removeFirst().run()
        currentPath = File(root, "other").absolutePath
        drainUi()
        assertTrue(updates.isEmpty())
        assertTrue(pending.isEmpty())
        currentPath = root.absolutePath
        request()
        childTasks.removeFirst().run()
        active = false
        drainUi()
        assertTrue(updates.isEmpty())
        assertTrue(pending.isEmpty())
    }

    @Test fun rejectedChildCountSubmissionClearsPendingPath() {
        val rejected = DirectoryBrowserController(root.absolutePath, LatestTaskDispatcher(executor),
            Executor { throw java.util.concurrent.RejectedExecutionException() },
            generation, counts, scrollStates, pending)
        rejected.requestChildCount(File(root, "child"), { true }, { root.absolutePath },
            { uiTasks.add(it) }, { _, _ -> fail("Rejected work must not update rows") })
        assertTrue(pending.isEmpty())
    }

    @Test fun directoryChangedDuringCountIsNotCachedAndIsRecounted() {
        var timestamp = 1L
        var reads = 0
        val directory = object : File(root, "child") {
            override fun lastModified() = timestamp
            override fun listFiles(): Array<File> {
                reads++
                if (reads == 1) timestamp++
                return Array(reads) { File(this, "page$it") }
            }
        }
        val updates = mutableListOf<Int>()
        browser.requestChildCount(directory, { true }, { root.absolutePath },
            { uiTasks.add(it) }, { _, count -> updates.add(count) })
        childTasks.removeFirst().run()
        assertNull(counts.get(directory.absolutePath, timestamp))
        drainUi()
        assertTrue(updates.isEmpty())
        childTasks.removeFirst().run()
        drainUi()
        assertEquals(listOf(2), updates)
        assertEquals(2, counts.get(directory.absolutePath, timestamp))
        assertTrue(pending.isEmpty())
    }

    @Test fun directoryChangedBeforeApplyIsRecountedWithoutApplyingStaleCount() {
        var timestamp = 1L
        var children = 1
        val directory = object : File(root, "child") {
            override fun lastModified() = timestamp
            override fun listFiles() = Array(children) { File(this, "page$it") }
        }
        val updates = mutableListOf<Int>()
        browser.requestChildCount(directory, { true }, { root.absolutePath },
            { uiTasks.add(it) }, { _, count -> updates.add(count) })
        childTasks.removeFirst().run()
        timestamp++
        children = 3
        drainUi()
        assertTrue(updates.isEmpty())
        childTasks.removeFirst().run()
        drainUi()
        assertEquals(listOf(3), updates)
        assertTrue(pending.isEmpty())
    }

    @Test fun deniedChildListingReportsZeroWithoutCachingUnknownCount() {
        val directory = object : File(root, "child") {
            override fun listFiles(): Array<File> = throw SecurityException("fixture denied")
        }
        val updates = mutableListOf<Int>()
        browser.requestChildCount(directory, { true }, { root.absolutePath },
            { uiTasks.add(it) }, { _, count -> updates.add(count) })
        childTasks.removeFirst().run()
        drainUi()
        assertEquals(listOf(0), updates)
        assertNull(counts.get(directory.absolutePath, directory.lastModified()))
        assertTrue(pending.isEmpty())
    }

    private fun load(path: String, hasPermission: Boolean = true,
        scrollState: FileListScrollState? = null,
        order: () -> DirectorySortOrder = { DirectorySortOrder.NAME_ASC }) {
        browser.load(generation.incrementAndGet(), path, hasPermission,
            order, scrollState, { true },
            { uiTasks.add(it) }, { results.add(it) })
    }

    private fun awaitWorker() {
        val barrier = CountDownLatch(1)
        executor.execute { barrier.countDown() }
        assertTrue("Directory worker timed out", barrier.await(10, TimeUnit.SECONDS))
    }
    private fun drainLoad() { awaitWorker(); drainUi() }
    private fun drainUi() { while (true) (uiTasks.poll() ?: break).invoke() }
}
