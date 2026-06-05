package com.example.comiclab

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog

fun BottomSheetDialog.showRoundedContent(content: View) {
    setContentView(content)
    setOnShowListener {
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipToOutline = true
        }
    }
    show()
}
