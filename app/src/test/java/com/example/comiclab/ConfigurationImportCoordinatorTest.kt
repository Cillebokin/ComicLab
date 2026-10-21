package com.example.comiclab

import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.comiclab.core.settings.ComicLabAppSettings
import com.example.comiclab.core.settings.ComicLabConfiguration
import com.example.comiclab.core.settings.ComicLabConfigurationImportSummary
import com.example.comiclab.core.settings.ComicLabConfigurationStore
import com.example.comiclab.core.settings.ComicLabFavoriteComic
import com.example.comiclab.core.store.FavoriteComicStore
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConfigurationImportCoordinatorTest {

    private val context = RuntimeEnvironment.getApplication()
    private val preferenceNames = listOf(
        "app_settings",
        "favorite_comic_prefs",
        "favorite_path_prefs",
        "reading_history_prefs",
        "ebook_progress_prefs",
        "reader_prefs"
    )

    @Before
    fun clearState() {
        preferenceNames.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @After
    fun resetState() {
        clearState()
    }

    @Test
    fun importFinishesAfterScreenStopsAndDeliversResultToNextScreen() {
        val root = Files.createTempDirectory("comiclab-import-lifecycle").toFile()
        try {
            val comic = File(root, "comic.cbz").apply { writeText("comic") }
            val configuration = ComicLabConfiguration(
                favoriteComics = listOf(
                    ComicLabFavoriteComic(comic.absolutePath, 1L, comic.length(), comic.lastModified())
                ),
                appSettings = ComicLabAppSettings(darkModeEnabled = true)
            )
            val executor = QueuedExecutor()
            val coordinator = ConfigurationImportCoordinator(
                executor = executor,
                dispatchToMain = { action -> action() },
                importWork = { appContext, _ ->
                    val summary = ComicLabConfigurationStore.importAndMerge(
                        appContext,
                        configuration,
                        applyAppSettings = false
                    )
                    ConfigurationImportPayload(summary, configuration.appSettings)
                },
                applySettings = AppSettings::applySnapshot
            )
            val stoppedScreen = RecordingListener()
            coordinator.addListener(stoppedScreen)

            assertTrue(coordinator.start(context, Uri.parse("content://comiclab/config")))
            assertTrue(coordinator.isRunning())
            assertTrue(stoppedScreen.running)
            coordinator.removeListener(stoppedScreen)
            executor.runNext()

            assertFalse(coordinator.isRunning())
            assertEquals(comic.absolutePath, FavoriteComicStore.items(context).single().file.absolutePath)
            assertTrue(AppSettings.isDarkModeEnabled(context))
            assertNull(stoppedScreen.completion)

            val nextScreen = RecordingListener()
            coordinator.addListener(nextScreen)
            assertFalse(nextScreen.running)
            assertEquals(
                ConfigurationImportCompletion.Success(
                    summary = ComicLabConfigurationImportSummary(1, 0),
                    appliedSettings = 1
                ),
                nextScreen.completion
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failureIsRetainedUntilAVisibleScreenCanPresentIt() {
        val executor = QueuedExecutor()
        val coordinator = ConfigurationImportCoordinator(
            executor = executor,
            dispatchToMain = { action -> action() },
            importWork = { _, _ -> error("配置文件读取失败") },
            applySettings = AppSettings::applySnapshot
        )
        val stoppedScreen = RecordingListener()
        coordinator.addListener(stoppedScreen)

        assertTrue(coordinator.start(context, Uri.parse("content://comiclab/config")))
        coordinator.removeListener(stoppedScreen)
        executor.runNext()

        val nextScreen = RecordingListener()
        coordinator.addListener(nextScreen)
        assertEquals(
            ConfigurationImportCompletion.Failure("配置文件读取失败"),
            nextScreen.completion
        )
        assertFalse(AppSettings.isDarkModeEnabled(context))
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }

    private class RecordingListener : ConfigurationImportCoordinator.Listener {
        var running = false
        var completion: ConfigurationImportCompletion? = null

        override fun onImportRunningChanged(running: Boolean) {
            this.running = running
        }

        override fun onImportCompleted(completion: ConfigurationImportCompletion) {
            this.completion = completion
        }
    }
}
