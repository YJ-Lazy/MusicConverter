package com.musicconverter.miui.scanner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.musicconverter.miui.core.AudioFormatDetector
import com.musicconverter.miui.core.IgnoredFormatPreferences
import java.io.File
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.LinkedHashSet

object FullStorageAudioScanner {

    /**
     * 仅在 Android 11+ 且用户主动授予 MANAGE_EXTERNAL_STORAGE 后使用。
     *
     * 与 SAF 扫描不同，这里直接遍历共享存储文件树，因此也能发现没有被
     * MediaStore 索引的 NCM / QMC / KGM / KWM 等加密音乐文件。
     */
    fun scan(
        context: Context,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<ScannedAudio> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !Environment.isExternalStorageManager()
        ) {
            throw SecurityException("尚未授予“所有文件访问权限”")
        }

        val roots = storageRoots(context)
        if (roots.isEmpty()) {
            throw IllegalStateException("没有找到可扫描的共享存储")
        }

        val outputDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "MusicConverter"
        ).canonicalFile

        data class PendingDir(
            val dir: File,
            val displayPath: String
        )

        val stack = ArrayDeque<PendingDir>()
        roots.forEach { (root, label) ->
            stack.add(PendingDir(root, label))
        }

        val visitedDirs = LinkedHashSet<String>()
        val result = ArrayList<ScannedAudio>()
        var visitedFiles = 0
        var skippedIgnored = 0

        while (stack.isNotEmpty()) {
            val pending = stack.removeLast()
            val dir = pending.dir

            val canonicalPath = runCatching { dir.canonicalPath }
                .getOrElse { dir.absolutePath }

            if (!visitedDirs.add(canonicalPath)) continue
            if (isInsideOutputDirectory(dir, outputDir)) continue

            val children = try {
                dir.listFiles()
            } catch (_: SecurityException) {
                null
            } catch (_: Throwable) {
                null
            } ?: continue

            for (child in children) {
                val name = child.name
                val displayPath = "${pending.displayPath}/$name"

                if (child.isDirectory) {
                    if (!isInsideOutputDirectory(child, outputDir)) {
                        stack.add(PendingDir(child, displayPath))
                    }
                    continue
                }

                if (!child.isFile) continue

                visitedFiles++
                var foundNow = false

                if (IgnoredFormatPreferences.shouldIgnore(context, name)) {
                    skippedIgnored++
                } else if (AudioFormatDetector.isSupported(name)) {
                    result += ScannedAudio(
                        uri = Uri.fromFile(child),
                        displayName = name,
                        size = child.length(),
                        relativePath = displayPath
                    )
                    foundNow = true
                }

                if (visitedFiles % 80 == 0 ||
                    (foundNow && result.size % 10 == 0)
                ) {
                    onProgress?.invoke(
                        ScanProgress(
                            visitedFiles = visitedFiles,
                            foundAudio = result.size,
                            skippedIgnored = skippedIgnored,
                            currentPath = displayPath
                        )
                    )
                }
            }
        }

        onProgress?.invoke(
            ScanProgress(
                visitedFiles = visitedFiles,
                foundAudio = result.size,
                skippedIgnored = skippedIgnored,
                currentPath = "全部共享存储"
            )
        )

        return result.sortedWith(
            compareBy<ScannedAudio> { it.displayName.lowercase() }
                .thenBy { it.relativePath.lowercase() }
        )
    }

    private fun storageRoots(context: Context): List<Pair<File, String>> {
        val roots = LinkedHashMap<String, Pair<File, String>>()

        fun addRoot(file: File?, label: String) {
            if (file == null) return
            val canonical = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
            if (!canonical.exists() || !canonical.isDirectory) return
            roots.putIfAbsent(canonical.absolutePath, canonical to label)
        }

        addRoot(Environment.getExternalStorageDirectory(), "内部存储")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageManager = context.getSystemService(StorageManager::class.java)
            storageManager?.storageVolumes?.forEachIndexed { index, volume ->
                val directory = volume.directory
                val label = if (volume.isPrimary) {
                    "内部存储"
                } else {
                    volume.getDescription(context).takeIf { it.isNotBlank() }
                        ?: "外部存储 ${index + 1}"
                }
                addRoot(directory, label)
            }
        }

        return roots.values.toList()
    }

    private fun isInsideOutputDirectory(file: File, outputDir: File): Boolean {
        val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val output = outputDir.path
        return path == output ||
            path.startsWith(output + File.separator)
    }
}
