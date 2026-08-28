package com.musicconverter.miui.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.musicconverter.miui.core.AudioFormatDetector
import com.musicconverter.miui.core.IgnoredFormatPreferences
import java.util.ArrayDeque

data class ScannedAudio(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val relativePath: String
)

data class ScanProgress(
    val visitedFiles: Int,
    val foundAudio: Int,
    val skippedIgnored: Int,
    val currentPath: String
)

object StorageAudioScanner {
    private data class PendingDir(val documentId: String, val path: String)

    fun scan(
        context: Context,
        treeUri: Uri,
        onProgress: ((ScanProgress) -> Unit)? = null
    ): List<ScannedAudio> {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<PendingDir>()
        stack.add(PendingDir(rootId, "存储空间"))

        val result = ArrayList<ScannedAudio>()
        var visited = 0
        var skippedIgnored = 0

        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )

            try {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                    while (cursor.moveToNext()) {
                        if (idCol < 0) continue
                        val childId = cursor.getString(idCol) ?: continue
                        val name = if (nameCol >= 0) cursor.getString(nameCol) ?: childId.substringAfterLast('/') else childId.substringAfterLast('/')
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                        val childPath = "${dir.path}/$name"

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.equals("MusicConverter", ignoreCase = true)) {
                                stack.add(PendingDir(childId, childPath))
                            }
                            continue
                        }

                        visited++
                        var foundNow = false
                        if (IgnoredFormatPreferences.shouldIgnore(context, name)) {
                            skippedIgnored++
                        } else if (AudioFormatDetector.isSupported(name)) {
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
                            result += ScannedAudio(childUri, name, size, childPath)
                            foundNow = true
                        }

                        if (visited % 40 == 0 || (foundNow && result.size % 10 == 0)) {
                            onProgress?.invoke(ScanProgress(visited, result.size, skippedIgnored, childPath))
                        }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }

        onProgress?.invoke(ScanProgress(visited, result.size, skippedIgnored, "存储空间"))
        return result.sortedWith(compareBy<ScannedAudio> { it.displayName.lowercase() }.thenBy { it.relativePath.lowercase() })
    }
}
