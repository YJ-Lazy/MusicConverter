package com.musicconverter.miui.core

import android.content.Context

data class IgnoredFormatOption(
    val id: String,
    val label: String,
    val extensions: Set<String>
)

object IgnoredFormatPreferences {
    private const val PREFS = "batch_scan_preferences"
    private const val KEY_IDS = "ignored_format_group_ids"

    val options: List<IgnoredFormatOption> = listOf(
        IgnoredFormatOption("mp3", "MP3", setOf("mp3")),
        IgnoredFormatOption("flac", "FLAC", setOf("flac")),
        IgnoredFormatOption("m4a_aac", "M4A / AAC", setOf("m4a", "aac")),
        IgnoredFormatOption("wav", "WAV", setOf("wav")),
        IgnoredFormatOption("ogg_opus", "OGG / OPUS", setOf("ogg", "opus")),
        IgnoredFormatOption("ncm", "网易云 NCM", setOf("ncm")),
        IgnoredFormatOption(
            "qq_encrypted",
            "QQ 音乐加密格式",
            setOf(
                "mflac", "mgg", "mflac0", "mgg0", "mgg1", "mggl", "mmp4",
                "qmcflac", "qmcogg", "qmc0", "qmc1", "qmc2", "qmc3", "qmc4",
                "qmc5", "qmc6", "qmc7", "qmc8", "tkm",
                "bkcmp3", "bkcm4a", "bkcflac", "bkcwav", "bkcape", "bkcogg", "bkcwma"
            )
        ),
        IgnoredFormatOption("kgm", "酷狗 KGM / KGMA / VPR", setOf("kgm", "kgma", "vpr")),
        IgnoredFormatOption("kwm", "酷我 KWM", setOf("kwm"))
    )

    private val defaultIds = setOf("mp3")

    fun selectedIds(context: Context): Set<String> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, null)
        return stored?.toSet() ?: defaultIds
    }

    fun saveSelectedIds(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_IDS, ids.toSet())
            .apply()
    }

    fun ignoredExtensions(context: Context): Set<String> {
        val ids = selectedIds(context)
        return options.asSequence()
            .filter { it.id in ids }
            .flatMap { it.extensions.asSequence() }
            .map { it.lowercase() }
            .toSet()
    }

    fun shouldIgnore(context: Context, fileName: String): Boolean {
        val ext = AudioFormatDetector.extension(fileName)
        return ext.isNotBlank() && ext in ignoredExtensions(context)
    }

    fun summary(context: Context): String {
        val ids = selectedIds(context)
        if (ids.isEmpty()) return "不忽略任何格式"
        return options.filter { it.id in ids }.joinToString("、") { it.label }
    }
}
