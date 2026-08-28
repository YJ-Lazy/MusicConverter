package com.musicconverter.miui.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.musicconverter.miui.core.AudioFileManager
import com.musicconverter.miui.core.AudioFormatDetector
import com.musicconverter.miui.core.DeleteManager
import com.musicconverter.miui.core.FfmpegEngine
import com.musicconverter.miui.data.HistoryRepository
import com.musicconverter.miui.ui.UiKit
import java.io.File
import java.util.Locale

class AudioEditorActivity : Activity() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var previewStopRunnable: Runnable? = null
    private lateinit var playButton: TextView
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private lateinit var startLabel: TextView
    private lateinit var endLabel: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var status: TextView
    private lateinit var input: File
    private lateinit var displayName: String
    private lateinit var sourceDisplayName: String
    private var sourceUri: Uri? = null
    private var sourceBackupFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = UiKit.BG
        window.navigationBarColor = UiKit.BG
        input = File(intent.getStringExtra("inputPath") ?: run { finish(); return })
        displayName = intent.getStringExtra("displayName") ?: input.name
        sourceDisplayName = intent.getStringExtra("sourceDisplayName") ?: displayName
        sourceUri = intent.getStringExtra("sourceUri")?.let(Uri::parse)
        sourceBackupFile = intent.getStringExtra("sourceBackupPath")?.let(::File)?.takeIf { it.exists() }
        durationMs = readDuration(input)
        endMs = durationMs
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(UiKit.BG)
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@AudioEditorActivity, 20), UiKit.dp(this@AudioEditorActivity, 20), UiKit.dp(this@AudioEditorActivity, 20), UiKit.dp(this@AudioEditorActivity, 34))
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = UiKit.text(this, "‹", 34f, UiKit.TEXT, false).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = UiKit.ripple(this@AudioEditorActivity, UiKit.SURFACE_2, 18)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(UiKit.dp(this, 46), UiKit.dp(this, 46)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@AudioEditorActivity, 14), 0, 0, 0)
            addView(UiKit.text(this@AudioEditorActivity, "音频剪辑", 25f, UiKit.TEXT, true))
            addView(UiKit.text(this@AudioEditorActivity, "精确选择 · 试听 · 保存", 12f, UiKit.TEXT_3).apply {
                setPadding(0, UiKit.dp(this@AudioEditorActivity, 4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        root.addView(UiKit.spacer(this, 20))
        val fileCard = UiKit.card(this, 24).apply {
            background = UiKit.rounded(Color.parseColor("#17142B"), 24, this@AudioEditorActivity, Color.parseColor("#332A62"), 1)
        }
        fileCard.addView(UiKit.text(this, "正在编辑", 11.5f, Color.parseColor("#AFA4E8"), true))
        fileCard.addView(UiKit.text(this, displayName, 17f, UiKit.TEXT, true).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 8), 0, 0)
            maxLines = 2
        })
        fileCard.addView(UiKit.text(this, "总时长 ${time(durationMs)}", 12.5f, UiKit.TEXT_2).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 9), 0, 0)
        })
        root.addView(fileCard)

        root.addView(UiKit.spacer(this, 24))
        root.addView(UiKit.sectionTitle(this, "剪辑范围", "拖动两个滑块确定保留片段"))
        root.addView(UiKit.spacer(this, 12))

        val rangeCard = UiKit.card(this, 24)
        val labelsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startBox = UiKit.card(this, 18).apply {
            background = UiKit.rounded(UiKit.SURFACE_2, 18, this@AudioEditorActivity)
            setPadding(UiKit.dp(this@AudioEditorActivity, 14), UiKit.dp(this@AudioEditorActivity, 12), UiKit.dp(this@AudioEditorActivity, 14), UiKit.dp(this@AudioEditorActivity, 12))
        }
        startBox.addView(UiKit.text(this, "开始", 11.5f, UiKit.TEXT_3, true))
        startLabel = UiKit.text(this, "", 18f, UiKit.TEXT, true).apply { setPadding(0, UiKit.dp(this@AudioEditorActivity, 5), 0, 0) }
        startBox.addView(startLabel)
        val endBox = UiKit.card(this, 18).apply {
            background = UiKit.rounded(UiKit.SURFACE_2, 18, this@AudioEditorActivity)
            setPadding(UiKit.dp(this@AudioEditorActivity, 14), UiKit.dp(this@AudioEditorActivity, 12), UiKit.dp(this@AudioEditorActivity, 14), UiKit.dp(this@AudioEditorActivity, 12))
        }
        endBox.addView(UiKit.text(this, "结束", 11.5f, UiKit.TEXT_3, true))
        endLabel = UiKit.text(this, "", 18f, UiKit.TEXT, true).apply { setPadding(0, UiKit.dp(this@AudioEditorActivity, 5), 0, 0) }
        endBox.addView(endLabel)
        labelsRow.addView(startBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = UiKit.dp(this@AudioEditorActivity, 6) })
        labelsRow.addView(endBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = UiKit.dp(this@AudioEditorActivity, 6) })
        rangeCard.addView(labelsRow)

        rangeLabel = UiKit.text(this, "", 13f, Color.parseColor("#C8BFFF"), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 18), 0, UiKit.dp(this@AudioEditorActivity, 5))
        }
        rangeCard.addView(rangeLabel)

        startSeek = SeekBar(this).apply {
            max = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress = 0
            progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#CFC7FF"))
        }
        rangeCard.addView(UiKit.text(this, "起点", 11.5f, UiKit.TEXT_3, true).apply { setPadding(0, UiKit.dp(this@AudioEditorActivity, 10), 0, 0) })
        rangeCard.addView(startSeek)
        endSeek = SeekBar(this).apply {
            max = startSeek.max
            progress = startSeek.max
            progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#CFC7FF"))
        }
        rangeCard.addView(UiKit.text(this, "终点", 11.5f, UiKit.TEXT_3, true).apply { setPadding(0, UiKit.dp(this@AudioEditorActivity, 8), 0, 0) })
        rangeCard.addView(endSeek)
        root.addView(rangeCard)

        root.addView(UiKit.spacer(this, 18))
        playButton = UiKit.wideButton(this, "▶", "试听选区", true)
        val reset = UiKit.wideButton(this, "↺", "重置选区")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(playButton, LinearLayout.LayoutParams(0, UiKit.dp(this@AudioEditorActivity, 54), 1f).apply { rightMargin = UiKit.dp(this@AudioEditorActivity, 6) })
        row.addView(reset, LinearLayout.LayoutParams(0, UiKit.dp(this@AudioEditorActivity, 54), 1f).apply { leftMargin = UiKit.dp(this@AudioEditorActivity, 6) })
        root.addView(row)

        root.addView(UiKit.spacer(this, 12))
        val trim = UiKit.wideButton(this, "✓", "保存剪辑到 Music/MusicConverter", true).apply {
            gravity = Gravity.CENTER
        }
        root.addView(trim, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 58)))

        root.addView(UiKit.spacer(this, 18))
        val statusCard = UiKit.card(this, 18).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(this@AudioEditorActivity, 15), UiKit.dp(this@AudioEditorActivity, 13), UiKit.dp(this@AudioEditorActivity, 15), UiKit.dp(this@AudioEditorActivity, 13))
        }
        statusCard.addView(UiKit.text(this, "●", 11f, UiKit.SUCCESS, true), LinearLayout.LayoutParams(UiKit.dp(this, 24), LinearLayout.LayoutParams.WRAP_CONTENT))
        status = UiKit.text(this, "状态：等待编辑", 13f, UiKit.TEXT_2, true)
        statusCard.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusCard)
        updateLabels()

        startSeek.setOnSeekBarChangeListener(listener { value ->
            startMs = value.toLong().coerceAtMost((endMs - 100).coerceAtLeast(0))
            if (startSeek.progress != startMs.toInt()) startSeek.progress = startMs.toInt()
            updateLabels()
        })
        endSeek.setOnSeekBarChangeListener(listener { value ->
            endMs = value.toLong().coerceAtLeast((startMs + 100).coerceAtMost(durationMs)).coerceAtMost(durationMs)
            if (endSeek.progress != endMs.toInt()) endSeek.progress = endMs.toInt()
            updateLabels()
        })
        playButton.setOnClickListener { togglePreview() }
        reset.setOnClickListener {
            stopPlayer()
            startMs = 0
            endMs = durationMs
            startSeek.progress = 0
            endSeek.progress = endSeek.max
            updateLabels()
        }
        trim.setOnClickListener { saveTrim() }
        setContentView(scroll)
    }

    private fun listener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) { if (player != null) stopPlayer() }
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun updateLabels() {
        startLabel.text = time(startMs)
        endLabel.text = time(endMs)
        rangeLabel.text = "选区时长  ${time((endMs - startMs).coerceAtLeast(0))}"
    }

    private fun togglePreview() {
        val current = player
        if (current == null) {
            startPreview()
            return
        }

        try {
            if (current.isPlaying) {
                current.pause()
                cancelPreviewTimer()
                playButton.text = "▶   继续试听"
                status.text = "状态：试听已暂停 · ${time(current.currentPosition.toLong())}"
            } else {
                if (current.currentPosition.toLong() >= endMs - 50L) {
                    stopPlayer()
                    startPreview()
                } else {
                    current.start()
                    schedulePreviewStop(current.currentPosition.toLong())
                    playButton.text = "Ⅱ   暂停试听"
                    status.text = "状态：继续试听 · ${time(current.currentPosition.toLong())} / ${time(endMs)}"
                }
            }
        } catch (t: Throwable) {
            stopPlayer()
            status.text = "试听失败：${t.message}"
        }
    }

    private fun startPreview() {
        stopPlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(input.absolutePath)
                prepare()
                seekTo(startMs.toInt())
                setOnCompletionListener { finishPreview("状态：试听结束") }
                start()
            }
            playButton.text = "Ⅱ   暂停试听"
            status.text = "状态：正在试听 ${time(startMs)} - ${time(endMs)}"
            schedulePreviewStop(startMs)
        } catch (t: Throwable) {
            stopPlayer()
            status.text = "试听失败：${t.message}"
        }
    }

    private fun schedulePreviewStop(currentPositionMs: Long) {
        cancelPreviewTimer()
        val remaining = (endMs - currentPositionMs).coerceAtLeast(100L)
        val task = Runnable { finishPreview("状态：试听结束") }
        previewStopRunnable = task
        handler.postDelayed(task, remaining)
    }

    private fun cancelPreviewTimer() {
        previewStopRunnable?.let(handler::removeCallbacks)
        previewStopRunnable = null
    }

    private fun finishPreview(message: String) {
        stopPlayer()
        status.text = message
    }

    private fun saveTrim() {
        if (endMs <= startMs) { status.text = "请选择有效剪辑范围"; return }
        status.text = "状态：正在剪辑…"
        Thread {
            val ext = AudioFormatDetector.extension(displayName).ifBlank { AudioFormatDetector.extension(input) }.ifBlank { "m4a" }
            val outName = AudioFileManager.outputName(displayName, ext, "trim")
            val out = File(File(cacheDir, "edits").apply { mkdirs() }, outName)
            val result = FfmpegEngine.trim(input, out, startMs, endMs)
            if (!result.success) {
                runOnUiThread { status.text = "剪辑失败：${result.message}" }
                return@Thread
            }
            try {
                val published = AudioFileManager.publishAudio(this, out, outName)
                HistoryRepository(this).record(displayName, outName, "剪辑", "完成")
                runOnUiThread {
                    status.text = "已保存到 Music/MusicConverter/$outName"
                    showFinishedDialog(outName, published)
                }
            } catch (t: Throwable) {
                runOnUiThread { status.text = "保存失败：${t.message}" }
            }
        }.start()
    }

    private fun showFinishedDialog(outName: String, outputUri: Uri) {
        val original = sourceUri
        if (original == null) {
            AlertDialog.Builder(this).setTitle("剪辑完成").setMessage(outName).setPositiveButton("确定", null).show()
            return
        }

        val options = arrayOf("保留源文件", "删除源文件", "用剪辑结果置换源文件")
        AlertDialog.Builder(this)
            .setTitle("剪辑完成")
            .setMessage("已生成：$outName\n请选择源文件处理方式。")
            .setItems(options) { _, which ->
                when (which) {
                    1 -> {
                        val ok = DeleteManager.delete(this, original)
                        Toast.makeText(this, if (ok) "源文件已删除" else "无法删除源文件，请检查文件提供方权限", Toast.LENGTH_LONG).show()
                    }
                    2 -> {
                        val replace = AudioFileManager.replaceOriginal(this, original, sourceDisplayName, outputUri, outName, sourceBackupFile)
                        Toast.makeText(
                            this,
                            if (replace.success) "已用剪辑结果置换源文件" else "置换失败：${replace.message}；源文件已保留",
                            Toast.LENGTH_LONG
                        ).show()
                        if (replace.success) status.text = "状态：已用剪辑结果置换源文件"
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun readDuration(file: File): Long = try {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            release(); d
        }
    } catch (_: Throwable) { 0L }

    private fun time(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.getDefault(), "%02d:%02d.%03d", total / 60, total % 60, ms % 1000)
    }

    private fun stopPlayer() {
        cancelPreviewTimer()
        try { player?.stop() } catch (_: Throwable) {}
        try { player?.release() } catch (_: Throwable) {}
        player = null
        if (::playButton.isInitialized) playButton.text = "▶   试听选区"
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }
}
