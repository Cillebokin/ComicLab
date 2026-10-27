package com.example.comiclab.ui.reader.translation

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.example.comiclab.R
import com.example.comiclab.core.reader.enhance.EnhancementRuntime
import com.example.comiclab.core.reader.translation.*
import java.util.concurrent.atomic.AtomicBoolean

internal object TranslationSettingsDialog {
    fun show(activity: Activity) {
        val saved = runCatching { TranslationSettingsStore.load(activity) }.getOrElse { TranslationSettings("", "", "") }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 0) }
        fun field(label: Int, value: String, password: Boolean = false): EditText {
            column.addView(TextView(activity).apply { setText(label) })
            return EditText(activity).apply {
                setText(value); setSingleLine(); importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                column.addView(this)
            }
        }
        val address = field(R.string.translation_address, saved.address)
        val model = field(R.string.translation_model, saved.model)
        val key = field(R.string.translation_key, saved.key, true)
        column.addView(TextView(activity).apply { setText(R.string.translation_api_help) })
        val test = Button(activity).apply { setText(R.string.translation_test) }; column.addView(test)
        val cancelled = AtomicBoolean()
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.translation_settings).setView(column)
            .setPositiveButton(R.string.translation_save_settings, null).setNegativeButton(android.R.string.cancel, null).create()
        fun settings() = TranslationSettings(address.text.toString().trim(), model.text.toString().trim(), key.text.toString().trim())
        dialog.setOnDismissListener { cancelled.set(true) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { TranslationSettingsStore.save(activity, settings()); dialog.dismiss() }
                catch (_: Exception) { Toast.makeText(activity, R.string.translation_invalid_settings, Toast.LENGTH_LONG).show() }
            }
            test.setOnClickListener {
                val config = settings()
                try { TranslationPolicy.endpoint(config.address); require(config.key.isNotBlank() && config.model.isNotBlank()) }
                catch (_: Exception) { Toast.makeText(activity, R.string.translation_invalid_settings, Toast.LENGTH_LONG).show(); return@setOnClickListener }
                test.isEnabled = false
                EnhancementRuntime.io.execute {
                    val ok = runCatching { TranslationApi.translate(config, listOf("こんにちは"), "Simplified Chinese", cancelled) }.isSuccess
                    activity.runOnUiThread {
                        if (dialog.isShowing && !activity.isDestroyed) {
                            test.isEnabled = true
                            Toast.makeText(activity, if (ok) R.string.translation_test_ok else R.string.translation_test_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
