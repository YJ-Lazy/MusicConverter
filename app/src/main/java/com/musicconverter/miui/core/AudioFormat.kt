package com.musicconverter.miui.core

import java.io.File

enum class AudioOutputFormat(val extension: String, val label: String) {
    MP3("mp3", "MP3"), FLAC("flac", "FLAC"), M4A("m4a", "M4A/AAC"),
    WAV("wav", "WAV"), OGG("ogg", "OGG/Vorbis")
}

object AudioFormatDetector {
    private val encrypted = setOf(
        "ncm", "mflac", "mflac0", "mflach", "mgg", "mgg0", "mgg1", "mggl", "mmp4",
        "qmflac", "qmcflac", "qmcogg", "qmc0", "qmc2", "qmc3", "qmc4", "qmc6", "qmc8",
        "tkm", "bkcmp3", "bkcm4a", "bkcflac", "bkcwav", "bkcape", "bkcogg", "bkcwma",
        "kgm", "kgma", "vpr", "kwm"
    )
    private val audioWrappers = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * Returns the actual encrypted format marker even when QQ Music appends a normal
     * audio-looking suffix, e.g. `song.mgg0.flac` -> `mgg0`.
     */
    fun encryptedExtension(name: String): String? {
        val lower = name.lowercase()
        val last = extension(lower)
        if (last in encrypted) return last
        if (last !in audioWrappers) return null

        val withoutWrapper = lower.substringBeforeLast('.', lower)
        val inner = extension(withoutWrapper)
        return inner.takeIf { it in encrypted }
    }

    fun isEncrypted(name: String): Boolean = encryptedExtension(name) != null
    fun isEditable(name: String): Boolean = extension(name) in audioWrappers
    fun isSupported(name: String): Boolean = isEncrypted(name) || isEditable(name)

    fun label(name: String): String = when {
        isEncrypted(name) -> "加密音乐 (${encryptedExtension(name)?.uppercase() ?: "未知"})"
        extension(name).isBlank() -> "未知格式"
        else -> extension(name).uppercase()
    }

    /** Remove both suffixes for disguised encrypted names such as .mgg0.flac. */
    fun stem(name: String): String {
        val encryptedExt = encryptedExtension(name)
        if (encryptedExt == null) return name.substringBeforeLast('.', name)
        val lower = name.lowercase()
        val directSuffix = ".${encryptedExt}"
        if (lower.endsWith(directSuffix)) return name.dropLast(directSuffix.length)

        val wrapper = extension(name)
        val compoundSuffix = ".${encryptedExt}.${wrapper}"
        return if (lower.endsWith(compoundSuffix)) name.dropLast(compoundSuffix.length)
        else name.substringBeforeLast('.', name)
    }

    fun extension(file: File): String = extension(file.name)
}
