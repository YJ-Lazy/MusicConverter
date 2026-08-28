package com.musicconverter.miui.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PreparedAudio(
    val originalUri: Uri,
    val localFile: File,
    val displayName: String,
    val size: Long
)

data class ReplaceResult(
    val success: Boolean,
    val message: String = "",
    val newUri: Uri? = null
)

object AudioFileManager {
    fun prepareInput(context: Context, uri: Uri): PreparedAudio {
        var displayName = "audio_${System.currentTimeMillis()}"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = c.getString(nameIndex) ?: displayName
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._()\\-\\u4e00-\\u9fff ]"), "_")
        val inputDir = File(context.cacheDir, "inputs").apply { mkdirs() }
        val dest = File(inputDir, "${UUID.randomUUID()}_$safeName")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件" }
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        return PreparedAudio(uri, dest, displayName, if (size >= 0) size else dest.length())
    }

    fun publishAudio(context: Context, source: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(displayName))
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MusicConverter")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)) { "无法创建输出文件" }
        try {
            resolver.openOutputStream(uri, "w").use { out ->
                requireNotNull(out) { "无法写入输出文件" }
                source.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    fun replaceOriginal(
        context: Context,
        originalUri: Uri,
        originalName: String,
        convertedUri: Uri,
        convertedName: String,
        backupFile: File? = null
    ): ReplaceResult {
        val resolver = context.contentResolver
        var targetUri = originalUri
        var renamed = false
        try {
            if (DocumentsContract.isDocumentUri(context, originalUri)) {
                val renamedUri = try {
                    DocumentsContract.renameDocument(resolver, originalUri, convertedName)
                } catch (_: Throwable) { null }
                if (renamedUri == null) return ReplaceResult(false, "文件提供方不支持原位重命名")
                targetUri = renamedUri
                renamed = true
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, convertedName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(convertedName))
                }
                if (resolver.update(originalUri, values, null, null) <= 0) return ReplaceResult(false, "无法重命名源文件")
                renamed = true
            }

            resolver.openInputStream(convertedUri).use { inputStream ->
                requireNotNull(inputStream) { "无法读取转换结果" }
                resolver.openOutputStream(targetUri, "w").use { outputStream ->
                    requireNotNull(outputStream) { "源文件不允许写入" }
                    inputStream.copyTo(outputStream)
                    outputStream.flush()
                }
            }
            try { resolver.delete(convertedUri, null, null) } catch (_: Throwable) {}
            return ReplaceResult(true, newUri = targetUri)
        } catch (t: Throwable) {
            var restored = false
            if (backupFile != null && backupFile.exists()) {
                try {
                    resolver.openOutputStream(targetUri, "w").use { outputStream ->
                        requireNotNull(outputStream) { "无法恢复源文件" }
                        backupFile.inputStream().use { it.copyTo(outputStream) }
                        outputStream.flush()
                    }
                    restored = true
                } catch (_: Throwable) {}
            }
            if (renamed) {
                try {
                    if (DocumentsContract.isDocumentUri(context, targetUri)) {
                        DocumentsContract.renameDocument(resolver, targetUri, originalName)
                    } else {
                        val rollback = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, originalName) }
                        resolver.update(targetUri, rollback, null, null)
                    }
                } catch (_: Throwable) {}
            }
            val base = t.message ?: t.javaClass.simpleName
            val message = if (backupFile != null) {
                if (restored) "$base（已恢复源文件）" else "$base（自动恢复失败，请检查源文件）"
            } else base
            return ReplaceResult(false, message)
        }
    }

    fun mimeFor(name: String): String = when (AudioFormatDetector.extension(name)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        else -> "application/octet-stream"
    }

    fun outputName(inputName: String, extension: String, suffix: String = "converted"): String {
        val stem = inputName.substringBeforeLast('.', inputName)
        return "${stem}_${suffix}.${extension}"
    }
}
