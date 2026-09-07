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


    fun concat(inputs: List<File>, output: File): FfmpegResult {
        if (inputs.size < 2) return FfmpegResult(false, "至少需要两个音频文件")
        output.parentFile?.mkdirs()
        output.delete()

        val args = mutableListOf("-y")
        inputs.forEach { args += listOf("-i", it.absolutePath) }

        val normalized = inputs.indices.joinToString("") { i ->
            "[$i:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[a$i];"
        }
        val concatInputs = inputs.indices.joinToString("") { "[a$it]" }
        val filter = normalized + "$concatInputs" +
            "concat=n=${inputs.size}:v=0:a=1[outa]"

        args += listOf(
            "-filter_complex", filter,
            "-map", "[outa]",
            "-vn",
            "-c:a", "libmp3lame",
            "-q:a", "2",
            output.absolutePath
        )
        return execute(args)
    }

    fun pitchShift(input: File, output: File, semitones: Int): FfmpegResult {
        if (semitones !in -12..12) return FfmpegResult(false, "升降调范围为 -12 到 +12 半音")
        output.parentFile?.mkdirs()
        output.delete()

        val factor = Math.pow(2.0, semitones / 12.0)
        val changedRate = 44100.0 * factor
        val tempo = 1.0 / factor
        val filter = String.format(
            Locale.US,
            "aresample=44100,asetrate=%.3f,aresample=44100,atempo=%.6f",
            changedRate,
            tempo
        )

        return execute(
            listOf(
                "-y",
                "-i", input.absolutePath,
                "-vn",
                "-af", filter,
                "-c:a", "libmp3lame",
                "-q:a", "2",
                output.absolutePath
            )
        )
    }

    data class MixTrack(
        val file: File,
        val offsetMs: Long = 0L,
        val volume: Float = 1f
    )

    fun mix(tracks: List<MixTrack>, output: File): FfmpegResult {
        if (tracks.size < 2) return FfmpegResult(false, "多轨混音至少需要两条轨道")
        output.parentFile?.mkdirs()
        output.delete()

        val args = mutableListOf("-y")
        tracks.forEach { args += listOf("-i", it.file.absolutePath) }

        val filters = mutableListOf<String>()
        tracks.forEachIndexed { index, track ->
            val delay = track.offsetMs.coerceAtLeast(0L)
            val volume = track.volume.coerceIn(0f, 2f)
            filters += String.format(
                Locale.US,
                "[%d:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo," +
                    "adelay=%d|%d,volume=%.3f[a%d]",
                index, delay, delay, volume, index
            )
        }
        val mixInputs = tracks.indices.joinToString("") { "[a$it]" }
        filters += "$mixInputs" +
            "amix=inputs=${tracks.size}:duration=longest:dropout_transition=0:normalize=0," +
            "alimiter=limit=0.95[outa]"

        args += listOf(
            "-filter_complex", filters.joinToString(";"),
            "-map", "[outa]",
            "-vn",
            "-c:a", "libmp3lame",
            "-q:a", "2",
            output.absolutePath
        )
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
