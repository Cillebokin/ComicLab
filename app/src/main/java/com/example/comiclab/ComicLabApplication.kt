package com.example.comiclab

import android.app.Application

class ComicLabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogManager.install(this)
    }
}
