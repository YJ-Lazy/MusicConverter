package com.musicconverter.miui.ui

import android.content.Context
import android.content.res.Configuration

object ThemePreferences {
    private const val PREFS = "musicconverter_theme"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_MODE = "appearance_mode"

    enum class Mode(val title: String) {
        SYSTEM("跟随系统"), LIGHT("日间模式"), DARK("夜间模式")
    }

    fun mode(context: Context): Mode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Mode.values().firstOrNull { it.name == prefs.getString(KEY_MODE, null) }?.let { return it }
        return if (!prefs.contains(KEY_DARK)) Mode.SYSTEM
            else if (prefs.getBoolean(KEY_DARK, true)) Mode.DARK else Mode.LIGHT
    }

    fun isDark(context: Context): Boolean = when (mode(context)) {
        Mode.LIGHT -> false
        Mode.DARK -> true
        Mode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    fun label(context: Context): String = mode(context).title
}
