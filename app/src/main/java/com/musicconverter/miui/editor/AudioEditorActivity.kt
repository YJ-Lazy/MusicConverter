package com.musicconverter.miui.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
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
import com.musicconverter.miui.core.ConversionEngine
import com.musicconverter.miui.core.DeleteManager
import com.musicconverter.miui.core.FfmpegEngine
import com.musicconverter.miui.data.HistoryRepository
import com.musicconverter.miui.ui.UiKit
import com.musicconverter.miui.ui.ThemePreferences
import java.io.File
import java.util.Locale

class AudioEditorActivity : Activity() {
    private val pickAudioCode = 2201

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var previewStopRunnable: Runnable? = null
    private val playbackTicker = object : Runnable {
        override fun run() {
            val current = player ?: return
            if (!current.isPlaying) return
            val position = try {
                current.currentPosition.toLong()
            } catch (_: Throwable) {
                return
            }
            if (::waveformView.isInitialized) waveformView.updatePlayback(position)
            handler.postDelayed(this, 42L)
        }
    }

    private lateinit var playButton: TextView
    private lateinit var trimButton: TextView
    private lateinit var chooseFileButton: TextView
    private lateinit var fileNameText: TextView
    private lateinit var durationText: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var waveformMeta: TextView
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private lateinit var startLabel: TextView
    private lateinit var endLabel: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var status: TextView

    private var input: File? = null
    private var displayName: String = ""
    private var sourceDisplayName: String = ""
    private var sourceUri: Uri? = null
    private var sourceBackupFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyTheme(this)
        window.statusBarColor = UiKit.BG
        window.navigationBarColor = UiKit.BG
        window.decorView.systemUiVisibility =
            if (ThemePreferences.isDark(this)) {
                0
            } else {
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }

        val initialPath = intent.getStringExtra("inputPath")
        if (!initialPath.isNullOrBlank()) {
            input = File(initialPath).takeIf { it.exists() && it.isFile }
            input?.let { file ->
                displayName = intent.getStringExtra("displayName") ?: file.name
                sourceDisplayName = intent.getStringExtra("sourceDisplayName") ?: displayName
                sourceUri = intent.getStringExtra("sourceUri")?.let(Uri::parse)
                sourceBackupFile = intent.getStringExtra("sourceBackupPath")
                    ?.let(::File)
                    ?.takeIf { it.exists() }
                durationMs = readDuration(file)
                endMs = durationMs
            }
        }

        buildUi()

        if (input != null) {
            refreshLoadedFileUi(loadWaveform = true)
        } else {
            showEmptyEditor()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(UiKit.BG)
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@AudioEditorActivity, 18),
                UiKit.dp(this@AudioEditorActivity, 20),
                UiKit.dp(this@AudioEditorActivity, 18),
                UiKit.dp(this@AudioEditorActivity, 34)
            )
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
            addView(UiKit.text(this@AudioEditorActivity, "先进入 · 再选文件 · 波形滑动选区", 12f, UiKit.TEXT_3).apply {
                setPadding(0, UiKit.dp(this@AudioEditorActivity, 4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        root.addView(UiKit.spacer(this, 20))
        val fileCard = UiKit.card(this, 24).apply {
            background = UiKit.rounded(
                UiKit.primaryContainer(this@AudioEditorActivity),
                24,
                this@AudioEditorActivity,
                UiKit.primaryContainer(this@AudioEditorActivity),
                1
            )
        }
        fileCard.addView(
            UiKit.text(
                this,
                "编辑文件",
                11.5f,
                UiKit.themedColor(this@AudioEditorActivity, "#AEB8FF", "#6650D8"),
                true
            )
        )
        fileNameText = UiKit.text(this, "尚未选择音频", 17f, UiKit.TEXT, true).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 8), 0, 0)
            maxLines = 2
        }
        fileCard.addView(fileNameText)

        durationText = UiKit.text(this, "进入剪辑页后选择要编辑的文件", 12.5f, UiKit.TEXT_2).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 9), 0, 0)
        }
        fileCard.addView(durationText)

        chooseFileButton = UiKit.wideButton(this, "+", "选择 / 更换音频文件", true).apply {
            setOnClickListener { chooseAudioFile() }
        }
        UiKit.margins(chooseFileButton, 0, 14, 0, 0)
        fileCard.addView(chooseFileButton)
        root.addView(fileCard)

        root.addView(UiKit.spacer(this, 24))
        root.addView(
            UiKit.sectionTitle(
                this,
                "波形预览",
                "直接在波形上拖动建立选区；拖左右边界微调，拖选区整体平移"
            )
        )
        root.addView(UiKit.spacer(this, 12))

        val waveformCard = UiKit.card(this, 24)
        waveformView = WaveformView(this).apply {
            minimumHeight = UiKit.dp(this@AudioEditorActivity, 148)
            updateSelection(startMs, endMs, durationMs)
            setOnSelectionChangeListener { start, end ->
                if (durationMs <= 0L) return@setOnSelectionChangeListener
                stopPlayer()
                startMs = start.coerceIn(0L, durationMs)
                endMs = end.coerceIn(startMs, durationMs)
                syncSeekBarsFromSelection()
                updateLabels(updateWaveform = false)
            }
        }
        waveformCard.addView(
            waveformView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 148)
            )
        )
        waveformMeta = UiKit.text(this, "选择音频后生成波形", 11.5f, UiKit.TEXT_3).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 12), 0, 0)
        }
        waveformCard.addView(waveformMeta)
        root.addView(waveformCard)

        root.addView(UiKit.spacer(this, 24))
        root.addView(UiKit.sectionTitle(this, "剪辑范围", "波形手势和下面两个滑块会实时同步"))
        root.addView(UiKit.spacer(this, 12))

        val rangeCard = UiKit.card(this, 24)
        val labelsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val startBox = UiKit.card(this, 18).apply {
            background = UiKit.rounded(UiKit.SURFACE_2, 18, this@AudioEditorActivity)
            setPadding(
                UiKit.dp(this@AudioEditorActivity, 14),
                UiKit.dp(this@AudioEditorActivity, 12),
                UiKit.dp(this@AudioEditorActivity, 14),
                UiKit.dp(this@AudioEditorActivity, 12)
            )
        }
        startBox.addView(UiKit.text(this, "开始", 11.5f, UiKit.TEXT_3, true))
        startLabel = UiKit.text(this, "", 18f, UiKit.TEXT, true).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 5), 0, 0)
        }
        startBox.addView(startLabel)

        val endBox = UiKit.card(this, 18).apply {
            background = UiKit.rounded(UiKit.SURFACE_2, 18, this@AudioEditorActivity)
            setPadding(
                UiKit.dp(this@AudioEditorActivity, 14),
                UiKit.dp(this@AudioEditorActivity, 12),
                UiKit.dp(this@AudioEditorActivity, 14),
                UiKit.dp(this@AudioEditorActivity, 12)
            )
        }
        endBox.addView(UiKit.text(this, "结束", 11.5f, UiKit.TEXT_3, true))
        endLabel = UiKit.text(this, "", 18f, UiKit.TEXT, true).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 5), 0, 0)
        }
        endBox.addView(endLabel)

        labelsRow.addView(
            startBox,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = UiKit.dp(this@AudioEditorActivity, 6)
            }
        )
        labelsRow.addView(
            endBox,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = UiKit.dp(this@AudioEditorActivity, 6)
            }
        )
        rangeCard.addView(labelsRow)

        rangeLabel = UiKit.text(
            this,
            "",
            13f,
            UiKit.themedColor(this@AudioEditorActivity, "#C8BFFF", "#6650D8"),
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(
                0,
                UiKit.dp(this@AudioEditorActivity, 18),
                0,
                UiKit.dp(this@AudioEditorActivity, 5)
            )
        }
        rangeCard.addView(rangeLabel)

        startSeek = SeekBar(this).apply {
            max = 0
            progress = 0
            progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
            thumbTintList = ColorStateList.valueOf(
                UiKit.themedColor(this@AudioEditorActivity, "#CFC7FF", "#6650D8")
            )
        }
        rangeCard.addView(UiKit.text(this, "起点", 11.5f, UiKit.TEXT_3, true).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 10), 0, 0)
        })
        rangeCard.addView(startSeek)

        endSeek = SeekBar(this).apply {
            max = 0
            progress = 0
            progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
            thumbTintList = ColorStateList.valueOf(
                UiKit.themedColor(this@AudioEditorActivity, "#CFC7FF", "#6650D8")
            )
        }
        rangeCard.addView(UiKit.text(this, "终点", 11.5f, UiKit.TEXT_3, true).apply {
            setPadding(0, UiKit.dp(this@AudioEditorActivity, 8), 0, 0)
        })
        rangeCard.addView(endSeek)
        root.addView(rangeCard)

        root.addView(UiKit.spacer(this, 18))
        playButton = UiKit.wideButton(this, "▶", "试听选区", true)
        val reset = UiKit.wideButton(this, "↺", "重置选区")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            playButton,
            LinearLayout.LayoutParams(0, UiKit.dp(this@AudioEditorActivity, 54), 1f).apply {
                rightMargin = UiKit.dp(this@AudioEditorActivity, 6)
            }
        )
        row.addView(
            reset,
            LinearLayout.LayoutParams(0, UiKit.dp(this@AudioEditorActivity, 54), 1f).apply {
                leftMargin = UiKit.dp(this@AudioEditorActivity, 6)
            }
        )
        root.addView(row)

        root.addView(UiKit.spacer(this, 12))
        trimButton = UiKit.wideButton(this, "✓", "保存剪辑到 Music/MusicConverter", true).apply {
            gravity = Gravity.CENTER
        }
        root.addView(
            trimButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 58)
            )
        )

        root.addView(UiKit.spacer(this, 18))
        val statusCard = UiKit.card(this, 18).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                UiKit.dp(this@AudioEditorActivity, 15),
                UiKit.dp(this@AudioEditorActivity, 13),
                UiKit.dp(this@AudioEditorActivity, 15),
                UiKit.dp(this@AudioEditorActivity, 13)
            )
        }
        statusCard.addView(
            UiKit.text(this, "●", 11f, UiKit.SUCCESS, true),
            LinearLayout.LayoutParams(UiKit.dp(this, 24), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        status = UiKit.text(this, "状态：等待选择音频", 13f, UiKit.TEXT_2, true)
        statusCard.addView(
            status,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(statusCard)

        startSeek.setOnSeekBarChangeListener(listener { value ->
            if (durationMs <= 0L) return@listener
            startMs = value.toLong().coerceAtMost((endMs - minRangeMs()).coerceAtLeast(0))
            if (startSeek.progress != startMs.toInt()) startSeek.progress = startMs.toInt()
            updateLabels()
        })
        endSeek.setOnSeekBarChangeListener(listener { value ->
            if (durationMs <= 0L) return@listener
            endMs = value.toLong()
                .coerceAtLeast((startMs + minRangeMs()).coerceAtMost(durationMs))
                .coerceAtMost(durationMs)
            if (endSeek.progress != endMs.toInt()) endSeek.progress = endMs.toInt()
            updateLabels()
        })
        playButton.setOnClickListener { togglePreview() }
        reset.setOnClickListener {
            if (input == null) return@setOnClickListener
            stopPlayer()
            startMs = 0
            endMs = durationMs
            syncSeekBarsFromSelection()
            updateLabels()
        }
        trimButton.setOnClickListener { saveTrim() }

        setContentView(scroll)
    }

    private fun chooseAudioFile() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(picker, pickAudioCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickAudioCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        val persistFlags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        runCatching {
            contentResolver.takePersistableUriPermission(uri, persistFlags)
        }

        stopPlayer()
        setEditorEnabled(false)
        status.text = "状态：正在准备音频…"
        fileNameText.text = "正在读取所选文件…"
        durationText.text = "准备可编辑音频"

        Thread {
            try {
                val prepared = AudioFileManager.prepareInput(this, uri)
                val (editableFile, error) = ConversionEngine(this).prepareEditable(prepared)
                if (editableFile == null) {
                    runOnUiThread {
                        status.text = "无法准备编辑：${error ?: "未知错误"}"
                        fileNameText.text = "文件准备失败"
                        durationText.text = prepared.displayName
                        setEditorEnabled(false)
                    }
                    return@Thread
                }

                val editorName = if (AudioFormatDetector.isEncrypted(prepared.displayName)) {
                    editableFile.name
                } else {
                    prepared.displayName
                }

                runOnUiThread {
                    input = editableFile
                    displayName = editorName
                    sourceDisplayName = prepared.displayName
                    sourceUri = prepared.originalUri
                    sourceBackupFile = prepared.localFile
                    durationMs = readDuration(editableFile)
                    startMs = 0L
                    endMs = durationMs
                    refreshLoadedFileUi(loadWaveform = true)
                    status.text = "状态：已载入 ${prepared.displayName}"
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.text = "读取失败：${t.message ?: t.javaClass.simpleName}"
                    fileNameText.text = "未能载入文件"
                    durationText.text = "请重新选择音频"
                    setEditorEnabled(false)
                }
            }
        }.start()
    }

    private fun showEmptyEditor() {
        input = null
        displayName = ""
        sourceDisplayName = ""
        sourceUri = null
        sourceBackupFile = null
        durationMs = 0L
        startMs = 0L
        endMs = 0L

        fileNameText.text = "尚未选择音频"
        durationText.text = "可以先进入剪辑器，再在这里选择文件"
        waveformView.setLoading(false)
        waveformView.setSamples(emptyList())
        waveformView.updateSelection(0L, 0L, 0L)
        waveformMeta.text = "选择音频后，可直接在波形上滑动选择范围"
        startSeek.max = 0
        startSeek.progress = 0
        endSeek.max = 0
        endSeek.progress = 0
        updateLabels()
        setEditorEnabled(false)
        status.text = "状态：等待选择音频"
    }

    private fun refreshLoadedFileUi(loadWaveform: Boolean) {
        val file = input ?: return
        durationMs = readDuration(file)
        startMs = startMs.coerceIn(0L, durationMs)
        endMs = if (endMs <= 0L) durationMs else endMs.coerceIn(startMs, durationMs)

        fileNameText.text = displayName.ifBlank { file.name }
        durationText.text = "总时长 ${time(durationMs)}"
        val maxValue = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        startSeek.max = maxValue
        endSeek.max = maxValue
        syncSeekBarsFromSelection()
        updateLabels()
        setEditorEnabled(durationMs > 0L)

        if (loadWaveform) loadWaveform()
    }

    private fun setEditorEnabled(enabled: Boolean) {
        startSeek.isEnabled = enabled
        endSeek.isEnabled = enabled
        playButton.isEnabled = enabled
        trimButton.isEnabled = enabled
        startSeek.alpha = if (enabled) 1f else 0.45f
        endSeek.alpha = if (enabled) 1f else 0.45f
        playButton.alpha = if (enabled) 1f else 0.45f
        trimButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun loadWaveform() {
        val file = input ?: return
        waveformView.setLoading(true)
        waveformMeta.text = "正在加载波形…"
        Thread {
            val samples = WaveformExtractor.extract(file, 180)
            runOnUiThread {
                if (input != file) return@runOnUiThread
                waveformView.setLoading(false)
                waveformView.setSamples(samples)
                waveformView.updateSelection(startMs, endMs, durationMs)
                waveformMeta.text = if (samples.isEmpty()) {
                    "未能解析波形，仍可用滑块正常剪辑与试听。"
                } else {
                    "拖动空白处新建选区；拖边界微调；拖选区整体平移。"
                }
            }
        }.start()
    }

    private fun listener(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (player != null) stopPlayer()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    private fun syncSeekBarsFromSelection() {
        if (!::startSeek.isInitialized || !::endSeek.isInitialized) return
        val max = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (startSeek.max != max) startSeek.max = max
        if (endSeek.max != max) endSeek.max = max
        val start = startMs.coerceAtMost(max.toLong()).toInt()
        val end = endMs.coerceAtMost(max.toLong()).toInt()
        if (startSeek.progress != start) startSeek.progress = start
        if (endSeek.progress != end) endSeek.progress = end
    }

    private fun updateLabels(updateWaveform: Boolean = true) {
        if (!::startLabel.isInitialized) return
        startLabel.text = time(startMs)
        endLabel.text = time(endMs)
        rangeLabel.text = "选区时长  ${time((endMs - startMs).coerceAtLeast(0))}"
        if (updateWaveform && ::waveformView.isInitialized) {
            waveformView.updateSelection(startMs, endMs, durationMs)
        }
    }

    private fun minRangeMs(): Long = minOf(100L, durationMs.coerceAtLeast(1L))

    private fun togglePreview() {
        val inputFile = input ?: run {
            status.text = "请先选择音频文件"
            return
        }
        if (durationMs <= 0L || endMs <= startMs) {
            status.text = "请选择有效剪辑范围"
            return
        }

        val current = player
        if (current == null) {
            startPreview(inputFile)
            return
        }

        try {
            if (current.isPlaying) {
                current.pause()
                cancelPreviewTimer()
                stopPlaybackTicker()
                val pausedAt = current.currentPosition.toLong()
                waveformView.updatePlayback(pausedAt)
                playButton.text = "▶   继续试听"
                status.text = "状态：试听已暂停 · ${time(pausedAt)}"
            } else {
                if (current.currentPosition.toLong() >= endMs - 50L) {
                    stopPlayer()
                    startPreview(inputFile)
                } else {
                    current.start()
                    schedulePreviewStop(current.currentPosition.toLong())
                    startPlaybackTicker()
                    playButton.text = "Ⅱ   暂停试听"
                    status.text =
                        "状态：继续试听 · ${time(current.currentPosition.toLong())} / ${time(endMs)}"
                }
            }
        } catch (t: Throwable) {
            stopPlayer()
            status.text = "试听失败：${t.message}"
        }
    }

    private fun startPreview(inputFile: File) {
        stopPlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(inputFile.absolutePath)
                prepare()
                seekTo(startMs.toInt())
                setOnCompletionListener { finishPreview("状态：试听结束") }
                start()
            }
            waveformView.updatePlayback(startMs)
            playButton.text = "Ⅱ   暂停试听"
            status.text = "状态：正在试听 ${time(startMs)} - ${time(endMs)}"
            schedulePreviewStop(startMs)
            startPlaybackTicker()
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

    private fun startPlaybackTicker() {
        handler.removeCallbacks(playbackTicker)
        handler.post(playbackTicker)
    }

    private fun stopPlaybackTicker() {
        handler.removeCallbacks(playbackTicker)
    }

    private fun finishPreview(message: String) {
        stopPlayer()
        waveformView.updatePlayback(endMs)
        status.text = message
    }

    private fun saveTrim() {
        val inputFile = input ?: run {
            status.text = "请先选择音频文件"
            return
        }
        if (endMs <= startMs) {
            status.text = "请选择有效剪辑范围"
            return
        }

        status.text = "状态：正在剪辑…"
        Thread {
            val ext = AudioFormatDetector.extension(displayName)
                .ifBlank { AudioFormatDetector.extension(inputFile) }
                .ifBlank { "m4a" }
            val outName = AudioFileManager.outputName(
                displayName.ifBlank { inputFile.name },
                ext,
                "trim"
            )
            val out = File(File(cacheDir, "edits").apply { mkdirs() }, outName)
            val result = FfmpegEngine.trim(inputFile, out, startMs, endMs)
            if (!result.success) {
                runOnUiThread { status.text = "剪辑失败：${result.message}" }
                return@Thread
            }

            try {
                val published = AudioFileManager.publishAudio(this, out, outName)
                HistoryRepository(this).record(
                    displayName.ifBlank { inputFile.name },
                    outName,
                    "剪辑",
                    "完成"
                )
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
            AlertDialog.Builder(this)
                .setTitle("剪辑完成")
                .setMessage(outName)
                .setPositiveButton("确定", null)
                .show()
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
                        Toast.makeText(
                            this,
                            if (ok) "源文件已删除" else "无法删除源文件，请检查文件提供方权限",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    2 -> {
                        val replace = AudioFileManager.replaceOriginal(
                            this,
                            original,
                            sourceDisplayName,
                            outputUri,
                            outName,
                            sourceBackupFile
                        )
                        Toast.makeText(
                            this,
                            if (replace.success) {
                                "已用剪辑结果置换源文件"
                            } else {
                                "置换失败：${replace.message}；源文件已保留"
                            },
                            Toast.LENGTH_LONG
                        ).show()
                        if (replace.success) {
                            status.text = "状态：已用剪辑结果置换源文件"
                        }
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun readDuration(file: File): Long = try {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            release()
            d
        }
    } catch (_: Throwable) {
        0L
    }

    private fun time(ms: Long): String {
        val total = ms / 1000
        return String.format(
            Locale.getDefault(),
            "%02d:%02d.%03d",
            total / 60,
            total % 60,
            ms % 1000
        )
    }

    private fun stopPlayer() {
        cancelPreviewTimer()
        stopPlaybackTicker()
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        if (::playButton.isInitialized) playButton.text = "▶   试听选区"
        if (::waveformView.isInitialized) waveformView.updatePlayback(null)
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }
}
