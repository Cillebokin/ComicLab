package com.example.comiclab.core.reader.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.comiclab.R
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal data class TranslationSettings(
    val address: String,
    val model: String,
    val key: String,
    val targetLanguageIndex: Int = 0
)

/** 配置与密文均放入noBackupFilesDir，不进入配置导出、系统备份或设备迁移。 */
internal object TranslationSettingsStore {
    private const val ALIAS = "comiclab.translation.key.v1"
    private fun file(context: Context) = File(context.noBackupFilesDir, "translation-settings.json")
    private fun enabledFile(context: Context) = File(context.noBackupFilesDir, "translation-enabled")
    private fun targetLanguageFile(context: Context) = File(context.noBackupFilesDir, "translation-target-language")

    fun isAutomaticTranslationEnabled(context: Context): Boolean =
        runCatching { enabledFile(context).readText() == "enabled" }.getOrDefault(false)

    @Synchronized
    fun setAutomaticTranslationEnabled(context: Context, enabled: Boolean) {
        val target = enabledFile(context)
        val atomic = android.util.AtomicFile(target)
        if (!enabled) {
            atomic.delete()
            return
        }
        val output = atomic.startWrite()
        try {
            output.write("enabled".toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun targetLanguageIndex(context: Context, fallback: Int): Int =
        runCatching { targetLanguageFile(context).readText().toInt().coerceIn(0, 4) }
            .getOrDefault(fallback.coerceIn(0, 4))

    @Synchronized
    fun saveTargetLanguageIndex(context: Context, index: Int) {
        require(index in 0..4)
        val atomic = android.util.AtomicFile(targetLanguageFile(context))
        val output = atomic.startWrite()
        try {
            output.write(index.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    @Synchronized
    fun load(context: Context): TranslationSettings {
        val saved = loadStored(context)
        return saved.copy(
            address = saved.address.ifBlank { context.getString(R.string.translation_default_address) },
            model = saved.model.ifBlank { context.getString(R.string.translation_default_model) }
        )
    }

    @Synchronized
    fun loadForEditor(context: Context): TranslationSettings = loadStored(context)

    private fun loadStored(context: Context): TranslationSettings {
        val file = file(context)
        if (!file.isFile) return TranslationSettings("", "", "", targetLanguageIndex(context, 0))
        val json = JSONObject(file.readText())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)))
        val key = String(cipher.doFinal(Base64.decode(json.getString("key"), Base64.NO_WRAP)), Charsets.UTF_8)
        return TranslationSettings(
            json.getString("address"), json.getString("model"), key,
            targetLanguageIndex(context, json.optInt("targetLanguageIndex", 0))
        )
    }
    @Synchronized fun save(context: Context, settings: TranslationSettings) {
        if (settings.address.isNotBlank()) TranslationPolicy.endpoint(settings.address)
        require(settings.model.length <= 200 && settings.key.isNotBlank() && !settings.key.contains('\n'))
        require(settings.targetLanguageIndex in 0..4)
        saveTargetLanguageIndex(context, settings.targetLanguageIndex)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val encrypted = cipher.doFinal(settings.key.toByteArray(Charsets.UTF_8))
        val json = JSONObject().put("address", settings.address.trim()).put("model", settings.model.trim())
            .put("targetLanguageIndex", settings.targetLanguageIndex)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("key", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        val target = file(context)
        val atomic = android.util.AtomicFile(target)
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
