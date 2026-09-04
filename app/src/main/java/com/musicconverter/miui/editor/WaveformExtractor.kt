package com.musicconverter.miui.editor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

object WaveformExtractor {

    fun extract(file: File, targetSamples: Int = 180): List<Float> {
        if (!file.exists() || file.length() <= 0L) return emptyList()
        return try {
            extractByDecoder(file, targetSamples).ifEmpty {
                extractByBytes(file, targetSamples)
            }
        } catch (_: Throwable) {
            extractByBytes(file, targetSamples)
        }
    }

    private fun extractByDecoder(file: File, targetSamples: Int): List<Float> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = candidate
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return emptyList()
        }

        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return emptyList()
        }

        extractor.selectTrack(trackIndex)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val amplitudes = ArrayList<Float>(targetSamples * 6)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val segmentSize = 2048

        try {
            while (!outputDone && amplitudes.size < targetSamples * 8) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 1) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val slice = outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                                val shortBuffer = slice.asShortBuffer()
                                val samples = ShortArray(shortBuffer.remaining())
                                shortBuffer.get(samples)

                                var cursor = 0
                                while (cursor < samples.size) {
                                    val end = minOf(cursor + segmentSize, samples.size)
                                    var sum = 0f
                                    for (i in cursor until end) {
                                        sum += abs(samples[i].toInt()) / 32768f
                                    }
                                    val avg = if (end == cursor) 0f else sum / (end - cursor).toFloat()
                                    amplitudes += avg
                                    cursor = end
                                }
                            }
                            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (eos) outputDone = true
                        }
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Throwable) {}
            try {
                codec.release()
            } catch (_: Throwable) {}
            try {
                extractor.release()
            } catch (_: Throwable) {}
        }

        return normalize(downsample(amplitudes, targetSamples))
    }

    private fun extractByBytes(file: File, targetSamples: Int): List<Float> {
        val length = file.length()
        if (length <= 0L) return emptyList()

        val result = ArrayList<Float>(targetSamples)
        RandomAccessFile(file, "r").use { raf ->
            val bufferSize = 4096
            val buffer = ByteArray(bufferSize)
            for (i in 0 until targetSamples) {
                val offset = ((i.toDouble() / targetSamples.toDouble()) * max(0L, length - bufferSize).toDouble()).toLong()
                raf.seek(offset)
                val read = raf.read(buffer)
                if (read <= 0) {
                    result += 0f
                    continue
                }
                var sum = 0f
                for (j in 0 until read) {
                    sum += abs(buffer[j].toInt()) / 128f
                }
                result += sum / read.toFloat()
            }
        }
        return normalize(result)
    }

    private fun downsample(values: List<Float>, targetSamples: Int): List<Float> {
        if (values.isEmpty()) return emptyList()
        if (values.size <= targetSamples) return values
        val result = ArrayList<Float>(targetSamples)
        val bucket = values.size.toFloat() / targetSamples.toFloat()
        for (i in 0 until targetSamples) {
            val start = (i * bucket).toInt().coerceAtMost(values.lastIndex)
            val end = (((i + 1) * bucket).toInt()).coerceAtMost(values.size)
            var maxValue = 0f
            for (j in start until max(start + 1, end)) {
                maxValue = max(maxValue, values[j])
            }
            result += maxValue
        }
        return result
    }

    private fun normalize(values: List<Float>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val maxValue = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        return values.map {
            sqrt((it / maxValue).coerceIn(0f, 1f))
        }
    }
}
