package com.example.comiclab.core.nas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.comiclab.core.store.PrefsGateway
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal data class NasShareProfile(
    val id: String,
    val name: String,
    val host: String,
    val shareName: String,
    val username: String,
    val rememberPassword: Boolean,
    internal val encryptedPassword: String? = null
)

internal object NasShareStore {
    private const val PREFERENCES_NAME = "nas_share_settings"
    private const val KEY_SHARES = "shares_json"
    private const val KEY_NAS_ENABLED = "nas_enabled"
    private const val KEY_ACTIVE_SHARE_ID = "active_share_id"
    private const val KEY_DOWNLOAD_PATH = "download_path"
    const val DEFAULT_DOWNLOAD_PATH = "Download/ComicLab/NAS"
    private val lock = Any()

    fun isNasEnabled(context: Context): Boolean = synchronized(lock) {
        PrefsGateway.open(context, PREFERENCES_NAME)
            .getBoolean(KEY_NAS_ENABLED, false)
    }

    fun setNasEnabled(context: Context, enabled: Boolean) = synchronized(lock) {
        PrefsGateway.open(context, PREFERENCES_NAME)
            .edit()
            .putBoolean(KEY_NAS_ENABLED, enabled)
            .apply()
    }

    fun downloadPath(context: Context): String = synchronized(lock) {
        PrefsGateway.open(context, PREFERENCES_NAME)
            .getString(KEY_DOWNLOAD_PATH, null)
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_DOWNLOAD_PATH
    }

    fun setDownloadPath(context: Context, path: String) = synchronized(lock) {
        require(path.isNotBlank()) { "NAS download path cannot be empty" }
        val saved = PrefsGateway.open(context, PREFERENCES_NAME)
            .edit()
            .putString(KEY_DOWNLOAD_PATH, path)
            .commit()
        check(saved) { "NAS download path could not be saved" }
    }

    fun activeShareId(context: Context): String? = synchronized(lock) {
        PrefsGateway.open(context, PREFERENCES_NAME)
            .getString(KEY_ACTIVE_SHARE_ID, null)
    }

    fun setActiveShare(context: Context, id: String) = synchronized(lock) {
        require(load(context).any { it.id == id }) { "NAS share does not exist" }
        val saved = PrefsGateway.open(context, PREFERENCES_NAME)
            .edit()
            .putString(KEY_ACTIVE_SHARE_ID, id)
            .commit()
        check(saved) { "Active NAS share could not be saved" }
    }

    fun load(context: Context): List<NasShareProfile> = synchronized(lock) {
        val preferences = PrefsGateway.open(context, PREFERENCES_NAME)
        if (!preferences.contains(KEY_SHARES)) {
            return@synchronized emptyList()
        }

        val raw = preferences.getString(KEY_SHARES, null)
            ?: throw IllegalStateException("NAS share settings are unreadable")
        try {
            val json = JSONArray(raw)
            List(json.length()) { index ->
                val item = json.getJSONObject(index)
                NasShareProfile(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    host = item.getString("host"),
                    shareName = item.getString("shareName"),
                    username = item.optString("username"),
                    rememberPassword = item.optBoolean("rememberPassword", false),
                    encryptedPassword = item.optString("encryptedPassword")
                        .takeIf(String::isNotEmpty)
                )
            }
        } catch (exception: Exception) {
            throw IllegalStateException("NAS share settings are malformed", exception)
        }
    }

    fun save(
        context: Context,
        profile: NasShareProfile,
        password: String,
        rememberPassword: Boolean
    ) = synchronized(lock) {
        val current = load(context)
        val previous = current.firstOrNull { it.id == profile.id }
        val encryptedPassword = when {
            !rememberPassword || profile.username.isBlank() -> null
            password.isNotEmpty() -> NasPasswordCipher.encrypt(password)
            else -> previous?.encryptedPassword
        }
        val updated = profile.copy(
            rememberPassword = rememberPassword,
            encryptedPassword = encryptedPassword
        )
        val next = current.filterNot { it.id == profile.id } + updated
        persist(context, next)
    }

    fun delete(context: Context, id: String) = synchronized(lock) {
        val wasActive = activeShareId(context) == id
        persist(
            context,
            load(context).filterNot { it.id == id },
            clearActiveShare = wasActive
        )
    }

    fun rememberedPassword(profile: NasShareProfile): String? {
        if (!profile.rememberPassword) return null
        val encrypted = profile.encryptedPassword ?: return null
        return NasPasswordCipher.decrypt(encrypted)
    }

    private fun persist(
        context: Context,
        profiles: List<NasShareProfile>,
        clearActiveShare: Boolean = false
    ) {
        val json = JSONArray()
        profiles.forEach { profile ->
            json.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("host", profile.host)
                put("shareName", profile.shareName)
                put("username", profile.username)
                put("rememberPassword", profile.rememberPassword)
                profile.encryptedPassword?.let { put("encryptedPassword", it) }
            })
        }
        val editor = PrefsGateway.open(context, PREFERENCES_NAME)
            .edit()
            .putString(KEY_SHARES, json.toString())
        if (clearActiveShare) {
            editor.remove(KEY_ACTIVE_SHARE_ID)
        }
        val saved = editor.commit()
        check(saved) { "NAS share settings could not be saved" }
    }
}

private object NasPasswordCipher {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "comiclab_nas_share_password"
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    fun encrypt(password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(password.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + ciphertext
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH_BYTES) { "Stored NAS password is invalid" }
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
