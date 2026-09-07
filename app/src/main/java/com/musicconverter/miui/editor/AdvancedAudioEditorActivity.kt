package com.musicconverter.miui.editor

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.musicconverter.miui.core.AudioFileManager
import com.musicconverter.miui.core.ConversionEngine
import com.musicconverter.miui.core.FfmpegEngine
import com.musicconverter.miui.core.PreparedAudio
import com.musicconverter.miui.ui.ThemePreferences
import com.musicconverter.miui.ui.UiKit
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class AdvancedAudioEditorActivity : Activity() {
    private val pickCode = 3301

    private enum class Mode {
        CONCAT, SPLIT, PITCH, MULTITRACK
    }

    private data class LoadedTrack(
        val prepared: PreparedAudio,
        val file: File,
        val name: String,
        var offsetMs: Long = 0L,
        var volume: Float = 1f
    )

    private lateinit var mode: Mode
    private lateinit var status: TextView
    private lateinit var selectionText: TextView
    private lateinit var controlsHost: LinearLayout
    private lateinit var actionButton: TextView

    private val tracks = mutableListOf<LoadedTrack>()
    private var splitSeek: SeekBar? = null
    private var splitLabel: TextView? = null
    private var pitchSeek: SeekBar? = null
    private var pitchLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiKit.applyTheme(this)
        window.statusBarColor = UiKit.BG
        window.navigationBarColor = UiKit.BG
        window.decorView.systemUiVisibility =
            if (ThemePreferences.isDark(this)) 0
            else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        mode = runCatching {
            Mode.valueOf(intent.getStringExtra("mode") ?: "")
        }.getOrDefault(Mode.CONCAT)

        buildUi()
        refreshUi()
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
                UiKit.dp(this@AdvancedAudioEditorActivity, 18),
                UiKit.dp(this@AdvancedAudioEditorActivity, 20),
                UiKit.dp(this@AdvancedAudioEditorActivity, 18),
                UiKit.dp(this@AdvancedAudioEditorActivity, 34)
            )
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = UiKit.text(this, "‹", 34f, UiKit.TEXT).apply {
            gravity = Gravity.CENTER
            isClickable = true
            background = UiKit.ripple(this@AdvancedAudioEditorActivity, UiKit.SURFACE_2, 18)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(UiKit.dp(this, 46), UiKit.dp(this, 46)))
        header.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(this@AdvancedAudioEditorActivity, 14), 0, 0, 0)
                addView(UiKit.text(this@AdvancedAudioEditorActivity, titleForMode(), 25f, UiKit.TEXT, true))
                addView(UiKit.text(this@AdvancedAudioEditorActivity, subtitleForMode(), 12f, UiKit.TEXT_3).apply {
                    setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 4), 0, 0)
                })
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(header)

        root.addView(UiKit.spacer(this, 20))

        val fileCard = UiKit.card(this, 24)
        fileCard.addView(UiKit.sectionTitle(this, "素材", materialHint()))
        selectionText = UiKit.text(this, "尚未选择音频", 13.5f, UiKit.TEXT_2).apply {
            setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 14), 0, 0)
            setLineSpacing(0f, 1.18f)
        }
        fileCard.addView(selectionText)

        val choose = UiKit.wideButton(
            this,
            "+",
            if (mode == Mode.PITCH || mode == Mode.SPLIT) "选择音频文件" else "选择多个音频文件",
            true
        ).apply {
            setOnClickListener { chooseFiles() }
        }
        val clear = UiKit.wideButton(this, "×", "清空素材").apply {
            setOnClickListener {
                tracks.clear()
                refreshUi()
            }
        }
        UiKit.margins(choose, 0, 16, 0, 0)
        UiKit.margins(clear, 0, 10, 0, 0)
        fileCard.addView(choose)
        fileCard.addView(clear)
        root.addView(fileCard)

        root.addView(UiKit.spacer(this, 20))
        controlsHost = UiKit.card(this, 24)
        root.addView(controlsHost)

        root.addView(UiKit.spacer(this, 16))
        actionButton = UiKit.wideButton(this, "✓", actionTitle(), true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { runAction() }
        }
        root.addView(
            actionButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 58)
            )
        )

        root.addView(UiKit.spacer(this, 18))
        val statusCard = UiKit.card(this, 18).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusCard.addView(
            UiKit.text(this, "●", 11f, UiKit.SUCCESS, true),
            LinearLayout.LayoutParams(UiKit.dp(this, 24), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        status = UiKit.text(this, "状态：等待素材", 13f, UiKit.TEXT_2, true)
        statusCard.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusCard)

        setContentView(scroll)
    }

    private fun chooseFiles() {
        val multiple = mode == Mode.CONCAT || mode == Mode.MULTITRACK
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            },
            pickCode
        )
    }

    @Deprecated("Deprecated in Android API but kept for compatibility with the existing project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        data.data?.let { if (it !in uris) uris += it }
        if (uris.isEmpty()) return

        if (mode == Mode.PITCH || mode == Mode.SPLIT) tracks.clear()
        val remaining = if (mode == Mode.MULTITRACK) (4 - tracks.size).coerceAtLeast(0) else Int.MAX_VALUE
        val selectedUris = if (mode == Mode.MULTITRACK) uris.take(remaining) else uris

        status.text = "状态：正在准备 ${selectedUris.size} 个文件…"
        Thread {
            val loaded = mutableListOf<LoadedTrack>()
            val errors = mutableListOf<String>()
            selectedUris.forEach { uri ->
                try {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val prepared = AudioFileManager.prepareInput(this, uri)
                    val (editable, error) = ConversionEngine(this).prepareEditable(prepared)
                    if (editable == null) {
                        errors += "${prepared.displayName}: ${error ?: "无法准备"}"
                    } else {
                        loaded += LoadedTrack(prepared, editable, editable.name)
                    }
                } catch (t: Throwable) {
                    errors += (t.message ?: "读取失败")
                }
            }
            runOnUiThread {
                tracks += loaded
                status.text = when {
                    loaded.isNotEmpty() && errors.isEmpty() -> "状态：素材已载入"
                    loaded.isNotEmpty() -> "状态：已载入 ${loaded.size} 个，另有 ${errors.size} 个失败"
                    else -> "状态：载入失败"
                }
                if (errors.isNotEmpty()) toast(errors.first())
                refreshUi()
            }
        }.start()
    }

    private fun refreshUi() {
        selectionText.text = if (tracks.isEmpty()) {
            "尚未选择音频"
        } else {
            tracks.mapIndexed { index, track ->
                "${index + 1}. ${track.prepared.displayName}"
            }.joinToString("\n")
        }

        controlsHost.removeAllViews()
        when (mode) {
            Mode.CONCAT -> buildConcatControls()
            Mode.SPLIT -> buildSplitControls()
            Mode.PITCH -> buildPitchControls()
            Mode.MULTITRACK -> buildTrackControls()
        }
        actionButton.isEnabled = when (mode) {
            Mode.CONCAT -> tracks.size >= 2
            Mode.MULTITRACK -> tracks.size >= 2
            Mode.PITCH, Mode.SPLIT -> tracks.size == 1
        }
        actionButton.alpha = if (actionButton.isEnabled) 1f else 0.48f
    }

    private fun buildConcatControls() {
        controlsHost.addView(UiKit.sectionTitle(this, "拼接顺序", "按素材列表从上到下依次拼接"))
        controlsHost.addView(
            UiKit.text(
                this,
                if (tracks.size >= 2) "已准备 ${tracks.size} 段音频，可直接生成 MP3。"
                else "至少选择 2 个音频文件。",
                13f,
                UiKit.TEXT_2
            ).apply { setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 12), 0, 0) }
        )
    }

    private fun buildSplitControls() {
        controlsHost.addView(UiKit.sectionTitle(this, "分割位置", "将一个音频按指定时间点切成两段"))
        if (tracks.size != 1) {
            controlsHost.addView(UiKit.text(this, "请先选择一个音频文件。", 13f, UiKit.TEXT_2).apply {
                setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 12), 0, 0)
            })
            splitSeek = null
            splitLabel = null
            return
        }

        val duration = readDuration(tracks.first().file)
        splitLabel = UiKit.text(this, "分割点 ${time(duration / 2)} / ${time(duration)}", 14f, UiKit.TEXT, true).apply {
            setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 14), 0, UiKit.dp(this@AdvancedAudioEditorActivity, 6))
        }
        controlsHost.addView(splitLabel)

        splitSeek = SeekBar(this).apply {
            max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            progress = (max / 2).coerceAtLeast(1)
            progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
            thumbTintList = ColorStateList.valueOf(UiKit.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val safe = progress.coerceIn(1, (max - 1).coerceAtLeast(1))
                    if (seekBar != null && seekBar.progress != safe) seekBar.progress = safe
                    splitLabel?.text = "分割点 ${time(safe.toLong())} / ${time(duration)}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        controlsHost.addView(splitSeek)
    }

    private fun buildPitchControls() {
        controlsHost.addView(UiKit.sectionTitle(this, "升降调", "范围 -12 到 +12 半音，尽量保持原时长"))
        pitchLabel = UiKit.text(this, "0 半音 · 原调", 15f, UiKit.TEXT, true).apply {
            setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 14), 0, UiKit.dp(this@AdvancedAudioEditorActivity, 6))
        }
        controlsHost.addView(pitchLabel)

        pitchSeek = SeekBar(this).apply {
            max = 24
            progress = 12
            progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
            thumbTintList = ColorStateList.valueOf(UiKit.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val semitone = progress - 12
                    pitchLabel?.text = when {
                        semitone > 0 -> "+$semitone 半音 · 升调"
                        semitone < 0 -> "$semitone 半音 · 降调"
                        else -> "0 半音 · 原调"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        controlsHost.addView(pitchSeek)
    }

    private fun buildTrackControls() {
        controlsHost.addView(
            UiKit.sectionTitle(
                this,
                "多轨混音",
                "最多 4 条轨道；每轨可设置开始偏移和音量"
            )
        )
        if (tracks.isEmpty()) {
            controlsHost.addView(UiKit.text(this, "请选择 2–4 个音频文件。", 13f, UiKit.TEXT_2).apply {
                setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 12), 0, 0)
            })
            return
        }

        tracks.forEachIndexed { index, track ->
            val card = UiKit.card(this, 18).apply {
                background = UiKit.rounded(UiKit.SURFACE_2, 18, this@AdvancedAudioEditorActivity)
            }
            card.addView(UiKit.text(this, "轨道 ${index + 1} · ${track.prepared.displayName}", 13.5f, UiKit.TEXT, true))

            val offsetLabel = UiKit.text(this, "开始偏移 ${time(track.offsetMs)}", 12f, UiKit.TEXT_2).apply {
                setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 10), 0, 0)
            }
            card.addView(offsetLabel)
            val offsetSeek = SeekBar(this).apply {
                max = 30000
                progress = track.offsetMs.toInt().coerceIn(0, max)
                progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
                thumbTintList = ColorStateList.valueOf(UiKit.ACCENT)
                setOnSeekBarChangeListener(simpleListener { value ->
                    track.offsetMs = value.toLong()
                    offsetLabel.text = "开始偏移 ${time(track.offsetMs)}"
                })
            }
            card.addView(offsetSeek)

            val volumeLabel = UiKit.text(this, "音量 ${(track.volume * 100).roundToInt()}%", 12f, UiKit.TEXT_2).apply {
                setPadding(0, UiKit.dp(this@AdvancedAudioEditorActivity, 6), 0, 0)
            }
            card.addView(volumeLabel)
            val volumeSeek = SeekBar(this).apply {
                max = 200
                progress = (track.volume * 100).roundToInt().coerceIn(0, 200)
                progressTintList = ColorStateList.valueOf(UiKit.ACCENT)
                thumbTintList = ColorStateList.valueOf(UiKit.ACCENT)
                setOnSeekBarChangeListener(simpleListener { value ->
                    track.volume = value / 100f
                    volumeLabel.text = "音量 $value%"
                })
            }
            card.addView(volumeSeek)

            if (index > 0) UiKit.margins(card, 0, 12, 0, 0)
            controlsHost.addView(card)
        }
    }

    private fun simpleListener(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) block(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun runAction() {
        if (!actionButton.isEnabled) return
        actionButton.isEnabled = false
        actionButton.alpha = 0.48f
        status.text = "状态：正在处理…"

        Thread {
            try {
                when (mode) {
                    Mode.CONCAT -> runConcat()
                    Mode.SPLIT -> runSplit()
                    Mode.PITCH -> runPitch()
                    Mode.MULTITRACK -> runMix()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.text = "处理失败：${t.message}"
                    actionButton.isEnabled = true
                    actionButton.alpha = 1f
                }
            }
        }.start()
    }

    private fun runConcat() {
        val outputName = "拼接_${System.currentTimeMillis()}.mp3"
        val temp = outputFile(outputName)
        val result = FfmpegEngine.concat(tracks.map { it.file }, temp)
        if (!result.success) return fail(result.message)
        publishAndFinish(temp, outputName, "拼接完成")
    }

    private fun runSplit() {
        val track = tracks.first()
        val duration = readDuration(track.file)
        val split = splitSeek?.progress?.toLong()?.coerceIn(1L, (duration - 1).coerceAtLeast(1L))
            ?: return fail("无效的分割位置")
        val stem = track.prepared.displayName.substringBeforeLast('.', track.prepared.displayName)
        val firstName = "${stem}_part1.mp3"
        val secondName = "${stem}_part2.mp3"
        val first = outputFile(firstName)
        val second = outputFile(secondName)

        val a = FfmpegEngine.trim(track.file, first, 0L, split)
        if (!a.success) return fail(a.message)
        val b = FfmpegEngine.trim(track.file, second, split, duration)
        if (!b.success) return fail(b.message)

        AudioFileManager.publishAudio(this, first, firstName)
        AudioFileManager.publishAudio(this, second, secondName)
        first.delete()
        second.delete()
        success("分割完成 · 已保存两段到 Music/MusicConverter")
    }

    private fun runPitch() {
        val semitone = (pitchSeek?.progress ?: 12) - 12
        val track = tracks.first()
        val stem = track.prepared.displayName.substringBeforeLast('.', track.prepared.displayName)
        val suffix = if (semitone >= 0) "pitch+$semitone" else "pitch$semitone"
        val outputName = "${stem}_${suffix}.mp3"
        val temp = outputFile(outputName)
        val result = FfmpegEngine.pitchShift(track.file, temp, semitone)
        if (!result.success) return fail(result.message)
        publishAndFinish(temp, outputName, "升降调完成")
    }

    private fun runMix() {
        val outputName = "多轨混音_${System.currentTimeMillis()}.mp3"
        val temp = outputFile(outputName)
        val specs = tracks.map {
            FfmpegEngine.MixTrack(
                file = it.file,
                offsetMs = it.offsetMs,
                volume = it.volume
            )
        }
        val result = FfmpegEngine.mix(specs, temp)
        if (!result.success) return fail(result.message)
        publishAndFinish(temp, outputName, "多轨混音完成")
    }

    private fun publishAndFinish(file: File, name: String, message: String) {
        AudioFileManager.publishAudio(this, file, name)
        file.delete()
        success("$message · 已保存到 Music/MusicConverter")
    }

    private fun outputFile(name: String): File =
        File(cacheDir, "advanced_editor").apply { mkdirs() }.resolve(name)

    private fun fail(message: String) {
        runOnUiThread {
            status.text = "处理失败：$message"
            actionButton.isEnabled = true
            actionButton.alpha = 1f
        }
    }

    private fun success(message: String) {
        runOnUiThread {
            status.text = "状态：$message"
            toast(message)
            actionButton.isEnabled = true
            actionButton.alpha = 1f
        }
    }

    private fun readDuration(file: File): Long = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        retriever.release()
        value
    } catch (_: Throwable) {
        0L
    }

    private fun titleForMode(): String = when (mode) {
        Mode.CONCAT -> "音频拼接"
        Mode.SPLIT -> "音频分割"
        Mode.PITCH -> "升降调"
        Mode.MULTITRACK -> "多轨编辑"
    }

    private fun subtitleForMode(): String = when (mode) {
        Mode.CONCAT -> "多段音频 · 顺序连接 · 一次导出"
        Mode.SPLIT -> "指定时间点 · 一分为二"
        Mode.PITCH -> "半音控制 · 保持节奏"
        Mode.MULTITRACK -> "轨道偏移 · 独立音量 · 混音导出"
    }

    private fun materialHint(): String = when (mode) {
        Mode.CONCAT -> "至少选择两个文件，选择顺序即拼接顺序"
        Mode.SPLIT -> "选择一个文件后设置分割时间点"
        Mode.PITCH -> "选择一个文件后调整 -12 ～ +12 半音"
        Mode.MULTITRACK -> "选择 2–4 个文件作为独立轨道"
    }

    private fun actionTitle(): String = when (mode) {
        Mode.CONCAT -> "生成拼接音频"
        Mode.SPLIT -> "执行分割"
        Mode.PITCH -> "生成升降调音频"
        Mode.MULTITRACK -> "混音并导出"
    }

    private fun time(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
    }

    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
