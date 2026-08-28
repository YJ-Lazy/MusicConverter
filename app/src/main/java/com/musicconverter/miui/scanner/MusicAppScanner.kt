package com.musicconverter.miui.scanner

import android.content.Context
import android.content.pm.PackageManager

data class MusicAppInfo(val label: String, val packageName: String, val commonPaths: List<String>)

object MusicAppScanner {
    private data class Known(val pkg: String, val name: String, val paths: List<String>)
    private val known = listOf(
        Known("com.miui.player", "小米音乐", listOf("/Music", "/MIUI/music")),
        Known("com.netease.cloudmusic", "网易云音乐", listOf("/netease/cloudmusic/Music", "/Music/netease")),
        Known("com.tencent.qqmusic", "QQ音乐", listOf("/qqmusic/song", "/Music/QQMusic")),
        Known("com.kugou.android", "酷狗音乐", listOf("/kugou/download", "/kgmusic/download")),
        Known("cn.kuwo.player", "酷我音乐", listOf("/kuwo/music", "/Music/Kuwo")),
        Known("com.spotify.music", "Spotify", listOf("应用私有目录（需由应用自身导出）"))
    )

    fun scan(context: Context): List<MusicAppInfo> {
        val pm = context.packageManager
        return known.mapNotNull { k ->
            try {
                val ai = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pm.getApplicationInfo(k.pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION") pm.getApplicationInfo(k.pkg, 0)
                }
                MusicAppInfo(pm.getApplicationLabel(ai).toString().ifBlank { k.name }, k.pkg, k.paths)
            } catch (_: Throwable) { null }
        }
    }
}
