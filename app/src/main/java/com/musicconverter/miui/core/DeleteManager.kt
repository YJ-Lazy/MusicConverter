package com.musicconverter.miui.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object DeleteManager {
    fun delete(context: Context, uri: Uri): Boolean = try {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    } catch (_: Throwable) {
        false
    }
}
