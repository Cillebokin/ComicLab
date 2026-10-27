package com.example.comiclab.core.reader.translation

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object TranslationModels {
    fun ensure(context: Context, cancelled: AtomicBoolean, progress: (String) -> Unit): File {
        val root = File(context.noBackupFilesDir, "translation-models").apply { check(mkdirs() || isDirectory) }
        val manifest = JSONObject(context.assets.open("translation/models.json").bufferedReader().use { it.readText() }).getJSONArray("models")
        for (i in 0 until manifest.length()) {
            checkCancelled(cancelled)
            val spec = manifest.getJSONObject(i)
            val name = spec.getString("name")
            require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
            val target = File(root, name)
            val size = spec.getLong("size")
            fun valid(file: File) = validFile(file, size, spec.getString("sha256"), cancelled)
            progress("${i + 1}/${manifest.length()} $name")
            if (valid(target)) continue
            val part = File(root, "$name.part")
            if (promoteCompletePart(part, target, size, spec.getString("sha256"), cancelled)) continue
            require(root.usableSpace > size - part.length() + 32L * 1024 * 1024) { "Insufficient model storage" }
            val connection = URL(spec.getString("url")).openConnection() as HttpURLConnection
            TranslationNetwork.use(connection, cancelled, 15 * 60_000L) {
                connection.connectTimeout = 15000; connection.readTimeout = 15000
                if (part.length() > 0) connection.setRequestProperty("Range", "bytes=${part.length()}-")
                val code = connection.responseCode
                if (code == 416) { part.delete(); error("Model download range rejected; retry download") }
                check(code == 200 || code == 206) { "Model download HTTP $code" }
                val append = code == 206 && part.length() > 0
                if (append) require(connection.getHeaderField("Content-Range")?.startsWith("bytes ${part.length()}-") == true)
                connection.inputStream.use { input -> java.io.FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(128 * 1024); var last = -1
                    var received = if (append) part.length() else 0L
                    while (true) {
                        checkCancelled(cancelled)
                        val n = input.read(buffer); if (n < 0) break
                        received += n; check(received <= size) { "Unexpected model size" }
                        output.write(buffer, 0, n)
                        val percent = (received * 100 / size).toInt()
                        if (percent != last) { progress("${i + 1}/${manifest.length()} $name $percent%"); last = percent }
                    }
                } }
            }
            if (!valid(part)) { part.delete(); error("Model integrity check failed") }
            publish(part, target)
        }
        return root
    }
    private fun validFile(file: File, size: Long, hash: String, cancelled: AtomicBoolean): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { checkCancelled(cancelled); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == hash
    }

    internal fun promoteCompletePart(part: File, target: File, size: Long, hash: String, cancelled: AtomicBoolean): Boolean {
        if (!part.exists() || part.length() < size) return false
        if (validFile(part, size, hash, cancelled)) { publish(part, target); return true }
        check(part.delete()) { "Cannot reset damaged model download" }
        return false
    }

    private fun publish(part: File, target: File) {
        java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    fun checkCancelled(cancelled: AtomicBoolean) { if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException() }
}
