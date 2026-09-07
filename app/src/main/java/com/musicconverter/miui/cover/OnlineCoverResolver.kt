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

object OnlineCoverResolver {
    private const val PREFS = "online_cover_cache_v2"
    private const val MISS_PREFIX = "__MISS__:"
    private const val MISS_RETRY_MS = 6L * 60L * 60L * 1000L

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap<String, MutableList<(String?) -> Unit>>()

    fun resolve(
        context: Context,
        title: String,
        artist: String,
        forceRefresh: Boolean = false,
        callback: (String?) -> Unit
    ) {
        val cleanTitle = clean(title)
        val cleanArtist = clean(artist)
            .takeUnless { it.equals("未知歌手", true) || it.equals("<unknown>", true) }
            .orEmpty()

        if (cleanTitle.isBlank() || cleanTitle == "未知歌曲") {
            callback(null)
            return
        }

        val key = (cleanTitle + "|" + cleanArtist).lowercase()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(key, null)

        if (!forceRefresh && !cached.isNullOrBlank()) {
            if (!cached.startsWith(MISS_PREFIX)) {
                callback(cached)
                return
            }
            val missAt = cached.removePrefix(MISS_PREFIX).toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - missAt < MISS_RETRY_MS) {
                callback(null)
                return
            }
            prefs.edit().remove(key).apply()
        } else if (forceRefresh && !cached.isNullOrBlank()) {
            // Local/system artwork or a previously cached remote URL failed to load.
            // Bypass the cover cache once and perform a fresh lookup.
            prefs.edit().remove(key).apply()
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
            if (result.isNullOrBlank()) {
                prefs.edit().putString(key, MISS_PREFIX + System.currentTimeMillis()).apply()
            } else {
                prefs.edit().putString(key, result).apply()
            }

            val callbacks = synchronized(inFlight) { inFlight.remove(key).orEmpty() }
            main.post { callbacks.forEach { it(result) } }
        }
    }

    private fun search(title: String, artist: String): String? {
        for (country in arrayOf("CN", "HK", "US", "JP")) {
            searchApple(title, artist, country)?.let { return it }
        }

        if (artist.isNotBlank()) {
            for (country in arrayOf("CN", "HK")) {
                searchApple(title, "", country)?.let { return it }
            }
        }

        return searchNetease(title, artist)
            ?: if (artist.isNotBlank()) searchNetease(title, "") else null
    }

    private fun searchApple(title: String, artist: String, country: String): String? {
        val term = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val q = URLEncoder.encode(term, "UTF-8")
        val c = open(
            URL("https://itunes.apple.com/search?term=$q&entity=song&limit=8&country=$country"),
            "https://music.apple.com/"
        )

        try {
            if (c.responseCode !in 200..299) return null
            val arr = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                .optJSONArray("results") ?: return null
            if (arr.length() == 0) return null

            val titleKey = norm(title)
            val artistKey = norm(artist)
            var fallback: String? = null

            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val artwork = item.optString("artworkUrl100")
                    .takeIf { it.startsWith("http", true) } ?: continue

                val large = artwork
                    .replace("100x100bb", "600x600bb")
                    .replace("http://", "https://")

                if (fallback == null) fallback = large

                val remoteTitle = norm(item.optString("trackName"))
                val remoteArtist = norm(item.optString("artistName"))
                val titleMatch = titleKey.isNotBlank() &&
                    (remoteTitle.contains(titleKey) || titleKey.contains(remoteTitle))
                val artistMatch = artistKey.isBlank() ||
                    remoteArtist.contains(artistKey) || artistKey.contains(remoteArtist)

                if (titleMatch && artistMatch) return large
            }
            return fallback
        } finally {
            c.disconnect()
        }
    }

    private fun searchNetease(title: String, artist: String): String? {
        val term = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        val q = URLEncoder.encode(term, "UTF-8")
        val c = open(
            URL("https://music.163.com/api/search/get/web?csrf_token=&s=$q&type=1&offset=0&total=true&limit=8"),
            "https://music.163.com/"
        )

        try {
            if (c.responseCode !in 200..299) return null

            val songs = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                .optJSONObject("result")?.optJSONArray("songs") ?: return null
            if (songs.length() == 0) return null

            val titleKey = norm(title)
            val artistKey = norm(artist)
            var fallback: String? = null

            for (i in 0 until songs.length()) {
                val song = songs.optJSONObject(i) ?: continue
                val pic = song.optJSONObject("album")?.optString("picUrl")
                    ?.takeIf { it.startsWith("http", true) }
                    ?.replace("http://", "https://")
                    ?: continue

                val cover = if (pic.contains("?")) pic else "$pic?param=600y600"
                if (fallback == null) fallback = cover

                val remoteTitle = norm(song.optString("name"))
                val artists = song.optJSONArray("artists")
                val remoteArtists = buildString {
                    if (artists != null) {
                        for (j in 0 until artists.length()) {
                            if (isNotEmpty()) append(' ')
                            append(artists.optJSONObject(j)?.optString("name").orEmpty())
                        }
                    }
                }
                val remoteArtist = norm(remoteArtists)

                val titleMatch = titleKey.isNotBlank() &&
                    (remoteTitle.contains(titleKey) || titleKey.contains(remoteTitle))
                val artistMatch = artistKey.isBlank() ||
                    remoteArtist.contains(artistKey) || artistKey.contains(remoteArtist)

                if (titleMatch && artistMatch) return cover
            }
            return fallback
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: URL, referer: String): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5500
            readTimeout = 7500
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            )
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        }
    }

    private fun clean(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .replace(Regex("[（(].*?[）)]"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .trim()

    private fun norm(value: String): String = clean(value)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
