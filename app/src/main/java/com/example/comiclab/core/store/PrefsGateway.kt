package com.example.comiclab.core.store

import android.content.Context
import android.content.SharedPreferences

internal object PrefsGateway {

    fun open(context: Context, preferencesName: String): SharedPreferences =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
}
