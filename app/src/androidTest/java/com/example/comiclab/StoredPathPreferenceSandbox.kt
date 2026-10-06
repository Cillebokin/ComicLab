package com.example.comiclab

import android.content.Context
import android.content.SharedPreferences

/** Keeps MainActivity instrumentation from inspecting or changing saved user file paths. */
internal class StoredPathPreferenceSandbox(context: Context) {

    private val entries = listOf(
        StringPreferenceSnapshot(
            context.getSharedPreferences("reading_history_prefs", Context.MODE_PRIVATE),
            "reading_history"
        ),
        StringPreferenceSnapshot(
            context.getSharedPreferences("favorite_comic_prefs", Context.MODE_PRIVATE),
            "favorite_comics"
        ),
        StringPreferenceSnapshot(
            context.getSharedPreferences("favorite_path_prefs", Context.MODE_PRIVATE),
            "favorite_paths"
        )
    )

    init {
        try {
            entries.forEach(StringPreferenceSnapshot::clear)
        } catch (failure: Throwable) {
            runCatching(::restore).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun restore() {
        var firstFailure: Throwable? = null
        entries.forEach { snapshot ->
            try {
                snapshot.restore()
            } catch (failure: Throwable) {
                val previousFailure = firstFailure
                if (previousFailure == null) {
                    firstFailure = failure
                } else {
                    previousFailure.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { throw it }
    }

    private class StringPreferenceSnapshot(
        private val preferences: SharedPreferences,
        private val key: String
    ) {
        private val wasPresent = preferences.contains(key)
        private val originalValue = preferences.getString(key, null)

        fun clear() {
            check(preferences.edit().remove(key).commit()) {
                "Could not isolate stored paths for instrumentation"
            }
        }

        fun restore() {
            val editor = preferences.edit()
            if (wasPresent) {
                editor.putString(
                    key,
                    checkNotNull(originalValue) { "Stored path preference was unexpectedly null" }
                )
            } else {
                editor.remove(key)
            }
            check(editor.commit()) { "Could not restore stored paths after instrumentation" }
        }
    }
}
