package com.musicconverter.miui.ui

import android.content.Context

object ThemePreferences {
    private const val PREFS = "musicconverter_theme"
    private const val KEY_DARK = "dark_mode"

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, true)

    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK, dark)
            .apply()
    }

    fun toggle(context: Context): Boolean {
        val next = !isDark(context)
        setDark(context, next)
        return next
    }

    fun label(context: Context): String =
        if (isDark(context)) "夜间模式" else "日间模式"
}
