package com.example.comiclab.core.store

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.example.comiclab.core.store.PrivacyStore.CredentialState
import com.example.comiclab.core.store.PrivacyStore.VerifyResult
import java.io.File
import java.time.Duration
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivacyStoreCredentialsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val pin = "012345"
    private val pattern = listOf(0, 1, 2, 5)
    private val differentPattern = listOf(0, 3, 6, 7)
    private val now = PrivacyStore.ClockReading(100_000L, 1_700_000_000_000L, 7)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = PrefsGateway.open(context, "privacy_prefs")
        assertTrue(prefs.edit().clear().commit())
    }

    @Test
    fun initializesOnlyOnceWithSeparateSaltedCredentials() {
        assertEquals(CredentialState.UNINITIALIZED, PrivacyStore.credentialState(context))
        assertTrue(PrivacyStore.initializeCredentials(context, pin, pattern))
        assertEquals(CredentialState.READY, PrivacyStore.credentialState(context))
        assertFalse(PrivacyStore.initializeCredentials(context, "999999", differentPattern))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPin(context, pin, now))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPattern(context, pattern, now))

        val pinSalt = prefs.getString("pin_salt", null)!!
        val patternSalt = prefs.getString("pattern_salt", null)!!
        assertNotEquals(pinSalt, patternSalt)
        for (salt in listOf(pinSalt, patternSalt)) {
            assertEquals(16, Base64.getDecoder().decode(salt).size)
        }
        for (key in listOf("pin_hash", "pattern_hash")) {
            assertEquals(32, Base64.getDecoder().decode(prefs.getString(key, null)!!).size)
        }
        assertFalse(prefs.all.values.any { it == pin || it == "0-1-2-5" })
    }

    @Test
    fun invalidatedUiRequestCannotCommitInitializationOrAReplacement() {
        val directory = temporaryFolder.newFolder("private")
        var checks = 0
        assertFalse(PrivacyStore.initializeCredentials(context, pin, pattern, directory) { ++checks == 1 })
        assertEquals(CredentialState.UNINITIALIZED, PrivacyStore.credentialState(context))
        assertTrue(PrivacyStore.privacyDirectories(context).isEmpty())

        initialize()
        checks = 0
        assertEquals(VerifyResult.INVALID_STATE,
            PrivacyStore.updatePinWithOldPin(context, pin, "654321", now) { ++checks == 1 })
        assertEquals(0, PrivacyStore.failedAttempts(context, now))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPin(context, pin, now))
    }

    @Test
    fun rejectsMalformedInputsWithoutCreatingAnAccountOrCountingAnAttempt() {
        assertFalse(PrivacyStore.initializeCredentials(context, "１２３４５６", pattern))
        assertFalse(PrivacyStore.initializeCredentials(context, pin, listOf(0, 1, 2)))
        assertFalse(PrivacyStore.initializeCredentials(context, pin, listOf(0, 1, 2, 2)))
        assertFalse(PrivacyStore.initializeCredentials(context, pin, listOf(0, 1, 2, 9)))
        assertEquals(CredentialState.UNINITIALIZED, PrivacyStore.credentialState(context))
        assertTrue(prefs.all.isEmpty())

        initialize()
        assertEquals(VerifyResult.INVALID_INPUT, PrivacyStore.verifyPin(context, "12345", now))
        assertEquals(VerifyResult.INVALID_INPUT, PrivacyStore.verifyPattern(context, listOf(0, 1, 2), now))
        assertEquals(0, PrivacyStore.failedAttempts(context, now))
    }

    @Test
    fun corruptPatternRequiresOldPinAndRepairPreservesDirectoryProtection() {
        val directory = temporaryFolder.newFolder("private")
        assertTrue(PrivacyStore.initializeCredentials(context, pin, pattern, directory))
        assertEquals(setOf(directory.canonicalPath), PrivacyStore.privacyDirectories(context))
        assertTrue(prefs.edit().putString("pattern_hash", "malformed").commit())

        assertEquals(CredentialState.PIN_ONLY_RECOVERABLE, PrivacyStore.credentialState(context))
        assertEquals(VerifyResult.INVALID_STATE, PrivacyStore.verifyPattern(context, pattern, now))
        assertEquals(
            VerifyResult.WRONG_CREDENTIALS,
            PrivacyStore.resetPatternWithPin(context, "999999", differentPattern, now)
        )
        assertEquals(CredentialState.PIN_ONLY_RECOVERABLE, PrivacyStore.credentialState(context))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.resetPatternWithPin(context, pin, differentPattern, now))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPattern(context, differentPattern, now))
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(context, directory))
    }

    @Test
    fun existingMarksOrInvalidPinPreventUnauthenticatedInitialization() {
        val directory = temporaryFolder.newFolder("private")
        assertTrue(PrivacyStore.addPrivacyDirectory(context, directory))
        assertEquals(CredentialState.CORRUPTED_LOCKED, PrivacyStore.credentialState(context))
        assertFalse(PrivacyStore.initializeCredentials(context, pin, pattern))
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(context, directory))

        assertTrue(prefs.edit().clear().commit())
        initialize()
        assertTrue(prefs.edit().putInt("pin_hash", 12).commit())
        assertEquals(CredentialState.CORRUPTED_LOCKED, PrivacyStore.credentialState(context))
        assertEquals(VerifyResult.INVALID_STATE, PrivacyStore.verifyPin(context, pin, now))
        assertEquals(VerifyResult.INVALID_STATE, PrivacyStore.verifyPattern(context, pattern, now))
    }

    @Test
    fun changesRequireTheirOwnCurrentCredential() {
        initialize()
        assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.updatePinWithOldPin(context, "999999", "654321", now))
        assertEquals(
            VerifyResult.WRONG_CREDENTIALS,
            PrivacyStore.updatePatternWithOldPattern(context, differentPattern, pattern, now)
        )
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.updatePinWithOldPin(context, pin, "654321", now))
        assertEquals(
            VerifyResult.SUCCESS,
            PrivacyStore.updatePatternWithOldPattern(context, pattern, differentPattern, now)
        )
        assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPin(context, pin, now))
        assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPattern(context, pattern, now))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPin(context, "654321", now))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPattern(context, differentPattern, now))
        assertEquals(0, PrivacyStore.failedAttempts(context, now))
    }

    @Test
    fun fiveSharedFailuresBlockEveryCredentialEntryUntilMonotonicDeadline() {
        initializeAndLock()
        val duringCooldown = now.copy(elapsedRealtime = now.elapsedRealtime + 5_000L, wallMillis = 0)

        assertEquals(5, PrivacyStore.failedAttempts(context, duringCooldown))
        assertEquals(25_000L, PrivacyStore.remainingCooldownMs(context, duringCooldown))
        assertEquals(VerifyResult.LOCKED_OUT, PrivacyStore.verifyPin(context, pin, duringCooldown))
        assertEquals(VerifyResult.LOCKED_OUT, PrivacyStore.verifyPattern(context, pattern, duringCooldown))
        assertEquals(
            VerifyResult.LOCKED_OUT,
            PrivacyStore.resetPatternWithPin(context, pin, differentPattern, duringCooldown)
        )
        assertEquals(
            VerifyResult.LOCKED_OUT,
            PrivacyStore.updatePinWithOldPin(context, pin, "654321", duringCooldown)
        )
        val deadline = duringCooldown.copy(elapsedRealtime = now.elapsedRealtime + 30_000L)
        assertEquals(0L, PrivacyStore.remainingCooldownMs(context, deadline))
        assertEquals(0, PrivacyStore.failedAttempts(context, deadline))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPin(context, pin, deadline))
    }

    @Test
    fun rebootRestoresOnlyTheRemainingCooldownAndReanchorsTime() {
        initializeAndLock()
        val rebooted = PrivacyStore.ClockReading(200L, now.wallMillis + 10_000L, now.bootCount + 1)
        assertEquals(20_000L, PrivacyStore.remainingCooldownMs(context, rebooted))

        val tenSecondsLater = rebooted.copy(elapsedRealtime = 10_200L, wallMillis = now.wallMillis - 1_000_000L)
        assertEquals(10_000L, PrivacyStore.remainingCooldownMs(context, tenSecondsLater))
        assertEquals(VerifyResult.LOCKED_OUT, PrivacyStore.verifyPattern(context, pattern, tenSecondsLater))
        val expired = tenSecondsLater.copy(elapsedRealtime = 20_200L)
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPattern(context, pattern, expired))
    }

    @Test
    fun wallClockRollbackAtRebootCannotHoldTheCooldownBeyondThirtySeconds() {
        initializeAndLock()
        val rebooted = PrivacyStore.ClockReading(100L, now.wallMillis - 1_000_000L, now.bootCount + 1)
        assertEquals(30_000L, PrivacyStore.remainingCooldownMs(context, rebooted))
        val expired = rebooted.copy(elapsedRealtime = 30_100L)
        assertEquals(0L, PrivacyStore.remainingCooldownMs(context, expired))
        assertEquals(0, PrivacyStore.failedAttempts(context, expired))
    }

    @Test
    fun storageFailureCannotDistinguishCorrectFromIncorrectCredentials() {
        initialize()
        val unavailable = contextWithPreferences(commit = { false })

        repeat(6) {
            assertEquals(VerifyResult.STORAGE_ERROR, PrivacyStore.verifyPin(unavailable, "999999", now))
        }
        assertEquals(VerifyResult.STORAGE_ERROR, PrivacyStore.verifyPin(unavailable, pin, now))
        assertEquals(VerifyResult.STORAGE_ERROR, PrivacyStore.verifyPattern(unavailable, pattern, now))
        assertEquals(
            VerifyResult.STORAGE_ERROR,
            PrivacyStore.resetPatternWithPin(unavailable, pin, differentPattern, now)
        )
        assertEquals(0, PrivacyStore.failedAttempts(context, now))
    }

    @Test
    fun aFifthCorrectAttemptCanClearTheReservedCount() {
        initialize()
        fourWrongAttempts(now)
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPin(context, pin, now))
        assertEquals(0, PrivacyStore.failedAttempts(context, now))
        assertEquals(0L, PrivacyStore.remainingCooldownMs(context, now))
    }

    @Test
    fun aFailedFifthAttemptRetainsItsReservationIfTheDeadlineCannotBeWritten() {
        initialize()
        fourWrongAttempts(now)

        val fifthFailure = PrivacyStore.verifyPin(contextFailingCommit(number = 2), "999999", now)
        assertEquals(VerifyResult.STORAGE_ERROR, fifthFailure)
        assertEquals(5, prefs.getInt("failed_attempts", 0))

        val nextBoot = PrivacyStore.ClockReading(100L, now.wallMillis + 2_000L, now.bootCount + 1)
        assertEquals(VerifyResult.LOCKED_OUT, PrivacyStore.verifyPin(context, pin, nextBoot))
        assertEquals(30_000L, PrivacyStore.remainingCooldownMs(context, nextBoot))
        val unlocked = nextBoot.copy(elapsedRealtime = nextBoot.elapsedRealtime + 30_000L)
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPin(context, pin, unlocked))
    }

    @Test
    fun defaultClockIsSampledAfterTheWriterStartsValidating() {
        initialize()
        val clock = currentDeviceClock()
        fourWrongAttempts(clock)
        assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPin(context, "999999", clock))

        val queued = contextWithPreferences(beforeOpen = {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        })
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPin(queued, pin))
    }

    @Test
    fun fifthFailureStartsAFullCooldownAfterEarlierWorkFinishes() {
        initialize()
        val clock = currentDeviceClock()
        fourWrongAttempts(clock)
        var firstCommit = true
        val delayed = contextWithPreferences(
            beforeOpen = { ShadowSystemClock.advanceBy(Duration.ofSeconds(10)) },
            commit = { editor ->
                if (firstCommit) {
                    ShadowSystemClock.advanceBy(Duration.ofSeconds(12))
                    firstCommit = false
                }
                editor.commit()
            }
        )

        assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPin(delayed, "999999"))
        assertEquals(30_000L, PrivacyStore.remainingCooldownMs(context))
    }

    @Test
    fun failedCredentialWritesPreserveMarksAndOldCredentials() {
        val directory: File = temporaryFolder.newFolder("private")
        assertFalse(PrivacyStore.initializeCredentials(contextFailingCommit(), pin, pattern, directory))
        assertEquals(CredentialState.UNINITIALIZED, PrivacyStore.credentialState(context))
        assertTrue(PrivacyStore.privacyDirectories(context).isEmpty())

        assertTrue(PrivacyStore.initializeCredentials(context, pin, pattern, directory))
        assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPattern(context, differentPattern, now))
        assertEquals(
            VerifyResult.STORAGE_ERROR,
            PrivacyStore.updatePinWithOldPin(contextFailingCommit(number = 2), pin, "654321", now)
        )
        assertEquals(2, PrivacyStore.failedAttempts(context, now))
        assertTrue(PrivacyStore.isUnderPrivacyDirectory(context, directory))
        assertEquals(VerifyResult.SUCCESS, PrivacyStore.verifyPin(context, pin, now))
        assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPin(context, "654321", now))
    }

    private fun initialize() {
        assertTrue(PrivacyStore.initializeCredentials(context, pin, pattern))
    }

    private fun initializeAndLock() {
        initialize()
        fourWrongAttempts(now)
        assertEquals(
            VerifyResult.WRONG_CREDENTIALS,
            PrivacyStore.updatePatternWithOldPattern(context, differentPattern, pattern, now)
        )
    }

    private fun fourWrongAttempts(clock: PrivacyStore.ClockReading) {
        repeat(2) {
            assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPattern(context, differentPattern, clock))
            assertEquals(VerifyResult.WRONG_CREDENTIALS, PrivacyStore.verifyPin(context, "999999", clock))
        }
    }

    private fun currentDeviceClock(): PrivacyStore.ClockReading {
        assertTrue(Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, now.bootCount))
        return PrivacyStore.ClockReading(SystemClock.elapsedRealtime(), System.currentTimeMillis(), now.bootCount)
    }

    private fun contextFailingCommit(number: Int = 1): Context {
        var commits = 0
        return contextWithPreferences { editor ->
            val written = editor.commit()
            ++commits != number && written
        }
    }

    private fun contextWithPreferences(
        beforeOpen: () -> Unit = {},
        commit: (SharedPreferences.Editor) -> Boolean = { it.commit() }
    ): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            beforeOpen()
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean = commit(editor)
                    }
                }
            }
        }
    }
}
