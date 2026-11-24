package com.example.comiclab.core.nas

internal object NasShareConnectionTester {
    fun test(profile: NasShareProfile, password: String) {
        NasShareDirectoryBrowser.list(profile, password, relativePath = "")
    }
}
