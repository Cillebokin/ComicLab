package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.abs

internal object PrivacyStore {
    private const val PREFS_NAME = "privacy_prefs"
    private const val KEY_PRIVACY_DIRECTORIES = "privacy_directories"
    private const val KEY_CREDENTIAL_VERSION = "credential_version"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_ELAPSED_DEADLINE = "cooldown_elapsed_deadline"
    private const val KEY_WALL_DEADLINE = "cooldown_wall_deadline"
    private const val KEY_BOOT_COUNT = "cooldown_boot_count"
    private const val CREDENTIAL_VERSION = 1
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private const val HASH_ITERATIONS = 600_000
    private const val MAX_ATTEMPTS = 5
    private const val COOLDOWN_MS = 30_000L

    private val random = SecureRandom()

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ComicLabPrivacyStore").apply { isDaemon = true }
    }

    enum class CredentialState { UNINITIALIZED, READY, PIN_ONLY_RECOVERABLE, CORRUPTED_LOCKED }

    enum class VerifyResult {
        SUCCESS,
        WRONG_CREDENTIALS,
        LOCKED_OUT,
        INVALID_STATE,
        INVALID_INPUT,
        STORAGE_ERROR
    }

    data class ClockReading(val elapsedRealtime: Long, val wallMillis: Long, val bootCount: Int)

    fun credentialState(context: Context): CredentialState = synchronized(lock) {
        readCredentials(PrefsGateway.open(context, PREFS_NAME)).state
    }

    fun initializeCredentials(
        context: Context,
        pin: String,
        patternNodes: List<Int>,
        initialDirectory: File? = null
    ): Boolean {
        if (!validPin(pin)) return false
        val pattern = patternValue(patternNodes) ?: return false
        val initialPath = if (initialDirectory == null) null else markablePath(initialDirectory) ?: return false

        return writeToStore {
            val preferences = PrefsGateway.open(context, PREFS_NAME)
            if (readCredentials(preferences).state != CredentialState.UNINITIALIZED) {
                return@writeToStore false
            }
            val values = credentialValues(createCredential(pin), createCredential(pattern)).toMutableMap()
            if (initialPath != null) values[KEY_PRIVACY_DIRECTORIES] = setOf(initialPath)
            commitChanges(preferences, values + Cooldown().values())
        }
    }

    fun verifyPin(context: Context, pin: String, clock: ClockReading? = null): VerifyResult =
        authenticate(context, Kind.PIN, pin.takeIf(::validPin), clock)

    fun verifyPattern(
        context: Context,
        patternNodes: List<Int>,
        clock: ClockReading? = null
    ): VerifyResult = authenticate(context, Kind.PATTERN, patternValue(patternNodes), clock)

    fun updatePinWithOldPin(
        context: Context,
        oldPin: String,
        newPin: String,
        clock: ClockReading? = null
    ): VerifyResult {
        if (!validPin(newPin)) return VerifyResult.INVALID_INPUT
        return authenticate(context, Kind.PIN, oldPin.takeIf(::validPin), clock, Replacement(Kind.PIN, newPin))
    }

    fun updatePatternWithOldPattern(
        context: Context,
        oldPattern: List<Int>,
        newPattern: List<Int>,
        clock: ClockReading? = null
    ): VerifyResult {
        val replacement = patternValue(newPattern) ?: return VerifyResult.INVALID_INPUT
        return authenticate(
            context, Kind.PATTERN, patternValue(oldPattern), clock, Replacement(Kind.PATTERN, replacement)
        )
    }

    fun resetPatternWithPin(
        context: Context,
        pin: String,
        newPattern: List<Int>,
        clock: ClockReading? = null
    ): VerifyResult {
        val replacement = patternValue(newPattern) ?: return VerifyResult.INVALID_INPUT
        return authenticate(context, Kind.PIN, pin.takeIf(::validPin), clock, Replacement(Kind.PATTERN, replacement))
    }

    fun failedAttempts(context: Context, clock: ClockReading? = null): Int =
        writeToStore {
            val preferences = PrefsGateway.open(context, PREFS_NAME)
            currentCooldown(preferences, clock ?: readClock(context))?.attempts ?: MAX_ATTEMPTS
        }

    fun remainingCooldownMs(context: Context, clock: ClockReading? = null): Long =
        writeToStore {
            val preferences = PrefsGateway.open(context, PREFS_NAME)
            val now = clock ?: readClock(context)
            currentCooldown(preferences, now)?.remaining(now) ?: COOLDOWN_MS
        }

    fun resolveCanonicalPath(path: String): String? = resolveCanonicalPath(File(path))

    fun resolveCanonicalPath(file: File): String? {
        if (!file.isAbsolute || '\u0000' in file.path) return null
        return try {
            file.canonicalPath
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun addPrivacyDirectory(context: Context, directory: File): Boolean {
        val path = markablePath(directory) ?: return false
        return updatePaths(context) { it + path } != null
    }

    private fun markablePath(directory: File): String? {
        val path = resolveCanonicalPath(directory) ?: return null
        val canonicalDirectory = File(path)
        return path.takeIf { canonicalDirectory.isDirectory && canonicalDirectory.parentFile != null }
    }

    fun removePrivacyDirectory(context: Context, directory: File): Boolean {
        val path = resolveCanonicalPath(directory) ?: return false
        return updatePaths(context) { it - path } != null
    }

    fun removeDirectorySubtree(context: Context, directory: File): Int {
        val path = resolveCanonicalPath(directory) ?: return 0
        return updatePaths(context) { BookcaseFolderPaths.underDirectory(it, path) } ?: 0
    }

    fun privacyDirectories(context: Context): Set<String> = synchronized(lock) {
        readPaths(PrefsGateway.open(context, PREFS_NAME)).toSet()
    }

    fun visibleRootDirectories(context: Context): List<File> {
        val paths = privacyDirectories(context)
        return paths.asSequence()
            .filter { path -> paths.none { it != path && BookcaseFolderPaths.isPathInDirectory(path, it) } }
            .sorted()
            .map(::File)
            .filter(File::isDirectory)
            .toList()
    }

    fun isPrivacyDirectoryMarked(context: Context, path: String): Boolean {
        val canonicalPath = resolveCanonicalPath(path) ?: return false
        return canonicalPath in privacyDirectories(context)
    }

    fun isUnderPrivacyDirectory(context: Context, path: String): Boolean =
        isUnderPrivacyDirectory(context, File(path))

    fun isUnderPrivacyDirectory(context: Context, file: File): Boolean {
        val path = resolveCanonicalPath(file) ?: return true
        return privacyDirectories(context).any { BookcaseFolderPaths.isPathInDirectory(path, it) }
    }

    // 两个子树查询用于拦截文件操作，路径无法确认时也需要拦截。
    fun containsPrivacyDescendant(context: Context, directoryPath: String): Boolean {
        val path = resolveCanonicalPath(directoryPath) ?: return true
        return privacyDirectories(context).any { it != path && BookcaseFolderPaths.isPathInDirectory(it, path) }
    }

    fun hasOwnOrDescendantPrivacyMark(context: Context, directoryPath: String): Boolean {
        val path = resolveCanonicalPath(directoryPath) ?: return true
        return privacyDirectories(context).any { BookcaseFolderPaths.isPathInDirectory(it, path) }
    }

    private fun updatePaths(context: Context, change: (Set<String>) -> Set<String>): Int? =
        writeToStore {
            val preferences = PrefsGateway.open(context, PREFS_NAME)
            val previous = readPaths(preferences)
            val next = change(previous)
            if (previous == next) return@writeToStore 0
            if (!commitChanges(preferences, mapOf(KEY_PRIVACY_DIRECTORIES to next))) {
                return@writeToStore null
            }
            abs(next.size - previous.size)
        }

    private fun <T> writeToStore(action: () -> T): T {
        val write = writer.submit<T> { action() }

        // 调用方取消等待时仍需拿到落盘结果，避免把一次成功写入误报为失败。
        var interrupted = false
        try {
            while (true) {
                try {
                    return write.get()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun commitChanges(preferences: SharedPreferences, changes: Map<String, Any?>): Boolean =
        synchronized(lock) {
            val previous = preferences.all
            val changed = changes.filter { (key, value) -> previous[key] != value }
            if (changed.isEmpty() || preferences.edit().putValues(changed).commit()) return@synchronized true

            // commit() 可能已更新内存，返回前同步尝试恢复本次修改。
            preferences.edit().putValues(changed.keys.associateWith { previous[it] }).commit()
            false
        }

    private fun SharedPreferences.Editor.putValues(values: Map<String, Any?>): SharedPreferences.Editor {
        for ((key, value) in values) {
            when (value) {
                null -> remove(key)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                else -> error("Unsupported privacy preference value")
            }
        }
        return this
    }

    private enum class Kind(val saltKey: String, val hashKey: String) {
        PIN("pin_salt", "pin_hash"),
        PATTERN("pattern_salt", "pattern_hash")
    }

    private data class Credential(val salt: ByteArray, val hash: ByteArray)

    private data class Credentials(
        val state: CredentialState,
        val pin: Credential? = null,
        val pattern: Credential? = null
    )

    private data class Replacement(val kind: Kind, val input: String)

    private fun readCredentials(preferences: SharedPreferences): Credentials {
        val values = preferences.all
        if (values[KEY_CREDENTIAL_VERSION] != CREDENTIAL_VERSION) {
            val marks = values[KEY_PRIVACY_DIRECTORIES]
            val noMarks = marks == null || (marks is Set<*> && marks.isEmpty())
            val noCredentials = KEY_CREDENTIAL_VERSION !in values &&
                Kind.entries.none { it.saltKey in values || it.hashKey in values }
            val state = if (noMarks && noCredentials) {
                CredentialState.UNINITIALIZED
            } else {
                CredentialState.CORRUPTED_LOCKED
            }
            return Credentials(state)
        }

        val pin = decodeCredential(values, Kind.PIN) ?: return Credentials(CredentialState.CORRUPTED_LOCKED)
        val pattern = decodeCredential(values, Kind.PATTERN)
        val state = if (pattern == null) CredentialState.PIN_ONLY_RECOVERABLE else CredentialState.READY
        return Credentials(state, pin, pattern)
    }

    private fun decodeCredential(values: Map<String, *>, kind: Kind): Credential? {
        val salt = decodePart(values[kind.saltKey], SALT_BYTES) ?: return null
        val hash = decodePart(values[kind.hashKey], HASH_BYTES) ?: return null
        return Credential(salt, hash)
    }

    private fun decodePart(value: Any?, size: Int): ByteArray? {
        if (value !is String || value.length != ((size + 2) / 3) * 4) return null
        return try {
            Base64.getDecoder().decode(value).takeIf { it.size == size }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun credentialValues(pin: Credential, pattern: Credential): Map<String, Any> {
        val encoder = Base64.getEncoder()
        return mapOf(
            KEY_CREDENTIAL_VERSION to CREDENTIAL_VERSION,
            Kind.PIN.saltKey to encoder.encodeToString(pin.salt),
            Kind.PIN.hashKey to encoder.encodeToString(pin.hash),
            Kind.PATTERN.saltKey to encoder.encodeToString(pattern.salt),
            Kind.PATTERN.hashKey to encoder.encodeToString(pattern.hash)
        )
    }

    private fun createCredential(input: String): Credential {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return Credential(salt, deriveHash(input, salt))
    }

    private fun deriveHash(input: String, salt: ByteArray): ByteArray {
        val password = input.toCharArray()
        val spec = PBEKeySpec(password, salt, HASH_ITERATIONS, HASH_BYTES * Byte.SIZE_BITS)
        password.fill('\u0000')
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun validPin(pin: String): Boolean = pin.length == 6 && pin.all { it in '0'..'9' }

    private fun patternValue(nodes: List<Int>): String? =
        if (nodes.size in 4..9 && nodes.all { it in 0..8 } && nodes.distinct().size == nodes.size) {
            nodes.joinToString("-")
        } else {
            null
        }

    private fun authenticate(
        context: Context,
        kind: Kind,
        input: String?,
        clock: ClockReading?,
        replacement: Replacement? = null
    ): VerifyResult = writeToStore {
        val preferences = PrefsGateway.open(context, PREFS_NAME)
        val credentials = readCredentials(preferences)
        val pin = credentials.pin ?: return@writeToStore VerifyResult.INVALID_STATE
        val pattern = credentials.pattern
        if (pattern == null && (kind == Kind.PATTERN || replacement?.kind == Kind.PIN)) {
            return@writeToStore VerifyResult.INVALID_STATE
        }

        val now = clock ?: readClock(context)
        val cooldown = currentCooldown(preferences, now) ?: return@writeToStore VerifyResult.STORAGE_ERROR
        if (cooldown.remaining(now) > 0) return@writeToStore VerifyResult.LOCKED_OUT
        if (input == null) return@writeToStore VerifyResult.INVALID_INPUT

        // 先持久化本次尝试。写不进去时直接报存储错误，避免依据结果区分凭据是否正确。
        val reserved = Cooldown(cooldown.attempts + 1)
        if (!commitChanges(preferences, reserved.values())) return@writeToStore VerifyResult.STORAGE_ERROR

        val credential = if (kind == Kind.PIN) pin else checkNotNull(pattern)
        if (!MessageDigest.isEqual(credential.hash, deriveHash(input, credential.salt))) {
            if (reserved.attempts == MAX_ATTEMPTS) {
                val failedAt = clock ?: readClock(context)
                val locked = Cooldown.locked(failedAt, COOLDOWN_MS)
                if (!commitChanges(preferences, locked.values())) return@writeToStore VerifyResult.STORAGE_ERROR
            }
            return@writeToStore VerifyResult.WRONG_CREDENTIALS
        }

        val changed = when (replacement?.kind) {
            Kind.PIN -> credentialValues(createCredential(replacement.input), checkNotNull(pattern))
            Kind.PATTERN -> credentialValues(pin, createCredential(replacement.input))
            null -> emptyMap()
        }
        if (commitChanges(preferences, changed + Cooldown().values())) VerifyResult.SUCCESS else VerifyResult.STORAGE_ERROR
    }

    private fun readClock(context: Context): ClockReading {
        val bootCount = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: SecurityException) {
            -1
        }
        return ClockReading(SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount)
    }

    private data class Cooldown(
        val attempts: Int = 0,
        val elapsedDeadline: Long = 0,
        val wallDeadline: Long = 0,
        val bootCount: Int = -1
    ) {
        fun normalized(clock: ClockReading): Cooldown {
            // 进程中断或截止时间写入失败时，第 5 次尝试的计数已经落盘，恢复时补齐冷却。
            if (elapsedDeadline <= 0) {
                return if (attempts >= MAX_ATTEMPTS) locked(clock, COOLDOWN_MS) else this
            }
            val sameBoot = bootCount == clock.bootCount &&
                (bootCount >= 0 || clock.elapsedRealtime >= elapsedDeadline - COOLDOWN_MS)
            if (sameBoot) return if (remaining(clock) > 0) this else Cooldown()

            // 换开机周期后只用墙钟恢复一次，再按新开机周期的单调时钟倒计时。
            val timeLeft = wallDeadline - clock.wallMillis.coerceIn(wallDeadline - COOLDOWN_MS, wallDeadline)
            return if (timeLeft > 0) locked(clock, timeLeft) else Cooldown()
        }

        fun remaining(clock: ClockReading): Long =
            (elapsedDeadline - clock.elapsedRealtime).coerceIn(0L, COOLDOWN_MS)

        fun values(): Map<String, Any?> = mapOf(
            KEY_FAILED_ATTEMPTS to attempts.takeIf { it != 0 },
            KEY_ELAPSED_DEADLINE to elapsedDeadline.takeIf { it != 0L },
            KEY_WALL_DEADLINE to wallDeadline.takeIf { it != 0L },
            KEY_BOOT_COUNT to bootCount.takeIf { elapsedDeadline != 0L }
        )

        companion object {
            fun locked(clock: ClockReading, remaining: Long): Cooldown = Cooldown(
                MAX_ATTEMPTS,
                clock.elapsedRealtime + remaining,
                clock.wallMillis + remaining,
                clock.bootCount
            )
        }
    }

    private fun currentCooldown(preferences: SharedPreferences, clock: ClockReading): Cooldown? {
        val values = preferences.all
        val stored = Cooldown(
            attempts = (values[KEY_FAILED_ATTEMPTS] as? Int ?: 0).coerceIn(0, MAX_ATTEMPTS),
            elapsedDeadline = values[KEY_ELAPSED_DEADLINE] as? Long ?: 0,
            wallDeadline = values[KEY_WALL_DEADLINE] as? Long ?: 0,
            bootCount = values[KEY_BOOT_COUNT] as? Int ?: -1
        )
        val current = stored.normalized(clock)
        return current.takeIf { it == stored || commitChanges(preferences, it.values()) }
    }

    private fun readPaths(preferences: SharedPreferences): Set<String> =
        preferences.getStringSet(KEY_PRIVACY_DIRECTORIES, null).orEmpty()
}
