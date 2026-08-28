package com.musicconverter.miui.update

import android.content.Context
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
    const val GITHUB_MANIFEST_URL =
        "https://raw.githubusercontent.com/YJ-Lazy/MusicConverter/main/update/update.json"

    const val TENCENT_DOC_URL =
        "https://docs.qq.com/doc/DQnB4ZVJST2xRR2h5"

    const val MANIFEST_URL = GITHUB_MANIFEST_URL

    private const val PREFS = "remote_update_preferences"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check"
    private const val KEY_IGNORED_VERSION_CODE = "ignored_version_code"
    private const val KEY_FORCE_VERSION_CODE = "force_version_code"
    private const val KEY_FORCE_VERSION_NAME = "force_version_name"
    private const val KEY_FORCE_TITLE = "force_title"
    private const val KEY_FORCE_CHANGELOG = "force_changelog"
    private const val KEY_FORCE_QUARK_URL = "force_quark_url"
    private const val KEY_FORCE_LANZOU_URL = "force_lanzou_url"
    private const val KEY_FORCE_LANZOU_PASSWORD = "force_lanzou_password"
    private const val KEY_FORCE_MIN_SUPPORTED = "force_min_supported"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
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
        if (ignored > 0 && currentVersionCode >= ignored) clearIgnoredVersion(context)
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
        return remoteVersionCode < ignored + REMIND_AFTER_VERSION_GAP
    }

    fun check(currentVersionCode: Int): UpdateCheckResult {
        val github = fetchManifest(
            url = GITHUB_MANIFEST_URL,
            sourceName = "GitHub",
            isTencentDoc = false
        )

        return when (github) {
            is ManifestFetch.Success -> evaluateManifest(github.info, currentVersionCode)
            is ManifestFetch.Failure -> {
                val tencent = fetchManifest(
                    url = TENCENT_DOC_URL,
                    sourceName = "腾讯文档",
                    isTencentDoc = true
                )
                when (tencent) {
                    is ManifestFetch.Success -> evaluateManifest(tencent.info, currentVersionCode)
                    is ManifestFetch.Failure -> UpdateCheckResult.Error(
                        "版本检查失败：GitHub：${github.message}；腾讯文档：${tencent.message}"
                    )
                }
            }
        }
    }

    private sealed class ManifestFetch {
        data class Success(val info: RemoteUpdateInfo, val sourceName: String) : ManifestFetch()
        data class Failure(val sourceName: String, val message: String) : ManifestFetch()
    }

    private fun fetchManifest(url: String, sourceName: String, isTencentDoc: Boolean): ManifestFetch {
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
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
                    } else {
                        "MusicConverter-Android-Updater"
                    }
                )
                useCaches = false
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return ManifestFetch.Failure(sourceName, "HTTP $code")
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (body.isBlank()) return ManifestFetch.Failure(sourceName, "返回内容为空")
                val json = if (isTencentDoc) extractManifestFromTencentDoc(body) else JSONObject(body)
                ManifestFetch.Success(parseManifest(json), sourceName)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            ManifestFetch.Failure(sourceName, e.message ?: e.javaClass.simpleName)
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

    private fun evaluateManifest(info: RemoteUpdateInfo, currentVersionCode: Int): UpdateCheckResult {
        if (info.versionCode <= currentVersionCode) return UpdateCheckResult.Latest(info)
        val hasQuark = info.quarkUrl.startsWith("https://pan.quark.cn/")
        val hasLanzou = isLanzouUrl(info.lanzouUrl)
        return when {
            !hasQuark && !hasLanzou -> UpdateCheckResult.Error("远程更新清单没有可用的下载链接")
            info.lanzouUrl.isNotBlank() && !hasLanzou -> UpdateCheckResult.Error("远程更新清单中的蓝奏云链接无效")
            else -> UpdateCheckResult.Available(info)
        }
    }

    private fun extractManifestFromTencentDoc(body: String): JSONObject {
        val trimmed = body.trim()
        runCatching { return JSONObject(trimmed) }
        val normalized = normalizeTencentDocText(body)
        val begin = "MUSICCONVERTER_UPDATE_BEGIN"
        val end = "MUSICCONVERTER_UPDATE_END"
        val beginIndex = normalized.indexOf(begin)
        if (beginIndex >= 0) {
            val endIndex = normalized.indexOf(end, beginIndex + begin.length)
            if (endIndex > beginIndex) {
                val payload = normalized.substring(beginIndex + begin.length, endIndex).trim()
                runCatching { return JSONObject(payload) }
            }
        }
        extractJsonObjectContainingVersionCode(normalized)?.let { return JSONObject(it) }
        throw IllegalArgumentException("腾讯文档中未找到可解析的 MusicConverter 更新清单")
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
            .replace("\\/", "/")
        if ("\\\"versionCode\\\"" in out) {
            out = out.replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r")
        }
        return out
    }

    private fun extractJsonObjectContainingVersionCode(value: String): String? {
        val keyPositions = listOf(value.indexOf("\"versionCode\""), value.indexOf("versionCode"))
            .filter { it >= 0 }
        for (keyPos in keyPositions) {
            var start = keyPos
            while (start >= 0) {
                if (value[start] == '{') {
                    val candidate = findBalancedJsonObject(value, start)
                    if (candidate != null && candidate.contains("versionCode") &&
                        runCatching { JSONObject(candidate).has("versionCode") }.getOrDefault(false)
                    ) return candidate
                }
                start--
            }
        }
        return null
    }

    private fun findBalancedJsonObject(value: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until value.length) {
            val c = value[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return value.substring(start, i + 1)
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
