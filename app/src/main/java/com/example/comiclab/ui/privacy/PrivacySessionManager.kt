package com.example.comiclab.ui.privacy

import android.os.Environment
import androidx.annotation.MainThread

internal object PrivacySessionManager {
    fun interface Listener {
        fun onPrivacySessionLocked()
    }

    private data class State(
        val epoch: Long = 0,
        val inPrivacySpace: Boolean = false,
        val normalPath: String? = null,
        val pendingRestorePath: String? = null
    )

    // 界面和生命周期事件在主线程修改状态，后台任务读取同一份不可变快照。
    @Volatile
    private var state = State()

    private val listeners = mutableSetOf<Listener>()

    val sessionEpoch: Long get() = state.epoch
    val isUnlocked: Boolean get() = state.inPrivacySpace
    val isInPrivacySpace: Boolean get() = state.inPrivacySpace
    val returnNormalPath: String? get() = state.normalPath

    fun isCurrentSession(epoch: Long): Boolean {
        val current = state
        return current.inPrivacySpace && current.epoch == epoch
    }

    @MainThread
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    @MainThread
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @MainThread
    fun enterPrivacySpace(returnPath: String?, verifiedEpoch: Long): Boolean {
        val current = state
        if (current.epoch != verifiedEpoch || current.inPrivacySpace || current.pendingRestorePath != null) {
            return false
        }
        state = State(epoch = current.epoch + 1, inPrivacySpace = true, normalPath = returnPath)
        return true
    }

    @MainThread
    fun exitPrivacySpace(): String? {
        val current = state
        if (!current.inPrivacySpace) return null
        val restorePath = current.normalPath ?: Environment.getExternalStorageDirectory().absolutePath
        state = State(epoch = current.epoch + 1)
        notifyLocked()
        return restorePath
    }

    @MainThread
    fun lock() {
        val current = state
        val restorePath = if (current.inPrivacySpace) {
            current.normalPath ?: Environment.getExternalStorageDirectory().absolutePath
        } else {
            current.pendingRestorePath
        }
        state = State(epoch = current.epoch + 1, pendingRestorePath = restorePath)
        notifyLocked()
    }

    @MainThread
    fun consumePendingNormalRestorePath(): String? {
        val current = state
        val restorePath = current.pendingRestorePath ?: return null
        state = current.copy(pendingRestorePath = null)
        return restorePath
    }

    private fun notifyLocked() {
        listeners.toList().forEach { it.onPrivacySessionLocked() }
    }
}
