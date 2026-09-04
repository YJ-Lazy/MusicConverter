package com.musicconverter.miui.core

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

data class FfmpegResult(val success: Boolean, val message: String = "")

object FfmpegEngine {
    fun availability(): FfmpegResult = try {
        FfmpegResult(true, "FFmpegKit ${FFmpegKitConfig.getVersion()}")
    } catch (t: Throwable) {
        FfmpegResult(false, "FFmpeg native 未加载: ${t.message}")
    }

    fun convert(input: File, output: File, target: AudioOutputFormat, threads: Int? = null): FfmpegResult {
        output.parentFile?.mkdirs()
        output.delete()
        val args = mutableListOf("-y", "-i", input.absolutePath, "-map_metadata", "0", "-vn")
        args += codecArgs(target.extension)
        if (threads != null && threads > 0) args += listOf("-threads", threads.toString())
        args += output.absolutePath
        return execute(args)
    }


    fun cancelAll() {
        runCatching { FFmpegKit.cancel() }
    }

    /** Fast, no-generation-loss trim. Falls back to re-encoding if stream copy isn't accepted. */
    fun trim(input: File, output: File, startMs: Long, endMs: Long): FfmpegResult {
        if (endMs <= startMs) return FfmpegResult(false, "结束时间必须大于开始时间")
        output.parentFile?.mkdirs()
        output.delete()
        val start = seconds(startMs)
        val duration = seconds(endMs - startMs)
        val copy = execute(listOf(
            "-y", "-ss", start, "-i", input.absolutePath, "-t", duration,
            "-map_metadata", "0", "-vn", "-c:a", "copy", "-avoid_negative_ts", "make_zero", output.absolutePath
        ))
        if (copy.success && output.exists() && output.length() > 0) return copy

        output.delete()
        val ext = AudioFormatDetector.extension(output)
        val args = mutableListOf("-y", "-ss", start, "-i", input.absolutePath, "-t", duration, "-map_metadata", "0", "-vn")
        args += codecArgs(ext)
        args += output.absolutePath
        return execute(args)
    }

    private fun codecArgs(ext: String): List<String> = when (ext.lowercase()) {
        "mp3" -> listOf("-c:a", "libmp3lame", "-q:a", "2")
        "flac" -> listOf("-c:a", "flac")
        "m4a", "aac" -> listOf("-c:a", "aac", "-b:a", "256k")
        "wav" -> listOf("-c:a", "pcm_s16le")
        "ogg" -> listOf("-c:a", "libvorbis", "-q:a", "6")
        "opus" -> listOf("-c:a", "libopus", "-b:a", "192k")
        else -> listOf("-c:a", "copy")
    }

    private fun execute(args: List<String>): FfmpegResult = try {
        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        if (ReturnCode.isSuccess(session.returnCode)) {
            FfmpegResult(true, "完成")
        } else {
            val tail = try { session.allLogsAsString?.takeLast(1800) ?: "" } catch (_: Throwable) { "" }
            FfmpegResult(false, "FFmpeg 返回 ${session.returnCode}. $tail")
        }
    } catch (t: Throwable) {
        FfmpegResult(false, "${t.javaClass.simpleName}: ${t.message}")
    }

    private fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
}
