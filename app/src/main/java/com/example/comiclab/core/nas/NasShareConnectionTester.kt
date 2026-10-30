package com.example.comiclab.core.nas

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import java.util.concurrent.TimeUnit

internal object NasShareConnectionTester {
    fun test(profile: NasShareProfile, password: String) {
        val config = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()

        SMBClient(config).use { client ->
            client.connect(profile.host).use { connection ->
                val username = profile.username.trim()
                val separator = username.indexOf('\\')
                val authentication = if (username.isEmpty()) {
                    AuthenticationContext.anonymous()
                } else {
                    val domain = if (separator > 0) username.substring(0, separator) else null
                    val account = if (separator > 0) username.substring(separator + 1) else username
                    AuthenticationContext(account, password.toCharArray(), domain)
                }
                val session = connection.authenticate(authentication)
                try {
                    session.connectShare(profile.shareName)
                } finally {
                    session.close()
                }
            }
        }
    }
}
