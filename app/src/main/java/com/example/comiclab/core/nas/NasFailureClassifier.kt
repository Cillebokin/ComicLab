package com.example.comiclab.core.nas

import android.content.Context
import com.example.comiclab.R
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

internal data class NasFailureDetails(
    val messageResId: Int,
    val diagnosticCode: String
)

internal object NasFailureClassifier {
    fun classify(exception: Throwable, localDownload: Boolean = false): NasFailureDetails {
        val causes = generateSequence(exception) { it.cause }.take(12).toList()
        val smbException = causes.filterIsInstance<SMBApiException>().firstOrNull()
        if (smbException != null) {
            val status = smbException.status
            val messageResId = when (status) {
                NtStatus.STATUS_LOGON_FAILURE,
                NtStatus.STATUS_PASSWORD_EXPIRED,
                NtStatus.STATUS_ACCOUNT_DISABLED,
                NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED -> R.string.nas_failure_credentials

                NtStatus.STATUS_BAD_NETWORK_NAME -> R.string.nas_failure_share_not_found
                NtStatus.STATUS_ACCESS_DENIED,
                NtStatus.STATUS_PRIVILEGE_NOT_HELD -> R.string.nas_failure_access_denied

                NtStatus.STATUS_BAD_NETWORK_PATH -> R.string.nas_failure_network
                NtStatus.STATUS_TIMEOUT,
                NtStatus.STATUS_IO_TIMEOUT -> R.string.nas_failure_timeout

                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                NtStatus.STATUS_NO_SUCH_FILE -> R.string.nas_failure_remote_path_missing

                NtStatus.STATUS_CONNECTION_DISCONNECTED,
                NtStatus.STATUS_CONNECTION_RESET,
                NtStatus.STATUS_NETWORK_NAME_DELETED,
                NtStatus.STATUS_USER_SESSION_DELETED -> R.string.nas_failure_disconnected

                else -> R.string.nas_failure_generic
            }
            val hexCode = java.lang.Long.toHexString(smbException.statusCode)
                .uppercase(Locale.ROOT)
                .padStart(8, '0')
            return NasFailureDetails(
                messageResId,
                "SMB ${status.name} (0x$hexCode)"
            )
        }

        val messageResId = when {
            causes.any { it is UnknownHostException } -> R.string.nas_failure_unknown_host
            causes.any { it is SocketTimeoutException } -> R.string.nas_failure_timeout
            causes.any { it is ConnectException || it is NoRouteToHostException } ->
                R.string.nas_failure_network
            causes.any { it is SocketException } -> R.string.nas_failure_disconnected
            localDownload && causes.any(::isLocalPermissionFailure) ->
                R.string.nas_failure_storage_permission
            else -> R.string.nas_failure_generic
        }
        val diagnosticCause = causes.firstOrNull {
            it is UnknownHostException || it is SocketTimeoutException ||
                it is ConnectException || it is NoRouteToHostException ||
                it is SocketException || it is SecurityException || it is IOException
        } ?: causes.lastOrNull() ?: exception
        return NasFailureDetails(messageResId, diagnosticCause.javaClass.simpleName)
    }

    fun localPathFailure(exception: Throwable): NasFailureDetails = NasFailureDetails(
        R.string.nas_failure_local_path,
        exception.javaClass.simpleName
    )

    fun format(context: Context, details: NasFailureDetails): String = context.getString(
        R.string.nas_failure_with_diagnostic,
        context.getString(details.messageResId),
        details.diagnosticCode
    )

    private fun isLocalPermissionFailure(throwable: Throwable): Boolean {
        if (throwable is SecurityException) return true
        val message = throwable.message.orEmpty()
        return message.contains("permission denied", ignoreCase = true) ||
            message.contains("eacces", ignoreCase = true) ||
            message.contains("operation not permitted", ignoreCase = true)
    }
}
