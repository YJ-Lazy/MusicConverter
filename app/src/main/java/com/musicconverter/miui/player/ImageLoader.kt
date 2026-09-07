package com.musicconverter.miui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

object ImageLoader {
    fun load(url: String?, view: ImageView, onResult: ((Boolean) -> Unit)? = null) {
        if (url.isNullOrBlank()) {
            view.setImageDrawable(null)
            onResult?.invoke(false)
            return
        }

        Thread {
            val bmp = runCatching { decode(url, view) }.getOrNull()
            view.post {
                if (bmp != null) {
                    view.setImageBitmap(bmp)
                    onResult?.invoke(true)
                } else {
                    onResult?.invoke(false)
                }
            }
        }.start()
    }

    private fun decode(value: String, view: ImageView): Bitmap? {
        return when {
            value.startsWith("media-thumb:") -> {
                val uri = Uri.parse(value.removePrefix("media-thumb:"))
                view.context.contentResolver.loadThumbnail(uri, Size(600, 600), null)
            }
            value.startsWith("content://") -> {
                view.context.contentResolver.openInputStream(Uri.parse(value))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }
            value.startsWith("file://") -> {
                view.context.contentResolver.openInputStream(Uri.parse(value))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }
            value.startsWith("http://", true) || value.startsWith("https://", true) -> {
                val c = (URL(value).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 7000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MusicConverter/2.4 Android")
                }
                try {
                    if (c.responseCode !in 200..299) return null
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    c.disconnect()
                }
            }
            else -> null
        }
    }
}
