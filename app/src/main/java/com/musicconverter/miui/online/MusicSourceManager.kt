package com.musicconverter.miui.online

import android.content.Context
import com.musicconverter.miui.player.Track
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class SourceKind(val code: String, val label: String) {
    TX("tx", "QQ"),
    WY("wy", "网易"),
    KW("kw", "酷我"),
    KG("kg", "酷狗"),
    MG("mg", "咪咕")
}

data class SourceCandidate(
    val source: SourceKind,
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artwork: String? = null
)

data class AggregatedSong(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String?,
    val candidates: List<SourceCandidate>
) {
    val sourceLabels: String
        get() = candidates.joinToString(" · ") { it.source.label }
}

object MusicSourceManager {
    private const val PREF = "online_music_sources"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ORDER = "order"
    private const val API_BASE = "https://lxmusicapi.onrender.com"
    private const val API_KEY = "share-v3"

    private val defaultOrder = listOf(
        SourceKind.TX, SourceKind.WY, SourceKind.KW, SourceKind.KG, SourceKind.MG
    )
    @Volatile private var lastResolveAt = 0L

    fun sourceOrder(context: Context): List<SourceKind> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null)
            ?.split(",")
            ?.mapNotNull { code -> SourceKind.values().firstOrNull { it.code == code } }
            .orEmpty()
        return (raw + defaultOrder).distinct()
    }

    fun enabledSources(context: Context): List<SourceKind> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY_ENABLED, null)
        val order = sourceOrder(context)
        return if (raw == null) order else order.filter { it.code in raw }
    }

    fun setOrder(context: Context, sources: List<SourceKind>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, sources.distinct().joinToString(",") { it.code }).apply()
    }

    fun setEnabled(context: Context, sources: Collection<SourceKind>) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENABLED, sources.map { it.code }.toSet()).apply()
    }

    fun searchAll(
        context: Context,
        keyword: String,
        callback: (Result<List<AggregatedSong>>) -> Unit
    ) {
        val sources = enabledSources(context)
        if (sources.isEmpty()) {
            callback(Result.failure(IllegalStateException("请至少启用一个音源")))
            return
        }
        Thread {
            callback(runCatching {
                val latch = CountDownLatch(sources.size)
                val lock = Any()
                val all = mutableListOf<SourceCandidate>()
                sources.forEach { source ->
                    Thread {
                        try {
                            val result = when (source) {
                                SourceKind.TX -> searchTx(keyword)
                                SourceKind.WY -> searchWy(keyword)
                                SourceKind.KW -> searchKw(keyword)
                                SourceKind.KG -> searchKg(keyword)
                                SourceKind.MG -> searchMg(keyword)
                            }
                            synchronized(lock) { all += result }
                        } catch (_: Throwable) {
                            // 单个音源失败不影响聚合结果
                        } finally {
                            latch.countDown()
                        }
                    }.start()
                }
                latch.await(12, TimeUnit.SECONDS)
                merge(all)
            })
        }.start()
    }

    fun resolveBest(
        context: Context,
        song: AggregatedSong,
        callback: (Result<Track>) -> Unit
    ) {
        Thread {
            callback(runCatching {
                val enabled = enabledSources(context)
                val candidates = song.candidates.sortedBy {
                    val i = enabled.indexOf(it.source)
                    if (i < 0) Int.MAX_VALUE else i
                }.filter { it.source in enabled }
                if (candidates.isEmpty()) error("没有可用候选音源")

                var lastError: Throwable? = null
                for (candidate in candidates) {
                    try {
                        val url = resolveUrl(candidate)
                        return@runCatching Track(
                            id = "${candidate.source.code}:${candidate.id}",
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            artwork = song.artwork ?: candidate.artwork,
                            streamUrl = url,
                            source = candidate.source.code
                        )
                    } catch (t: Throwable) {
                        lastError = t
                    }
                }
                throw lastError ?: IllegalStateException("全部音源均解析失败")
            })
        }.start()
    }

    fun healthCheck(source: SourceKind, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val results = when (source) {
                    SourceKind.TX -> searchTx("周杰伦")
                    SourceKind.WY -> searchWy("周杰伦")
                    SourceKind.KW -> searchKw("周杰伦")
                    SourceKind.KG -> searchKg("周杰伦")
                    SourceKind.MG -> searchMg("周杰伦")
                }
                callback(results.isNotEmpty(), if (results.isNotEmpty()) "搜索正常" else "无结果")
            } catch (t: Throwable) {
                callback(false, t.message ?: "请求失败")
            }
        }.start()
    }

    private fun merge(items: List<SourceCandidate>): List<AggregatedSong> {
        val groups = linkedMapOf<String, MutableList<SourceCandidate>>()
        items.forEach { c ->
            val key = normalize(c.title) + "|" + normalize(c.artist.substringBefore(",").substringBefore("、"))
            groups.getOrPut(key) { mutableListOf() }.add(c)
        }
        return groups.values.map { cs ->
            val first = cs.first()
            AggregatedSong(
                title = first.title,
                artist = first.artist,
                album = cs.firstOrNull { it.album.isNotBlank() }?.album.orEmpty(),
                artwork = cs.firstNotNullOfOrNull { it.artwork },
                candidates = cs.distinctBy { it.source to it.id }
            )
        }.sortedWith(compareByDescending<AggregatedSong> { it.candidates.size }.thenBy { it.title })
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("""[\s\-—_·•()（）\[\]【】"'“”‘’]+"""), "")

    private fun resolveUrl(candidate: SourceCandidate): String {
        var lastError: Throwable? = null
        // 高音质地址可能因版权/会员/节点状态不可用，自动降级到 128k。
        for (quality in listOf("320k", "128k")) {
            try {
                synchronized(this) {
                    val now = System.currentTimeMillis()
                    val wait = (1300L - (now - lastResolveAt)).coerceAtLeast(0L)
                    if (wait > 0) Thread.sleep(wait)
                    lastResolveAt = System.currentTimeMillis()
                }
                val encodedId = URLEncoder.encode(candidate.id, "UTF-8")
                val conn = open("$API_BASE/url/${candidate.source.code}/$encodedId/$quality")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "lx-music-request/2.6.0")
                conn.setRequestProperty("X-Request-Key", API_KEY)
                val raw = read(conn)
                val obj = JSONObject(raw)
                if (obj.optInt("code", -1) != 0) {
                    when (obj.optInt("code", -1)) {
                        1 -> error("${candidate.source.label}：当前 IP 被限制")
                        5 -> error("${candidate.source.label}：请求过快")
                        else -> error("${candidate.source.label}：${obj.optString("msg", "解析失败")}")
                    }
                }
                val rawUrl = extractResolvedUrl(obj)
                    ?: error("${candidate.source.label}：音源返回了空播放地址")
                val url = normalizeMediaUrl(rawUrl)
                validateMediaUrl(url, candidate)
                return url
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("${candidate.source.label}：无法取得可播放地址")
    }

    /**
     * Android 9+ 默认禁止明文 HTTP。部分音乐 CDN 仍返回 http:// 地址，
     * 这些 CDN 通常同时支持 HTTPS，因此在交给校验器和 MediaPlayer 前统一升级。
     */
    private fun normalizeMediaUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true)) {
            "https://" + trimmed.substring(7)
        } else {
            trimmed
        }
    }

    private fun extractResolvedUrl(obj: JSONObject): String? {
        fun valid(v: String?): String? = v?.trim()?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        }
        valid(obj.optString("url"))?.let { return it }
        val data = obj.optJSONObject("data")
        valid(data?.optString("url"))?.let { return it }
        val result = obj.optJSONObject("result")
        valid(result?.optString("url"))?.let { return it }
        return null
    }

    /**
     * 解析接口成功并不代表 CDN 地址真的能播放。这里先做一次极小范围请求，
     * 遇到 403、失效跳转、证书错误或 HTML 错误页时让上层自动切换下一音源。
     */
    private fun validateMediaUrl(url: String, candidate: SourceCandidate) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 7000
            readTimeout = 9000
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-1")
            setRequestProperty("Accept", "audio/*,*/*;q=0.8")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Mobile Safari/537.36")
            when (candidate.source) {
                SourceKind.TX -> setRequestProperty("Referer", "https://y.qq.com/")
                SourceKind.WY -> setRequestProperty("Referer", "https://music.163.com/")
                SourceKind.KW -> setRequestProperty("Referer", "https://www.kuwo.cn/")
                SourceKind.KG -> setRequestProperty("Referer", "https://www.kugou.com/")
                SourceKind.MG -> setRequestProperty("Referer", "https://m.music.migu.cn/")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 206) error("${candidate.source.label}：播放地址 HTTP $code")
            val type = conn.contentType.orEmpty().lowercase(Locale.ROOT)
            if (type.contains("text/html") || type.contains("application/json")) {
                error("${candidate.source.label}：播放地址已失效")
            }
            // 只验证响应，不读取整首音频。
            runCatching { conn.inputStream.close() }
        } finally {
            conn.disconnect()
        }
    }

    private fun searchTx(keyword: String): List<SourceCandidate> {
        val conn = open("https://u.y.qq.com/cgi-bin/musicu.fcg", "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Referer", "https://y.qq.com")
        val safe = keyword.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"req_1":{"method":"DoSearchForQQMusicDesktop","module":"music.search.SearchCgiService","param":{"num_per_page":20,"page_num":1,"query":"$safe","search_type":0}}}"""
        conn.outputStream.bufferedWriter().use { it.write(body) }
        val arr = JSONObject(read(conn)).getJSONObject("req_1").getJSONObject("data")
            .getJSONObject("body").getJSONObject("song").getJSONArray("list")
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val singers = s.optJSONArray("singer")
                val artist = buildList {
                    if (singers != null) for (j in 0 until singers.length()) {
                        singers.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.joinToString(", ")
                val album = s.optJSONObject("album")
                val mid = s.optString("mid", s.optString("songmid"))
                if (mid.isBlank()) continue
                val albumMid = album?.optString("mid").orEmpty()
                add(SourceCandidate(
                    SourceKind.TX, mid, s.optString("title", s.optString("name")),
                    artist, album?.optString("name").orEmpty(),
                    albumMid.takeIf { it.isNotBlank() }?.let {
                        "https://y.gtimg.cn/music/photo_new/T002R800x800M000$it.jpg"
                    }
                ))
            }
        }
    }

    private fun searchWy(keyword: String): List<SourceCandidate> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val conn = open("https://music.163.com/api/search/get/web?csrf_token=&s=$q&type=1&offset=0&total=true&limit=20")
        conn.setRequestProperty("Referer", "https://music.163.com/")
        val songs = JSONObject(read(conn)).optJSONObject("result")?.optJSONArray("songs")
            ?: return emptyList()
        return buildList {
            for (i in 0 until songs.length()) {
                val s = songs.getJSONObject(i)
                val artists = s.optJSONArray("artists")
                val artist = buildList {
                    if (artists != null) for (j in 0 until artists.length()) {
                        artists.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }.joinToString(", ")
                val album = s.optJSONObject("album")
                add(SourceCandidate(
                    SourceKind.WY,
                    s.optString("id"),
                    s.optString("name"),
                    artist,
                    album?.optString("name").orEmpty(),
                    album?.optString("picUrl")?.takeIf { it.isNotBlank() }
                ))
            }
        }.filter { it.id.isNotBlank() }
    }

    private fun searchKg(keyword: String): List<SourceCandidate> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val conn = open("https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$q&page=1&pagesize=20&showtype=1")
        val data = JSONObject(read(conn)).optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val s = data.getJSONObject(i)
                val hash = s.optString("hash")
                if (hash.isBlank()) continue
                add(SourceCandidate(
                    SourceKind.KG, hash,
                    s.optString("songname", s.optString("filename")),
                    s.optString("singername"),
                    s.optString("album_name"),
                    null
                ))
            }
        }
    }

    private fun searchKw(keyword: String): List<SourceCandidate> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val conn = open("https://search.kuwo.cn/r.s?all=$q&ft=music&itemset=web_2013&client=kt&pn=0&rn=20&rformat=json&encoding=utf8")
        conn.setRequestProperty("Referer", "https://www.kuwo.cn/")
        val obj = JSONObject(read(conn))
        val list = obj.optJSONArray("abslist") ?: obj.optJSONArray("musiclist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                val rid = s.optString("MUSICRID").removePrefix("MUSIC_")
                    .ifBlank { s.optString("rid").removePrefix("MUSIC_") }
                if (rid.isBlank()) continue
                add(SourceCandidate(
                    SourceKind.KW, rid,
                    s.optString("SONGNAME", s.optString("name")),
                    s.optString("ARTIST", s.optString("artist")),
                    s.optString("ALBUM", s.optString("album")),
                    s.optString("web_albumpic_short").takeIf { it.startsWith("http") }
                ))
            }
        }
    }

    private fun searchMg(keyword: String): List<SourceCandidate> {
        val q = URLEncoder.encode(keyword, "UTF-8")
        val conn = open("https://m.music.migu.cn/migu/remoting/scr_search_tag?keyword=$q&pgc=1&rows=20&type=2")
        conn.setRequestProperty("Referer", "https://m.music.migu.cn/")
        val obj = JSONObject(read(conn))
        val list = obj.optJSONArray("musics")
            ?: obj.optJSONObject("data")?.optJSONArray("songs")
            ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                val id = s.optString("copyrightId")
                    .ifBlank { s.optString("copyrightid") }
                    .ifBlank { s.optString("id") }
                if (id.isBlank()) continue
                val pic = s.optString("cover")
                    .ifBlank { s.optString("largePic") }
                    .ifBlank { s.optString("albumPicL") }
                add(SourceCandidate(
                    SourceKind.MG, id,
                    s.optString("songName", s.optString("name")),
                    s.optString("singerName", s.optString("artist")),
                    s.optString("albumName", s.optString("album")),
                    pic.takeIf { it.startsWith("http") }
                ))
            }
        }
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MusicConverter/2.1")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }

    private fun read(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code")
        return text
    }
}
