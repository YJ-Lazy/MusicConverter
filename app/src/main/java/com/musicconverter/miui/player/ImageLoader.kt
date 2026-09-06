package com.musicconverter.miui.player

import android.graphics.BitmapFactory
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

object ImageLoader {
    fun load(url: String?, view: ImageView) {
        if (url.isNullOrBlank()) {
            view.setImageDrawable(null)
            return
        }
        Thread {
            val bmp = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 7000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) view.post { view.setImageBitmap(bmp) }
        }.start()
    }
}
