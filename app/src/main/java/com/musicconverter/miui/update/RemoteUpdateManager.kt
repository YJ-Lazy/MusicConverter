package com.musicconverter.miui.update

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RemoteUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val title: String,
    val changelog: String,
    val quarkUrl: String,
    val lanzouUrl: String,
    val lanzouPassword: String,
    val force: Boolean,
    val minSupportedVersionCode: Int
)

sealed class UpdateCheckResult {
    data class Available(val info: RemoteUpdateInfo) : UpdateCheckResult()
    data class Latest(val info: RemoteUpdateInfo?) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object RemoteUpdateManager {
    // GitHub 为主版本清单；腾讯文档作为国内备用版本检查源。
    const val GITHUB_MANIFEST_URL =
        "https://raw.githubusercontent.com/YJ-Lazy/MusicConverter/main/update/update.json"

    const val TENCENT_DOC_URL =
        "https://docs.qq.com/doc/DQnB4ZVJST2xRR2h5"

    // 保留旧常量名，避免其他调用点受影响。
    const val MANIFEST_URL = GITHUB_MANIFEST_URL

    private const val PREFS = "remote_update_preferences"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check"
    private const val KEY_IGNORED_VERSION_CODE = "ignored_version_code"

    // 已经确认过的强制更新会持久化保存。
    // 这样用户在看到强制更新后，即使断网重开 APP，也不能绕过更新门禁。
    private const val KEY_FORCE_VERSION_CODE = "force_version_code"
    private const val KEY_FORCE_VERSION_NAME = "force_version_name"
    private const val KEY_FORCE_TITLE = "force_title"
    private const val KEY_FORCE_CHANGELOG = "force_changelog"
    private const val KEY_FORCE_QUARK_URL = "force_quark_url"
    private const val KEY_FORCE_LANZOU_URL = "force_lanzou_url"
    private const val KEY_FORCE_LANZOU_PASSWORD = "force_lanzou_password"
    private const val KEY_FORCE_MIN_SUPPORTED = "force_min_supported"

    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    /**
     * 用户忽略某个版本后，只有远程 versionCode 至少再增加 2，才恢复自动提醒。
     * 例如忽略 15：16 不提醒，17 再提醒。手动“检查更新”始终可以查看。
     */
    private const val REMIND_AFTER_VERSION_GAP = 2

    fun cacheMandatoryUpdate(context: Context, info: RemoteUpdateInfo) {
        val requiredCode = maxOf(info.versionCode, info.minSupportedVersionCode)
        if (requiredCode <= 0) return

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_FORCE_VERSION_CODE, requiredCode)
            .putString(KEY_FORCE_VERSION_NAME, info.versionName)
            .putString(KEY_FORCE_TITLE, info.title)
            .putString(KEY_FORCE_CHANGELOG, info.changelog)
            .putString(KEY_FORCE_QUARK_URL, info.quarkUrl)
            .putString(KEY_FORCE_LANZOU_URL, info.lanzouUrl)
            .putString(KEY_FORCE_LANZOU_PASSWORD, info.lanzouPassword)
            .putInt(KEY_FORCE_MIN_SUPPORTED, info.minSupportedVersionCode)
            .apply()
    }

    fun cachedMandatoryUpdate(context: Context, currentVersionCode: Int): RemoteUpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val requiredCode = prefs.getInt(KEY_FORCE_VERSION_CODE, 0)

        if (requiredCode <= 0) return null
        if (currentVersionCode >= requiredCode) {
            clearMandatoryUpdate(context)
            return null
        }

        return RemoteUpdateInfo(
            versionCode = requiredCode,
            versionName = prefs.getString(KEY_FORCE_VERSION_NAME, "必须更新") ?: "必须更新",
            title = prefs.getString(KEY_FORCE_TITLE, "必须更新 MusicConverter") ?: "必须更新 MusicConverter",
            changelog = prefs.getString(KEY_FORCE_CHANGELOG, "") ?: "",
            quarkUrl = prefs.getString(KEY_FORCE_QUARK_URL, "") ?: "",
            lanzouUrl = prefs.getString(KEY_FORCE_LANZOU_URL, "") ?: "",
            lanzouPassword = prefs.getString(KEY_FORCE_LANZOU_PASSWORD, "") ?: "",
            force = true,
            minSupportedVersionCode = prefs.getInt(KEY_FORCE_MIN_SUPPORTED, requiredCode)
        )
    }

    fun clearMandatoryUpdate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FORCE_VERSION_CODE)
            .remove(KEY_FORCE_VERSION_NAME)
            .remove(KEY_FORCE_TITLE)
            .remove(KEY_FORCE_CHANGELOG)
            .remove(KEY_FORCE_QUARK_URL)
            .remove(KEY_FORCE_LANZOU_URL)
            .remove(KEY_FORCE_LANZOU_PASSWORD)
            .remove(KEY_FORCE_MIN_SUPPORTED)
            .apply()
    }

    fun isMandatory(info: RemoteUpdateInfo, currentVersionCode: Int): Boolean {
        return info.force || currentVersionCode < info.minSupportedVersionCode
    }

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AUTO_CHECK, 0L)
        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS
    }

    fun markAutoChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis())
            .apply()
    }

    fun ignoreVersion(context: Context, versionCode: Int) {
        if (versionCode <= 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_IGNORED_VERSION_CODE, versionCode)
            .apply()
    }

    fun ignoredVersionCode(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_IGNORED_VERSION_CODE, 0)
    }

    fun clearIgnoredVersion(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_IGNORED_VERSION_CODE)
            .apply()
    }

    fun clearIgnoredVersionIfInstalled(context: Context, currentVersionCode: Int) {
        val ignored = ignoredVersionCode(context)
        if (ignored > 0 && currentVersionCode >= ignored) {
            clearIgnoredVersion(context)
        }
    }

    fun shouldSuppressIgnoredUpdate(
        context: Context,
        remoteVersionCode: Int,
        currentVersionCode: Int
    ): Boolean {
        val ignored = ignoredVersionCode(context)
        if (ignored <= 0) return false

        if (currentVersionCode >= ignored) {
            clearIgnoredVersion(context)
            return false
        }

        // 同一个被忽略版本，或只更新了 1 个 versionCode，都不再弹自动提醒。
        return remoteVersionCode < ignored + REMIND_AFTER_VERSION_GAP
    }

    fun check(currentVersionCode: Int): UpdateCheckResult {
        // 版本检查策略：
        // 1. 永远优先访问 GitHub 主清单；
        // 2. GitHub 只要成功返回并能解析，就直接采用 GitHub 结果；
        // 3. 只有 GitHub 无法访问 / HTTP 失败 / 内容无效时，才调用腾讯文档兜底。
        val github = fetchManifest(
            url = GITHUB_MANIFEST_URL,
            sourceName = "GitHub",
            isTencentDoc = false
        )

        return when (github) {
            is ManifestFetch.Success -> {
                evaluateManifest(github.info, currentVersionCode)
            }

            is ManifestFetch.Failure -> {
                val tencent = fetchTencentManifest()

                when (tencent) {
                    is ManifestFetch.Success -> {
                        evaluateManifest(tencent.info, currentVersionCode)
                    }

                    is ManifestFetch.Failure -> {
                        UpdateCheckResult.Error(
                            "版本检查失败：GitHub：${github.message}；腾讯文档：${tencent.message}"
                        )
                    }
                }
            }
        }
    }

    private sealed class ManifestFetch {
        data class Success(
            val info: RemoteUpdateInfo,
            val sourceName: String
        ) : ManifestFetch()

        data class Failure(
            val sourceName: String,
            val message: String
        ) : ManifestFetch()
    }

    /**
     * 腾讯文档页面正文通常由前端二次加载，直接 GET /doc/... 可能只拿到页面壳，
     * 因此备用源优先读取公开 opendoc 数据，再回退到旧的网页正文解析。
     */
    private fun fetchTencentManifest(): ManifestFetch {
        val docId = TENCENT_DOC_URL
            .substringAfterLast("/")
            .substringBefore("?")
            .trim()

        if (docId.isBlank()) {
            return ManifestFetch.Failure("腾讯文档", "无法解析腾讯文档 ID")
        }

        val apiUrl =
            "https://docs.qq.com/dop-api/opendoc" +
                "?id=$docId" +
                "&normal=1" +
                "&outformat=1" +
                "&noEscape=1" +
                "&doc_chunk_flag=1"

        val apiResult = fetchTencentOpenDoc(apiUrl)
        if (apiResult is ManifestFetch.Success) {
            return apiResult
        }

        // 某些账号/分享方式下 opendoc 可能不可用，继续兼容原来的分享页抓取。
        val pageResult = fetchManifest(
            url = TENCENT_DOC_URL,
            sourceName = "腾讯文档",
            isTencentDoc = true
        )
        if (pageResult is ManifestFetch.Success) {
            return pageResult
        }

        val apiMessage = (apiResult as? ManifestFetch.Failure)?.message ?: "未知错误"
        val pageMessage = (pageResult as? ManifestFetch.Failure)?.message ?: "未知错误"
        return ManifestFetch.Failure(
            "腾讯文档",
            "公开数据接口：$apiMessage；分享页：$pageMessage"
        )
    }

    private fun fetchTencentOpenDoc(url: String): ManifestFetch {
        return try {
            val separator = if (url.contains("?")) "&" else "?"
            val requestUrl = "$url${separator}_mc_ts=${System.currentTimeMillis()}"
            val conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Referer", TENCENT_DOC_URL)
                setRequestProperty("Origin", "https://docs.qq.com")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
                )
                useCaches = false
                instanceFollowRedirects = true
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return ManifestFetch.Failure("腾讯文档", "opendoc HTTP $code")
                }

                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (body.isBlank()) {
                    return ManifestFetch.Failure("腾讯文档", "opendoc 返回内容为空")
                }

                val manifest = extractManifestFromTencentOpenDoc(body)
                ManifestFetch.Success(
                    info = parseManifest(manifest),
                    sourceName = "腾讯文档"
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            ManifestFetch.Failure(
                "腾讯文档",
                e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun fetchManifest(
        url: String,
        sourceName: String,
        isTencentDoc: Boolean
    ): ManifestFetch {
        return try {
            val separator = if (url.contains("?")) "&" else "?"
            val requestUrl = "$url${separator}_mc_ts=${System.currentTimeMillis()}"
            val conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty(
                    "Accept",
                    if (isTencentDoc) "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
                    else "application/json"
                )
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty(
                    "User-Agent",
                    if (isTencentDoc) {
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
                    } else {
                        "MusicConverter-Android-Updater"
                    }
                )
                useCaches = false
                instanceFollowRedirects = true
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return ManifestFetch.Failure(sourceName, "HTTP $code")
                }

                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (body.isBlank()) {
                    return ManifestFetch.Failure(sourceName, "返回内容为空")
                }

                val json = if (isTencentDoc) {
                    extractManifestFromTencentDoc(body)
                } else {
                    JSONObject(body)
                }

                ManifestFetch.Success(
                    info = parseManifest(json),
                    sourceName = sourceName
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            ManifestFetch.Failure(
                sourceName,
                e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun parseManifest(json: JSONObject): RemoteUpdateInfo {
        return RemoteUpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.optString("versionName", "未知版本"),
            title = json.optString("title", "发现新版本"),
            changelog = readChangelog(json),
            quarkUrl = json.optString("quarkUrl").trim(),
            lanzouUrl = json.optString("lanzouUrl").trim(),
            lanzouPassword = json.optString("lanzouPassword").trim(),
            force = json.optBoolean("force", false),
            minSupportedVersionCode = json.optInt("minSupportedVersionCode", 0)
        )
    }

    private fun evaluateManifest(
        info: RemoteUpdateInfo,
        currentVersionCode: Int
    ): UpdateCheckResult {
        if (info.versionCode <= currentVersionCode) {
            return UpdateCheckResult.Latest(info)
        }

        val hasQuark = info.quarkUrl.startsWith("https://pan.quark.cn/")
        val hasLanzou = isLanzouUrl(info.lanzouUrl)
        return when {
            !hasQuark && !hasLanzou ->
                UpdateCheckResult.Error("远程更新清单没有可用的下载链接")
            info.lanzouUrl.isNotBlank() && !hasLanzou ->
                UpdateCheckResult.Error("远程更新清单中的蓝奏云链接无效")
            else -> UpdateCheckResult.Available(info)
        }
    }

    /**
     * 解析腾讯文档公开 opendoc 返回值。
     *
     * 兼容：
     * 1. 直接 JSON；
     * 2. clientVarsCallback("...") 包装；
     * 3. clientVars / collab_client_vars / initialAttributedText 深层文本；
     * 4. 正文被拆成多个字符串片段；
     * 5. 正文使用 HTML/JS 转义。
     */
    private fun extractManifestFromTencentOpenDoc(body: String): JSONObject {
        val normalizedBody = normalizeTencentDocText(body)

        // 如果 opendoc 本身直接返回了更新清单。
        runCatching {
            val direct = JSONObject(normalizedBody.trim())
            if (direct.has("versionCode")) return direct
        }

        val root = parseTencentOpenDocRoot(normalizedBody)

        // 递归扫描每一个字符串值，优先找完整 JSON。
        val strings = ArrayList<String>()
        collectJsonStrings(root, strings)

        for (raw in strings) {
            val value = normalizeTencentDocText(raw)
            runCatching {
                val direct = JSONObject(value.trim())
                if (direct.has("versionCode")) return direct
            }
            extractJsonObjectContainingVersionCode(value)?.let {
                return JSONObject(it)
            }
        }

        // 腾讯文档富文本经常把正文拆成多个 c/text 字符串片段。
        // 按 JSON 原始遍历顺序拼回正文，再解析一次。
        val joinedNoSeparator = normalizeTencentDocText(strings.joinToString(""))
        extractJsonObjectContainingVersionCode(joinedNoSeparator)?.let {
            return JSONObject(it)
        }

        val joinedLines = normalizeTencentDocText(strings.joinToString("\n"))
        extractJsonObjectContainingVersionCode(joinedLines)?.let {
            return JSONObject(it)
        }

        // 最后尝试从拼接文本中按字段提取，解决花括号被富文本结构拆开的情况。
        extractManifestFields(joinedLines)?.let { return it }
        extractManifestFields(joinedNoSeparator)?.let { return it }

        throw IllegalArgumentException(
            "腾讯文档公开数据中未找到可解析的 MusicConverter 更新清单"
        )
    }

    private fun parseTencentOpenDocRoot(body: String): Any {
        val trimmed = body.trim()

        runCatching { return JSONObject(trimmed) }
        runCatching { return JSONArray(trimmed) }

        // 兼容 clientVarsCallback("...") / clientVarsCallback({...})
        val callbackPrefix = "clientVarsCallback("
        val callbackIndex = trimmed.indexOf(callbackPrefix)
        if (callbackIndex >= 0) {
            val contentStart = callbackIndex + callbackPrefix.length
            val contentEnd = trimmed.lastIndexOf(')')
            if (contentEnd > contentStart) {
                var payload = trimmed.substring(contentStart, contentEnd).trim()
                if (payload.startsWith("\"") && payload.endsWith("\"") && payload.length >= 2) {
                    payload = decodeJsonQuotedString(payload)
                }
                val normalized = normalizeTencentDocText(payload)
                runCatching { return JSONObject(normalized) }
                runCatching { return JSONArray(normalized) }
            }
        }

        // 即使根结构不标准，也包装成字符串供后续扫描。
        return JSONObject().put("raw", trimmed)
    }

    private fun decodeJsonQuotedString(value: String): String {
        return runCatching {
            JSONArray("[$value]").getString(0)
        }.getOrElse {
            value.removePrefix("\"").removeSuffix("\"")
        }
    }

    private fun collectJsonStrings(value: Any?, output: MutableList<String>) {
        when (value) {
            null, JSONObject.NULL -> Unit
            is String -> output += value
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectJsonStrings(value.opt(key), output)
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectJsonStrings(value.opt(i), output)
                }
            }
            else -> Unit
        }
    }

    /**
     * 分享页旧兼容路径。
     */
    private fun extractManifestFromTencentDoc(body: String): JSONObject {
        val trimmed = body.trim()
        runCatching {
            val direct = JSONObject(trimmed)
            if (direct.has("versionCode")) return direct
        }

        val normalized = normalizeTencentDocText(body)

        val begin = "MUSICCONVERTER_UPDATE_BEGIN"
        val end = "MUSICCONVERTER_UPDATE_END"
        val beginIndex = normalized.indexOf(begin)
        if (beginIndex >= 0) {
            val endIndex = normalized.indexOf(end, beginIndex + begin.length)
            if (endIndex > beginIndex) {
                val payload = normalized
                    .substring(beginIndex + begin.length, endIndex)
                    .trim()
                runCatching { return JSONObject(payload) }
            }
        }

        extractJsonObjectContainingVersionCode(normalized)?.let {
            return JSONObject(it)
        }

        extractManifestFields(normalized)?.let { return it }

        throw IllegalArgumentException(
            "腾讯文档中未找到可解析的 MusicConverter 更新清单"
        )
    }

    private fun normalizeTencentDocText(value: String): String {
        var out = value
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\u0022", "\"")
            .replace("\\u003c", "<")
            .replace("\\u003e", ">")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

        // 常见 HTML 空白。
        out = out
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")

        return out
    }

    private fun extractJsonObjectContainingVersionCode(value: String): String? {
        val positions = ArrayList<Int>()
        var from = 0
        while (true) {
            val p = value.indexOf("versionCode", from)
            if (p < 0) break
            positions += p
            from = p + "versionCode".length
        }

        for (keyPos in positions) {
            var start = keyPos
            while (start >= 0) {
                if (value[start] == '{') {
                    val candidate = findBalancedJsonObject(value, start)
                    if (candidate != null &&
                        candidate.contains("versionCode") &&
                        runCatching {
                            JSONObject(candidate).has("versionCode")
                        }.getOrDefault(false)
                    ) {
                        return candidate
                    }
                }
                start--
            }
        }
        return null
    }

    /**
     * 当腾讯富文本把 JSON 的结构字符拆散时，按字段名做最后兜底。
     * 必须存在 versionCode，避免误把普通页面内容当成更新清单。
     */
    private fun extractManifestFields(value: String): JSONObject? {
        fun stringField(name: String): String? {
            val quoted = Regex(
                """["']?$name["']?\s*:\s*["']([^"']*)["']""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(value)?.groupValues?.getOrNull(1)
            return quoted?.trim()
        }

        fun intField(name: String): Int? {
            return Regex(
                """["']?$name["']?\s*:\s*(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        fun boolField(name: String): Boolean? {
            return Regex(
                """["']?$name["']?\s*:\s*(true|false)""",
                RegexOption.IGNORE_CASE
            ).find(value)?.groupValues?.getOrNull(1)?.lowercase()?.toBooleanStrictOrNull()
        }

        val versionCode = intField("versionCode") ?: return null

        val json = JSONObject()
            .put("versionCode", versionCode)
            .put("versionName", stringField("versionName") ?: "未知版本")
            .put("title", stringField("title") ?: "发现新版本")
            .put("quarkUrl", stringField("quarkUrl") ?: "")
            .put("lanzouUrl", stringField("lanzouUrl") ?: "")
            .put("lanzouPassword", stringField("lanzouPassword") ?: "")
            .put("force", boolField("force") ?: false)
            .put("minSupportedVersionCode", intField("minSupportedVersionCode") ?: 0)

        // changelog 数组优先。
        val changelogArrayText = Regex(
            """"changelog"\s*:\s*\[(.*?)]""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(value)?.groupValues?.getOrNull(1)

        if (!changelogArrayText.isNullOrBlank()) {
            val changelog = JSONArray()
            Regex(""""([^"]+)"""").findAll(changelogArrayText).forEach {
                val line = it.groupValues[1].trim()
                if (line.isNotEmpty()) changelog.put(line)
            }
            if (changelog.length() > 0) {
                json.put("changelog", changelog)
            }
        }

        if (!json.has("changelog")) {
            json.put(
                "changelog",
                stringField("changelog") ?: "本次更新包含功能改进与问题修复。"
            )
        }

        return json
    }

    private fun findBalancedJsonObject(value: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until value.length) {
            val c = value[i]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }

            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return value.substring(start, i + 1)
                    }
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    private fun isLanzouUrl(value: String): Boolean {
        if (value.isBlank()) return false
        return runCatching {
            val host = URL(value).host.lowercase()
            value.startsWith("https://") && host.contains("lanzou")
        }.getOrDefault(false)
    }

    private fun readChangelog(json: JSONObject): String {
        val array = json.optJSONArray("changelog")
        if (array != null) {
            val lines = ArrayList<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty()) lines += "• $value"
            }
            return lines.joinToString("\n")
        }
        return json.optString("changelog", "本次更新包含功能改进与问题修复。")
    }
}
