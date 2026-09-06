package com.musicconverter.miui.core

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class UnlockResult(val success: Boolean, val file: File? = null, val container: String = "", val source: String = "", val error: String = "")

class PythonUnlockEngine(private val context: Context) {
    fun unlock(input: File): UnlockResult {
        return try {
            ensurePythonStarted()
            val outputRoot = File(context.cacheDir, "unlocked").apply { mkdirs() }
            val outputDir = File(outputRoot, "unlock_${UUID.randomUUID()}").apply { mkdirs() }
            val raw = Python.getInstance().getModule("android_bridge")
                .callAttr("unlock_file", input.absolutePath, outputDir.absolutePath).toString()
            val obj = JSONObject(raw)
            if (!obj.optBoolean("ok", false)) {
                UnlockResult(false, error = obj.optString("error", "解密失败"))
            } else {
                UnlockResult(
                    true,
                    File(obj.getString("output")),
                    obj.optString("container"),
                    obj.optString("source")
                )
            }
        } catch (t: Throwable) {
            UnlockResult(false, error = "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun ensurePythonStarted() {
        if (Python.isStarted()) return
        synchronized(pythonStartLock) {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context.applicationContext))
            }
        }
    }

    companion object {
        private val pythonStartLock = Any()
    }
}
