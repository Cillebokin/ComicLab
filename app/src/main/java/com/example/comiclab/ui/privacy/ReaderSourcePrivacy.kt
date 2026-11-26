package com.example.comiclab.ui.privacy

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.MainThread
import com.example.comiclab.core.store.PrivacyStore
import java.io.File
import java.util.UUID

/** Keeps a reader page tied to the source and, for private books, to its launching session. */
internal class ReaderSourcePrivacy(
    private val activity: Activity,
    private val onRevoked: (maySaveLastPosition: Boolean) -> Unit = {}
) {
    private class Source(val file: File, val canonicalPath: String, val epoch: Long?)

    @Volatile private var source: Source? = null
    @Volatile private var revoked = false
    private var marksListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val lockListener = PrivacySessionManager.Listener { checkAccess() }
    private val drawListener = ViewTreeObserver.OnPreDrawListener { checkAccess() }
    private val dialogs = mutableListOf<Dialog>()

    @MainThread
    fun bind(file: File): Boolean {
        val path = PrivacyStore.resolveCanonicalPath(file)
        val epoch = activity.intent.takeIf { it.hasExtra(EXTRA_EPOCH) }?.getLongExtra(EXTRA_EPOCH, -1L)
        if (path == null || isPrivate(activity, path) != (epoch != null) ||
            (epoch != null && (!PrivacySessionManager.isCurrentSession(epoch) ||
                activity.intent.getStringExtra(EXTRA_PROCESS) != processToken))
        ) {
            revoke()
            return false
        }
        source = Source(file, path, epoch)
        if (epoch != null) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        PrivacySessionManager.addListener(lockListener)
        marksListener = PrivacyStore.registerDirectoryChangeListener(activity) {
            activity.runOnUiThread { if (!activity.isDestroyed) checkAccess() }
        }
        activity.window.decorView.viewTreeObserver.addOnPreDrawListener(drawListener)
        return checkAccess()
    }

    fun canRead(): Boolean {
        if (revoked) return false
        val current = source ?: return true
        return sourceIsUnchanged(current) && !revoked &&
            (current.epoch == null || PrivacySessionManager.isCurrentSession(current.epoch))
    }

    private fun sourceIsUnchanged(current: Source): Boolean =
        PrivacyStore.resolveCanonicalPath(current.file) == current.canonicalPath &&
            isPrivate(activity, current.canonicalPath) == (current.epoch != null)

    @MainThread
    fun checkAccess(): Boolean {
        if (canRead()) return true
        revoke()
        return false
    }

    fun allowsRelatedBook(file: File): Boolean {
        val path = PrivacyStore.resolveCanonicalPath(file) ?: return false
        return canRead() && isPrivate(activity, path) == (source?.epoch != null)
    }

    @MainThread
    fun showDialog(dialog: Dialog) {
        if (!checkAccess() || activity.isFinishing || activity.isDestroyed) return
        if (source?.epoch != null) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialogs.removeAll { !it.isShowing }
        dialogs.add(dialog)
        dialog.show()
    }

    @MainThread
    fun close() {
        revoked = true
        PrivacySessionManager.removeListener(lockListener)
        marksListener?.let { PrivacyStore.unregisterDirectoryChangeListener(activity, it) }
        marksListener = null
        val observer = activity.window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(drawListener)
        dismissDialogs()
    }

    private fun revoke() {
        if (revoked) return
        revoked = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.findViewById<View>(android.R.id.content)?.visibility = View.INVISIBLE
        activity.window.decorView.visibility = View.INVISIBLE
        try {
            dismissDialogs()
            val current = source
            onRevoked(current?.epoch != null && sourceIsUnchanged(current))
        } finally {
            activity.finish()
        }
    }

    private fun dismissDialogs() {
        val openDialogs = dialogs.toList()
        dialogs.clear()
        openDialogs.forEach { dialog ->
            if (dialog.isShowing) {
                dialog.window?.decorView?.visibility = View.INVISIBLE
                dialog.dismiss()
            }
        }
    }

    companion object {
        private const val EXTRA_EPOCH = "reader_privacy_epoch"
        private const val EXTRA_PROCESS = "reader_privacy_process"
        private val processToken = UUID.randomUUID().toString()

        fun authorizeLaunch(context: Context, intent: Intent, file: File): Boolean {
            val path = PrivacyStore.resolveCanonicalPath(file) ?: return false
            if (isPrivate(context, path)) {
                val epoch = PrivacySessionManager.sessionEpoch
                if (!PrivacySessionManager.isCurrentSession(epoch)) return false
                intent.putExtra(EXTRA_EPOCH, epoch)
                intent.putExtra(EXTRA_PROCESS, processToken)
            }
            return true
        }

        private fun isPrivate(context: Context, canonicalPath: String) =
            PrivacyStore.isCanonicalPathUnderPrivacyDirectory(context, canonicalPath)
    }
}
