package com.musicconverter.miui.core

import android.content.Context
import android.net.Uri
import com.musicconverter.miui.data.HistoryRepository
import java.io.File

data class ConversionResult(val success: Boolean, val outputUri: Uri? = null, val error: String = "", val outputName: String = "")

class ConversionEngine(private val context: Context) {
    private val unlocker = PythonUnlockEngine(context)
    private val history = HistoryRepository(context)

    fun convert(input: PreparedAudio, target: AudioOutputFormat, ffmpegThreads: Int? = null): ConversionResult {
        var working = input.localFile
        var sourceContainer = AudioFormatDetector.extension(input.displayName)
        if (AudioFormatDetector.isEncrypted(input.displayName)) {
            history.record(input.displayName, "", "解密", "处理中")
            val unlock = unlocker.unlock(input.localFile)
            if (!unlock.success || unlock.file == null) {
                history.record(input.displayName, "", "解密", "失败: ${unlock.error}")
                return ConversionResult(false, error = unlock.error)
            }
            working = unlock.file
            sourceContainer = unlock.container.ifBlank { AudioFormatDetector.extension(working) }
        }

        val outName = AudioFileManager.outputName(input.displayName, target.extension)
        val outputDir = File(context.cacheDir, "outputs").apply { mkdirs() }
        val tempOut = File.createTempFile("convert_", ".${target.extension}", outputDir)
        val needsTranscode = sourceContainer.lowercase() != target.extension.lowercase()
        val ok = if (needsTranscode) {
            FfmpegEngine.convert(working, tempOut, target, ffmpegThreads)
        } else {
            working.copyTo(tempOut, overwrite = true)
            FfmpegResult(true)
        }
        if (!ok.success) {
            history.record(input.displayName, outName, "转换", "失败: ${ok.message}")
            return ConversionResult(false, error = ok.message)
        }

        return try {
            val uri = AudioFileManager.publishAudio(context, tempOut, outName)
            tempOut.delete()
            if (working != input.localFile && working.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                working.delete()
                working.parentFile?.takeIf { it.name.startsWith("unlock_") }?.delete()
            }
            history.record(input.displayName, outName, "转换", "完成")
            ConversionResult(true, uri, outputName = outName)
        } catch (t: Throwable) {
            history.record(input.displayName, outName, "保存", "失败: ${t.message}")
            ConversionResult(false, error = "保存失败: ${t.message}")
        }
    }

    fun prepareEditable(input: PreparedAudio): Pair<File?, String?> {
        if (!AudioFormatDetector.isEncrypted(input.displayName)) return input.localFile to null
        val unlock = unlocker.unlock(input.localFile)
        return if (unlock.success) unlock.file to null else null to unlock.error
    }
}
