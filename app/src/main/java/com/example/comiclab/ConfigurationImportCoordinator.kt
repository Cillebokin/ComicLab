package com.example.comiclab

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.comiclab.core.settings.ComicLabAppSettings
import com.example.comiclab.core.settings.ComicLabConfigurationImportSummary
import com.example.comiclab.core.settings.ComicLabConfigurationJson
import com.example.comiclab.core.settings.ComicLabConfigurationStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal data class ConfigurationImportPayload(
    val summary: ComicLabConfigurationImportSummary,
    val appSettings: ComicLabAppSettings
)

internal sealed interface ConfigurationImportCompletion {
    data class Success(
        val summary: ComicLabConfigurationImportSummary,
        val appliedSettings: Int
    ) : ConfigurationImportCompletion

    data class Failure(val message: String) : ConfigurationImportCompletion
}

/**
 * Keeps an import alive across SettingsActivity recreation and retains its result
 * until a visible SettingsActivity can present it.
 */
internal class ConfigurationImportCoordinator(
    private val executor: Executor,
    private val dispatchToMain: (() -> Unit) -> Unit,
    private val importWork: (Context, Uri) -> ConfigurationImportPayload,
    private val applySettings: (Context, ComicLabAppSettings) -> Int
) {
    interface Listener {
        fun onImportRunningChanged(running: Boolean)
        fun onImportCompleted(completion: ConfigurationImportCompletion)
    }

    private val lock = Any()
    private val listeners = linkedSetOf<Listener>()
    private var running = false
    private var pendingCompletion: ConfigurationImportCompletion? = null

    fun addListener(listener: Listener) {
        val state = synchronized(lock) {
            listeners.add(listener)
            val pending = pendingCompletion
            pendingCompletion = null
            running to pending
        }
        listener.onImportRunningChanged(state.first)
        state.second?.let(listener::onImportCompleted)
    }

    fun removeListener(listener: Listener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    fun isRunning(): Boolean = synchronized(lock) { running }

    fun start(context: Context, uri: Uri): Boolean {
        val listenersToNotify = synchronized(lock) {
            if (running) return false
            running = true
            listeners.toList()
        }

        val appContext = context.applicationContext ?: context
        try {
            dispatchToMain {
                listenersToNotify.forEach { it.onImportRunningChanged(true) }
            }
            executor.execute {
                val payload = runCatching { importWork(appContext, uri) }
                dispatchToMain {
                    val completion = payload.fold(
                        onSuccess = { imported ->
                            runCatching {
                                ConfigurationImportCompletion.Success(
                                    summary = imported.summary,
                                    appliedSettings = applySettings(appContext, imported.appSettings)
                                )
                            }.getOrElse(::failure)
                        },
                        onFailure = ::failure
                    )
                    complete(completion)
                }
            }
        } catch (error: Exception) {
            dispatchToMain { complete(failure(error)) }
        }
        return true
    }

    private fun complete(completion: ConfigurationImportCompletion) {
        val listenersToNotify = synchronized(lock) {
            running = false
            if (listeners.isEmpty()) {
                pendingCompletion = completion
                emptyList()
            } else {
                listeners.toList()
            }
        }
        listenersToNotify.forEach { listener ->
            listener.onImportRunningChanged(false)
            listener.onImportCompleted(completion)
        }
    }

    private fun failure(error: Throwable): ConfigurationImportCompletion.Failure {
        return ConfigurationImportCompletion.Failure(
            error.message.orEmpty().ifBlank { error::class.java.simpleName }
        )
    }
}

internal object ProcessConfigurationImportCoordinator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val coordinator = ConfigurationImportCoordinator(
        executor = Executors.newSingleThreadExecutor(),
        dispatchToMain = { action -> mainHandler.post(action) },
        importWork = { context, uri ->
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: error(context.getString(R.string.configuration_open_input_failed))
            val rawJson = inputStream.use { stream ->
                stream.reader(Charsets.UTF_8).use { reader -> reader.readText() }
            }
            val decoded = ComicLabConfigurationJson.decode(rawJson)
            val summary = ComicLabConfigurationStore.importAndMerge(
                context,
                decoded.configuration,
                decoded.skippedEntries,
                applyAppSettings = false
            )
            ConfigurationImportPayload(summary, decoded.configuration.appSettings)
        },
        applySettings = AppSettings::applySnapshot
    )

    fun addListener(listener: ConfigurationImportCoordinator.Listener) =
        coordinator.addListener(listener)

    fun removeListener(listener: ConfigurationImportCoordinator.Listener) =
        coordinator.removeListener(listener)

    fun isRunning(): Boolean = coordinator.isRunning()

    fun start(context: Context, uri: Uri): Boolean = coordinator.start(context, uri)
}
