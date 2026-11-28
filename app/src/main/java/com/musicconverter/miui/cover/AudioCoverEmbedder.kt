package com.musicconverter.miui.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.system.Os
import android.util.Log
import com.musicconverter.miui.core.AudioFormatDetector
import com.musicconverter.miui.core.FfmpegEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Best-effort output artwork. Originals are never modified by this class. */
internal object AudioCoverEmbedder {
    private const val TAG = "MusicConverter-Cover"
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_DIMENSION = 1200

    private data class Tags(
        val title: String = "", val artist: String = "", val album: String = "",
        val picture: ByteArray? = null
    )

    fun complete(source: File, output: File, displayName: String) {
        if (AudioFormatDetector.extension(output) !in setOf("flac", "mp3") ||
            !output.isFile || output.length() == 0L || Thread.currentThread().isInterrupted) return
        var jpeg: File? = null
        var staged: File? = null
        try {
            val outputTags = readTags(output)
            if (outputTags.picture?.isNotEmpty() == true) return
            val sourceTags = readTags(source)
            val nameParts = AudioFormatDetector.stem(displayName).split(" - ", limit = 2)
            val title = valid(outputTags.title).ifBlank { valid(sourceTags.title) }
                .ifBlank { nameParts.getOrNull(1).orEmpty().trim() }
            val artist = valid(outputTags.artist).ifBlank { valid(sourceTags.artist) }
                .ifBlank { if (nameParts.size == 2) nameParts[0].trim() else "" }
            val album = valid(outputTags.album).ifBlank { valid(sourceTags.album) }
            val image = sourceTags.picture?.takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
                ?: OnlineCoverResolver.resolveForEmbedding(title, artist, album)?.let(::download)
                ?: return
            val dir = output.parentFile ?: return
            val imageFile = File.createTempFile("cover_", ".jpg", dir)
            jpeg = imageFile
            if (!toJpeg(image, imageFile) || Thread.currentThread().isInterrupted) return
            val resultFile = File.createTempFile("covered_", "." + AudioFormatDetector.extension(output), dir)
            staged = resultFile
            val result = FfmpegEngine.embedCover(output, imageFile, resultFile)
            if (!result.success || resultFile.length() == 0L ||
                readTags(resultFile).picture?.isNotEmpty() != true || Thread.currentThread().isInterrupted) {
                Log.w(TAG, "Cover remux failed or output could not be verified")
                return
            }
            // Same-directory atomic rename: a failed remux never truncates the output.
            Os.rename(resultFile.absolutePath, output.absolutePath)
            Log.i(TAG, "Album cover embedded")
        } catch (e: Exception) {
            Log.w(TAG, "Artwork skipped: " + e.javaClass.simpleName)
        } finally {
            jpeg?.delete()
            staged?.delete()
        }
    }

    private fun valid(value: String): String = value.trim().takeUnless {
        it.equals("<unknown>", true) || it.equals("unknown", true) ||
            it == "未知歌手" || it == "未知歌曲" || it == "未知专辑"
    }.orEmpty()

    private fun readTags(file: File): Tags {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            Tags(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                r.embeddedPicture
            )
        } catch (_: RuntimeException) {
            Tags()
        } finally {
            runCatching { r.release() }
        }
    }

    private fun download(address: String): ByteArray? {
        val url = URL(address)
        val host = url.host.lowercase()
        if (url.protocol != "https" || !(host.endsWith(".mzstatic.com") ||
                    host.endsWith(".music.126.net"))) return null
        val c = url.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 5000
            c.readTimeout = 7000
            c.instanceFollowRedirects = false
            if (c.responseCode !in 200..299 || c.contentLength > MAX_IMAGE_BYTES) return null
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_IMAGE_BYTES) return null
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    private fun toJpeg(data: ByteArray, target: File): Boolean {
        if (data.isEmpty() || data.size > MAX_IMAGE_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return false
        return try {
            val scale = minOf(1.0, MAX_DIMENSION.toDouble() / maxOf(bitmap.width, bitmap.height))
            val resized = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            try {
                target.outputStream().use { resized.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally { if (resized !== bitmap) resized.recycle() }
        } finally { bitmap.recycle() }
    }
}
