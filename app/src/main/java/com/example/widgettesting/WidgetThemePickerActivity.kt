package com.example.widgettesting

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle

class WidgetThemePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themes = WidgetTheme.themes()
        val labels = themes.map { if (it == "coffee") "Café" else it.replaceFirstChar(Char::uppercase) }
        AlertDialog.Builder(this)
            .setTitle("Widget background")
            .setSingleChoiceItems(labels.toTypedArray(), themes.indexOf(WidgetTheme.current(this))) { dialog, index ->
                WidgetTheme.setFromText(this, themes[index])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .setOnDismissListener { finish() }
            .show()
    }
}
