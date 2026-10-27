package com.example.comiclab.core.reader.translation

import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Disconnect also interrupts a blocked connect/read/write; readTimeout alone cannot cancel a request. */
internal object TranslationNetwork {
    fun <T> use(connection: HttpURLConnection, cancelled: AtomicBoolean, timeoutMillis: Long = 120_000, block: () -> T): T {
        TranslationModels.checkCancelled(cancelled)
        val done = AtomicBoolean()
        val expired = AtomicBoolean()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        val watcher = Thread({
            try {
                while (!done.get()) {
                    if (cancelled.get() || System.nanoTime() >= deadline) {
                        if (!cancelled.get()) expired.set(true)
                        connection.disconnect()
                        break
                    }
                    Thread.sleep(25)
                }
            } catch (_: InterruptedException) { }
        }, "translation-http-cancel").apply { isDaemon = true; start() }
        try {
            val result = block()
            TranslationModels.checkCancelled(cancelled)
            if (expired.get()) throw SocketTimeoutException("Translation request timed out")
            return result
        } catch (error: Exception) {
            if (cancelled.get()) throw CancellationException()
            if (expired.get()) throw SocketTimeoutException("Translation request timed out")
            throw error
        } finally {
            done.set(true); watcher.interrupt(); connection.disconnect()
        }
    }
}
