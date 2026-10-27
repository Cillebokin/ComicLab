package com.example.comiclab.translation

import com.example.comiclab.core.reader.translation.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class TranslationNetworkTest {
    private class Connection(private val body: String?, private val code: Int = 200) : HttpsURLConnection(URL("https://example.invalid/v1/chat/completions")) {
        val entered = CountDownLatch(1); val closed = CountDownLatch(1)
        val request = ByteArrayOutputStream()
        var sends = 0
        override fun connect() {}
        override fun disconnect() { closed.countDown() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = request.also { sends++ }
        override fun getResponseCode(): Int {
            entered.countDown()
            if (body == null) { closed.await(5, TimeUnit.SECONDS); throw IOException("connection closed") }
            return code
        }
        override fun getInputStream(): InputStream = body!!.byteInputStream()
    }

    @Test fun cancelDisconnectsBlockedRequestAndReportsCancellationWithoutRetry() {
        val connection = Connection(null); val cancelled = AtomicBoolean()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<List<String>> {
                TranslationApi.request(TranslationSettings("https://example.invalid/v1", "model", "key"), listOf("原文"), "English", cancelled) { connection }
            }
            assertTrue(connection.entered.await(2, TimeUnit.SECONDS))
            cancelled.set(true)
            val failure = assertThrows(ExecutionException::class.java) { future.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
            assertEquals(1, connection.sends)
        } finally { connection.disconnect(); executor.shutdownNow() }
    }

    @Test fun requestUsesMinimalCompatiblePayloadAndDoesNotRetryHttpFailure() {
        val connection = Connection("ignored", 429)
        assertThrows(IllegalStateException::class.java) {
            TranslationApi.request(TranslationSettings("https://example.invalid/v1", "model", "key"), listOf("原文"), "English", AtomicBoolean()) { connection }
        }
        val request = org.json.JSONObject(connection.request.toString("UTF-8"))
        assertEquals("model", request.getString("model")); assertFalse(request.getBoolean("stream"))
        assertFalse(request.has("temperature")); assertFalse(request.has("response_format")); assertFalse(request.has("max_tokens"))
        assertEquals(1, connection.sends); assertFalse(connection.instanceFollowRedirects)
    }

    @Test fun completeCorruptPartialIsResetInsteadOfRepeatingRange416() {
        val dir = java.nio.file.Files.createTempDirectory("translation-model-test").toFile()
        try {
            val target = java.io.File(dir, "model.bin")
            val part = java.io.File(dir, "model.bin.part").apply { writeText("bad") }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest("yes".toByteArray()).joinToString("") { "%02x".format(it) }
            assertFalse(TranslationModels.promoteCompletePart(part, target, 3, hash, AtomicBoolean()))
            assertFalse(part.exists())
            part.writeText("yes")
            assertTrue(TranslationModels.promoteCompletePart(part, target, 3, hash, AtomicBoolean()))
            assertEquals("yes", target.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun totalDeadlineDisconnectsAStalledConnection() {
        val connection = Connection(null)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Int> {
                TranslationNetwork.use(connection, AtomicBoolean(), timeoutMillis = 100) { connection.responseCode }
            }
            val failure = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is java.net.SocketTimeoutException)
            assertEquals(0, connection.closed.count)
        } finally { connection.disconnect(); executor.shutdownNow() }
    }

    @Test fun standardChatCompletionPreservesCustomProviderPrefix() {
        val response = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject()
            .put("finish_reason", "stop").put("message", org.json.JSONObject().put("content", "[{\"id\":1,\"text\":\"你好\"}]"))))
        val connection = Connection(response.toString())
        val result = TranslationApi.request(TranslationSettings("https://example.invalid/custom/v1/", "model", "key"),
            listOf("こんにちは"), "Simplified Chinese", AtomicBoolean()) {
            assertEquals("https://example.invalid/custom/v1/chat/completions", it.toString()); connection
        }
        assertEquals(listOf("你好"), result)
    }
}
