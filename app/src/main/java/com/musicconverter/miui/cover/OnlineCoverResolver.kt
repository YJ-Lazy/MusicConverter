package com.musicconverter.miui.cover

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 本地歌曲缺少封面时的轻量联网补全器。
 * - 仅在 artwork 为空时调用
 * - 查询结果持久缓存，避免每次进入应用重复联网
 * - 最多 3 个并发请求，避免首页一次渲染大量歌曲时卡顿/打爆网络
 * - 不修改音频文件本身，只给应用内 Track 补充 artwork URL
 */
object OnlineCoverResolver {
    private const val PREFS = "online_cover_cache_v1"
    private const val MISS = "__MISS__"
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap<String, MutableList<(String?) -> Unit>>()

    fun resolve(context: Context, title: String, artist: String, callback: (String?) -> Unit) {
        val cleanTitle = clean(title)
        val cleanArtist = clean(artist).takeUnless { it == "未知歌手" || it == "<unknown>" }.orEmpty()
        if (cleanTitle.isBlank() || cleanTitle == "未知歌曲") {
            callback(null)
            return
        }

        val key = (cleanTitle + "|" + cleanArtist).lowercase()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(key)) {
            val cached = prefs.getString(key, MISS)
            callback(cached?.takeUnless { it == MISS })
            return
        }

        synchronized(inFlight) {
            val waiters = inFlight[key]
            if (waiters != null) {
                waiters += callback
                return
            }
            inFlight[key] = mutableListOf(callback)
        }

        executor.execute {
            val result = runCatching { search(cleanTitle, cleanArtist) }.getOrNull()
            prefs.edit().putString(key, result ?: MISS).apply()
            val callbacks = synchronized(inFlight) { inFlight.remove(key).orEmpty() }
            main.post { callbacks.forEach { it(result) } }
        }
    }

    private fun search(title: String, artist: String): String? {
        val term = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val q = URLEncoder.encode(term, "UTF-8")
        val url = URL("https://itunes.apple.com/search?term=$q&entity=song&limit=5&country=CN")
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 6500
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MusicConverter/2.1 Android")
        }
        try {
            if (c.responseCode !in 200..299) return null
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).optJSONArray("results") ?: return null
            if (arr.length() == 0) return null

            val titleKey = norm(title)
            val artistKey = norm(artist)
            var fallback: String? = null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val artwork = item.optString("artworkUrl100").takeIf { it.startsWith("http") } ?: continue
                val large = artwork
                    .replace("100x100bb", "600x600bb")
                    .replace("http://", "https://")
                if (fallback == null) fallback = large

                val remoteTitle = norm(item.optString("trackName"))
                val remoteArtist = norm(item.optString("artistName"))
                val titleMatch = titleKey.isNotBlank() && (remoteTitle.contains(titleKey) || titleKey.contains(remoteTitle))
                val artistMatch = artistKey.isBlank() || remoteArtist.contains(artistKey) || artistKey.contains(remoteArtist)
                if (titleMatch && artistMatch) return large
            }
            return fallback
        } finally {
            c.disconnect()
        }
    }

    private fun clean(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[（(].*?[）)]"), " ")
        .trim()

    private fun norm(value: String): String = clean(value)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
