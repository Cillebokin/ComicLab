package com.example.comiclab.ui.privacy

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.comiclab.R
import com.example.comiclab.core.store.PrivacyStore
import com.example.comiclab.core.store.PrivacyStore.CredentialState
import com.example.comiclab.core.store.PrivacyStore.VerifyResult
import com.example.comiclab.ui.common.applyRoundedSurface
import com.example.comiclab.ui.common.createRounded
import java.io.File
import java.util.concurrent.Executors

internal class PrivacyAuthDialogController(
    private val activity: AppCompatActivity,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    private class Request(val epoch: Long)

    private inner class CredentialDialog : AlertDialog(activity) {
        override fun cancel() {
            if (dialog === this) dismissAll()
            super.cancel()
        }
    }

    private class Prompt(
        val dialog: AlertDialog,
        val pin: EditText,
        val pattern: PatternLockView,
        val status: TextView
    ) {
        fun enableInput(enabled: Boolean) {
            pin.isEnabled = enabled
            pattern.isEnabled = enabled
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = enabled
        }

        fun message(text: String?) {
            status.text = text
            status.visibility = if (text == null) View.GONE else View.VISIBLE
        }
    }

    private val context = activity.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val lockListener = PrivacySessionManager.Listener(::dismissAll)
    @Volatile private var request: Request? = null
    private var dialog: AlertDialog? = null
    private var countdown: Runnable? = null
    val isShowing: Boolean get() = request != null

    fun startInitializationFlow(
        initialDirectory: File? = null,
        onCompleted: () -> Unit
    ) {
        if (PrivacyStore.credentialState(context) != CredentialState.UNINITIALIZED) return
        start { active ->
            collectNewPin(active) { _, pin ->
                collectNewPattern(active) { prompt, pattern ->
                    runVerification(active, prompt, {
                        val saved = PrivacyStore.initializeCredentials(context, pin, pattern, initialDirectory) {
                            isCurrent(active) && (initialDirectory == null || PrivacyAccessGuard.canMarkDirectory(initialDirectory))
                        }
                        if (saved) VerifyResult.SUCCESS else VerifyResult.STORAGE_ERROR
                    }) { finish(active, onCompleted) }
                }
            }
        }
    }

    fun showUnlockPrompt(onUnlocked: (Long) -> Unit) {
        val state = PrivacyStore.credentialState(context)
        if (state == CredentialState.UNINITIALIZED) return
        start { active ->
            val unlocked = { finish(active) { onUnlocked(active.epoch) } }
            when (state) {
                CredentialState.READY -> verifyOldPattern(active, { recoverPattern(active, unlocked) }) { unlocked() }
                CredentialState.PIN_ONLY_RECOVERABLE -> recoverPattern(active, unlocked)
                CredentialState.CORRUPTED_LOCKED -> showBlocked(active)
                CredentialState.UNINITIALIZED -> dismissAll()
            }
        }
    }

    fun showChangePinFlow(onChanged: () -> Unit) {
        if (PrivacyStore.credentialState(context) != CredentialState.READY) return
        start { active ->
            verifyOldPin(active) { oldPin ->
                collectNewPin(active) { prompt, newPin ->
                    runVerification(active, prompt, {
                        PrivacyStore.updatePinWithOldPin(context, oldPin, newPin, canCommit = { isCurrent(active) })
                    }) { finish(active, onChanged) }
                }
            }
        }
    }

    fun showChangePatternFlow(onChanged: () -> Unit) {
        if (PrivacyStore.credentialState(context) != CredentialState.READY) return
        start { active ->
            val done = { finish(active, onChanged) }
            verifyOldPattern(active, { recoverPattern(active, done) }) { old ->
                collectNewPattern(active) { prompt, new ->
                    runVerification(active, prompt, {
                        PrivacyStore.updatePatternWithOldPattern(context, old, new, canCommit = { isCurrent(active) })
                    }, done)
                }
            }
        }
    }

    fun showPinRecoveryPatternFlow(onRecovered: () -> Unit) {
        if (PrivacyStore.credentialState(context) != CredentialState.PIN_ONLY_RECOVERABLE) return
        start { active -> recoverPattern(active) { finish(active, onRecovered) } }
    }

    fun showCorruptedNotice() {
        start(::showBlocked)
    }

    private fun showBlocked(active: Request) {
        show(active, AlertDialog.Builder(activity)
            .setTitle(R.string.settings_section_privacy)
            .setMessage(R.string.privacy_credentials_corrupted)
            .setPositiveButton(android.R.string.ok, null)
            .createRounded())
    }

    private fun recoverPattern(active: Request, onRecovered: () -> Unit) {
        verifyOldPin(active) { pin ->
            collectNewPattern(active) { prompt, pattern ->
                runVerification(active, prompt, {
                    PrivacyStore.resetPatternWithPin(context, pin, pattern, canCommit = { isCurrent(active) })
                }, onRecovered)
            }
        }
    }

    private fun collectNewPin(active: Request, onConfirmed: (Prompt, String) -> Unit) {
        pinPrompt(active, R.string.privacy_new_pin) { _, pin ->
            pinPrompt(active, R.string.privacy_confirm_pin, confirmValue = pin, onSubmitted = onConfirmed)
        }
    }

    private fun collectNewPattern(active: Request, onConfirmed: (Prompt, List<Int>) -> Unit) {
        patternPrompt(active, R.string.privacy_new_pattern) { _, first ->
            patternPrompt(active, R.string.privacy_confirm_pattern) { prompt, second ->
                if (first == second) onConfirmed(prompt, second)
                else {
                    prompt.pattern.showErrorState()
                    prompt.message(activity.getString(R.string.privacy_pattern_mismatch))
                }
            }
        }
    }

    private fun verifyOldPin(active: Request, onVerified: (String) -> Unit) {
        val prompt = pinPrompt(active, R.string.privacy_verify_pin) { panel, pin ->
            runVerification(active, panel, { PrivacyStore.verifyPin(context, pin) }) { onVerified(pin) }
        }
        checkCooldown(active, prompt)
    }

    private fun verifyOldPattern(active: Request, onForgot: () -> Unit, onVerified: (List<Int>) -> Unit) {
        val prompt = patternPrompt(active, R.string.privacy_verify_pattern, onForgot) { panel, pattern ->
            runVerification(active, panel, { PrivacyStore.verifyPattern(context, pattern) }) { onVerified(pattern) }
        }
        checkCooldown(active, prompt)
    }

    private fun pinPrompt(
        active: Request,
        title: Int,
        confirmValue: String? = null,
        onSubmitted: (Prompt, String) -> Unit
    ): Prompt {
        val prompt = prompt(active, title, usePin = true)
        val submit = View.OnClickListener {
            if (!isCurrent(active) || dialog !== prompt.dialog || !prompt.pin.isEnabled) return@OnClickListener
            val value = prompt.pin.text.toString()
            when {
                value.length != 6 || value.any { it !in '0'..'9' } ->
                    prompt.message(activity.getString(R.string.privacy_pin_length))
                confirmValue != null && confirmValue != value -> {
                    prompt.pin.text.clear()
                    prompt.message(activity.getString(R.string.privacy_pin_mismatch))
                }
                else -> onSubmitted(prompt, value)
            }
        }
        prompt.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(submit)
        prompt.pin.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_DONE) false
            else {
                submit.onClick(prompt.pin)
                true
            }
        }
        return prompt
    }

    private fun patternPrompt(
        active: Request,
        title: Int,
        onForgot: (() -> Unit)? = null,
        onSubmitted: (Prompt, List<Int>) -> Unit
    ): Prompt {
        val prompt = prompt(active, title, usePin = false, onForgot = onForgot)
        prompt.pattern.onPatternComplete = { pattern ->
            if (isCurrent(active) && dialog === prompt.dialog) onSubmitted(prompt, pattern)
        }
        prompt.pattern.onPatternTooShort = {
            prompt.message(activity.getString(R.string.privacy_pattern_length))
        }
        return prompt
    }

    private fun prompt(active: Request, title: Int, usePin: Boolean, onForgot: (() -> Unit)? = null): Prompt {
        val view = activity.layoutInflater.inflate(R.layout.dialog_privacy_credential, null)
        val pin = view.findViewById<EditText>(R.id.inputPrivacyPin)
        val pattern = view.findViewById<PatternLockView>(R.id.privacyPattern)
        pin.visibility = if (usePin) View.VISIBLE else View.GONE
        pattern.visibility = if (usePin) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.tvPrivacyPatternInstruction).visibility = pattern.visibility
        val noListener: DialogInterface.OnClickListener? = null
        val dialog = CredentialDialog().apply {
            setTitle(title)
            setView(view)
            setButton(AlertDialog.BUTTON_NEGATIVE, activity.getText(R.string.cancel), noListener)
            if (usePin) setButton(AlertDialog.BUTTON_POSITIVE, activity.getText(R.string.confirm), noListener)
            if (onForgot != null) {
                setButton(AlertDialog.BUTTON_NEUTRAL, activity.getText(R.string.privacy_forgot_pattern), noListener)
            }
        }.applyRoundedSurface()
        if (usePin) {
            pin.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        val prompt = Prompt(dialog, pin, pattern, view.findViewById(R.id.tvPrivacyCredentialMessage))
        show(active, dialog)
        if (onForgot != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (isCurrent(active) && this.dialog === dialog) onForgot()
            }
        }
        return prompt
    }

    private fun runVerification(
        active: Request,
        prompt: Prompt,
        verify: () -> VerifyResult,
        onSuccess: () -> Unit
    ) {
        prompt.enableInput(false)
        prompt.message(activity.getString(R.string.privacy_checking))
        submit(active, prompt, {
            val result = runCatching(verify).getOrDefault(VerifyResult.STORAGE_ERROR)
            val remaining = if (result == VerifyResult.WRONG_CREDENTIALS || result == VerifyResult.LOCKED_OUT) {
                readCooldown()
            } else 0L
            result to remaining
        }) { (result, remaining) ->
            if (result == VerifyResult.SUCCESS) {
                onSuccess()
            } else {
                prompt.pin.text.clear()
                if (prompt.pattern.isShown) prompt.pattern.showErrorState()
                if (remaining > 0) {
                    showCooldown(active, prompt, remaining)
                } else {
                    prompt.enableInput(true)
                    prompt.message(activity.getString(when (result) {
                        VerifyResult.WRONG_CREDENTIALS -> R.string.privacy_wrong_credential
                        VerifyResult.INVALID_INPUT -> R.string.privacy_invalid_credential
                        VerifyResult.INVALID_STATE -> R.string.privacy_credentials_changed
                        else -> R.string.privacy_credentials_write_failed
                    }))
                }
            }
        }
    }

    private fun readCooldown(): Long =
        runCatching { PrivacyStore.remainingCooldownMs(context) }.getOrDefault(30_000L)

    private fun checkCooldown(active: Request, prompt: Prompt) {
        prompt.enableInput(false)
        submit(active, prompt, ::readCooldown) { remaining ->
            if (remaining > 0) showCooldown(active, prompt, remaining)
            else {
                prompt.enableInput(true)
                prompt.message(null)
            }
        }
    }

    private fun showCooldown(active: Request, prompt: Prompt, remaining: Long) {
        val deadline = SystemClock.elapsedRealtime() + remaining
        countdown = object : Runnable {
            override fun run() {
                if (!isCurrent(active) || dialog !== prompt.dialog) return
                val left = deadline - SystemClock.elapsedRealtime()
                if (left > 0) {
                    prompt.message(activity.getString(R.string.privacy_cooldown, (left + 999) / 1000))
                    handler.postDelayed(this, minOf(1000, left))
                } else {
                    countdown = null
                    checkCooldown(active, prompt)
                }
            }
        }.also { it.run() }
    }

    private fun <T> submit(active: Request, prompt: Prompt, task: () -> T, display: (T) -> Unit) {
        worker.execute {
            if (!isCurrent(active)) return@execute
            val result = task()
            activity.runOnUiThread {
                if (isCurrent(active) && dialog === prompt.dialog && prompt.dialog.isShowing &&
                    !activity.isFinishing && !activity.isDestroyed
                ) {
                    display(result)
                }
            }
        }
    }

    private fun isCurrent(active: Request) =
        request === active && active.epoch == PrivacySessionManager.sessionEpoch

    private fun start(block: (Request) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed || isShowing) return
        val active = Request(PrivacySessionManager.sessionEpoch)
        request = active
        PrivacySessionManager.addListener(lockListener)
        onVisibilityChanged(true)
        block(active)
    }

    private fun show(active: Request, next: AlertDialog) {
        if (!isCurrent(active)) return
        clearCountdown()
        val previous = dialog
        dialog = next
        previous?.dismiss()
        next.setCanceledOnTouchOutside(false)
        next.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        next.setOnCancelListener { if (dialog === next) dismissAll() }
        next.setOnDismissListener { if (dialog === next) dismissAll() }
        next.show()
        next.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            if (dialog === next) dismissAll()
        }
    }

    private fun finish(active: Request, action: () -> Unit) {
        if (!isCurrent(active)) return
        try {
            action()
        } finally {
            dismissAll()
        }
    }

    private fun clearCountdown() {
        countdown?.let(handler::removeCallbacks)
        countdown = null
    }

    fun dismissAll() {
        if (!isShowing) return
        request = null
        PrivacySessionManager.removeListener(lockListener)
        clearCountdown()
        val previous = dialog
        dialog = null
        previous?.window?.decorView?.visibility = View.INVISIBLE
        previous?.dismiss()
        onVisibilityChanged(false)
    }

    fun close() {
        dismissAll()
        worker.shutdownNow()
    }
}
