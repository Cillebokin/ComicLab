package com.example.comiclab

import android.content.DialogInterface
import android.graphics.drawable.ColorDrawable
import androidx.core.content.ContextCompat

fun android.app.AlertDialog.Builder.showRounded(): android.app.AlertDialog {
    return createRounded().also { it.show() }
}

fun android.app.AlertDialog.Builder.createRounded(): android.app.AlertDialog {
    return create().applyRoundedSurface()
}

fun androidx.appcompat.app.AlertDialog.Builder.showRounded(): androidx.appcompat.app.AlertDialog {
    return createRounded().also { it.show() }
}

fun androidx.appcompat.app.AlertDialog.Builder.createRounded(): androidx.appcompat.app.AlertDialog {
    return create().applyRoundedSurface()
}

fun android.app.AlertDialog.applyRoundedSurface(): android.app.AlertDialog {
    setOnShowListener {
        window?.setBackgroundDrawable(
            ContextCompat.getDrawable(context, R.drawable.bg_dialog_rounded)
                ?: ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setTextColor(ContextCompat.getColor(context, R.color.comiclab_accent))
        getButton(DialogInterface.BUTTON_NEGATIVE)
            ?.setTextColor(ContextCompat.getColor(context, R.color.comiclab_text_secondary))
        getButton(DialogInterface.BUTTON_NEUTRAL)
            ?.setTextColor(ContextCompat.getColor(context, R.color.comiclab_text_secondary))
    }
    return this
}

fun androidx.appcompat.app.AlertDialog.applyRoundedSurface(): androidx.appcompat.app.AlertDialog {
    setOnShowListener {
        window?.setBackgroundDrawable(
            ContextCompat.getDrawable(context, R.drawable.bg_dialog_rounded)
                ?: ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setTextColor(ContextCompat.getColor(context, R.color.comiclab_accent))
        getButton(DialogInterface.BUTTON_NEGATIVE)
            ?.setTextColor(ContextCompat.getColor(context, R.color.comiclab_text_secondary))
        getButton(DialogInterface.BUTTON_NEUTRAL)
            ?.setTextColor(ContextCompat.getColor(context, R.color.comiclab_text_secondary))
    }
    return this
}
