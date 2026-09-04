package com.musicconverter.miui.core

import java.io.File

enum class AudioOutputFormat(val extension: String, val label: String) {
    MP3("mp3", "MP3"), FLAC("flac", "FLAC"), M4A("m4a", "M4A/AAC"),
    WAV("wav", "WAV"), OGG("ogg", "OGG/Vorbis")
}

object AudioFormatDetector {
    private val encrypted = setOf(
        "ncm", "mflac", "mgg", "mflac0", "mgg0", "mgg1", "mggl", "mmp4",
        "qmcflac", "qmcogg", "qmc0", "qmc1", "qmc2", "qmc3", "qmc4", "qmc5", "qmc6", "qmc7", "qmc8",
        "tkm", "bkcmp3", "bkcm4a", "bkcflac", "bkcwav", "bkcape", "bkcogg", "bkcwma",
        "kgm", "kgma", "vpr", "kwm"
    )

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun isEncrypted(name: String): Boolean = extension(name) in encrypted || name.lowercase().endsWith(".kgm.flac") || name.lowercase().endsWith(".vpr.flac")
    fun isEditable(name: String): Boolean = extension(name) in setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")
    fun isSupported(name: String): Boolean = isEncrypted(name) || isEditable(name)
    fun label(name: String): String = when {
        isEncrypted(name) -> "加密音乐 (${extension(name).uppercase()})"
        extension(name).isBlank() -> "未知格式"
        else -> extension(name).uppercase()
    }
    fun extension(file: File): String = extension(file.name)
}
