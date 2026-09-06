package com.musicconverter.miui.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

object DeleteManager {
    fun delete(context: Context, uri: Uri): Boolean = try {
        when {
            uri.scheme.equals("file", ignoreCase = true) ->
                uri.path?.let(::File)?.delete() == true

            DocumentsContract.isDocumentUri(context, uri) ->
                DocumentsContract.deleteDocument(context.contentResolver, uri)

            else ->
                context.contentResolver.delete(uri, null, null) > 0
        }
    } catch (_: Throwable) {
        false
    }
}
