package com.musicconverter.miui

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.musicconverter.miui.core.*
import com.musicconverter.miui.data.HistoryRepository
import com.musicconverter.miui.editor.AudioEditorActivity
import com.musicconverter.miui.scanner.MusicAppScanner
import com.musicconverter.miui.scanner.ScannedAudio
import com.musicconverter.miui.scanner.StorageAudioScanner
import com.musicconverter.miui.service.BatchConversionService
import com.musicconverter.miui.service.BatchProgressSnapshot
import com.musicconverter.miui.ui.UiKit

class MainActivity : Activity() {
    private val pickFileCode = 1001
    private val pickTreeCode = 1002

    private var selected: PreparedAudio? = null
    private var scannedBatch: List<ScannedAudio> = emptyList()
    @Volatile private var batchRunning = false

    private lateinit var selectedText: TextView
    private lateinit var batchText: TextView
    private lateinit var batchConvertButton: TextView
    private lateinit var batchPauseButton: TextView
    private lateinit var ignoreFormatsButton: TextView
    private lateinit var status: TextView
    private lateinit var deleteAsk: CheckBox
    private var batchReceiverRegistered = false

    private val batchProgressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BatchConversionService.ACTION_PROGRESS) return
            val snapshot = BatchProgressSnapshot(
                running = intent.getBooleanExtra(BatchConversionService.EXTRA_RUNNING, false),
                current = intent.getIntExtra(BatchConversionService.EXTRA_CURRENT, 0),
                total = intent.getIntExtra(BatchConversionService.EXTRA_TOTAL, 0),
                success = intent.getIntExtra(BatchConversionService.EXTRA_SUCCESS, 0),
                failed = intent.getIntExtra(BatchConversionService.EXTRA_FAILED, 0),
                replaced = intent.getIntExtra(BatchConversionService.EXTRA_REPLACED, 0),
                replaceFailed = intent.getIntExtra(BatchConversionService.EXTRA_REPLACE_FAILED, 0),
                currentName = intent.getStringExtra(BatchConversionService.EXTRA_CURRENT_NAME).orEmpty(),
                replaceSource = intent.getBooleanExtra(BatchConversionService.EXTRA_REPLACE_SOURCE, false),
                parallelism = intent.getIntExtra(BatchConversionService.EXTRA_PARALLELISM, 1),
                active = intent.getIntExtra(BatchConversionService.EXTRA_ACTIVE, 0),
                pausing = intent.getBooleanExtra(BatchConversionService.EXTRA_PAUSING, false),
                paused = intent.getBooleanExtra(BatchConversionService.EXTRA_PAUSED, false),
                done = intent.getBooleanExtra(BatchConversionService.EXTRA_DONE, false),
                cancelled = intent.getBooleanExtra(BatchConversionService.EXTRA_CANCELLED, false),
                message = intent.getStringExtra(BatchConversionService.EXTRA_MESSAGE).orEmpty()
            )
            applyBatchSnapshot(snapshot, showResult = snapshot.done)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = UiKit.BG
        window.navigationBarColor = UiKit.BG
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        if (!batchReceiverRegistered) {
            val filter = IntentFilter(BatchConversionService.ACTION_PROGRESS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batchProgressReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(batchProgressReceiver, filter)
            }
            batchReceiverRegistered = true
        }
        applyBatchSnapshot(BatchConversionService.snapshot(), showResult = false)
    }

    override fun onStop() {
        if (batchReceiverRegistered) {
            runCatching { unregisterReceiver(batchProgressReceiver) }
            batchReceiverRegistered = false
        }
        super.onStop()
    }

    private fun buildUi() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG)
        }

        val pageHost = FrameLayout(this).apply {
            setBackgroundColor(UiKit.BG)
        }
        screen.addView(pageHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val homePage = buildHomePage()
        val batchPage = buildBatchPage()
        val aboutPage = buildAboutPage()
        val pages = listOf<View>(homePage, batchPage, aboutPage)
        pages.forEach { pageHost.addView(it, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )) }

        val statusWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 10), UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 8))
        }
        val statusDot = TextView(this).apply {
            text = "●"
            textSize = 10f
            setTextColor(UiKit.SUCCESS)
            gravity = Gravity.CENTER
        }
        statusWrap.addView(statusDot, LinearLayout.LayoutParams(UiKit.dp(this, 22), LinearLayout.LayoutParams.WRAP_CONTENT))
        status = UiKit.text(this, "状态：等待任务", 12f, UiKit.TEXT_3, true)
        statusWrap.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        screen.addView(statusWrap)

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 8), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 12))
            background = UiKit.rounded(UiKit.SURFACE, 26, this@MainActivity, UiKit.BORDER, 1)
        }
        UiKit.margins(navigation, 14, 0, 14, 12)

        val homeNav = UiKit.navItem(this, "⌂", "首页")
        val batchNav = UiKit.navItem(this, "≋", "批量")
        val aboutNav = UiKit.navItem(this, "ⓘ", "介绍")
        val navItems = listOf(homeNav, batchNav, aboutNav)
        navItems.forEachIndexed { index, item ->
            navigation.addView(item, LinearLayout.LayoutParams(0, UiKit.dp(this, 58), 1f).apply {
                if (index > 0) leftMargin = UiKit.dp(this@MainActivity, 5)
            })
        }
        screen.addView(navigation)

        fun selectPage(index: Int) {
            pages.forEachIndexed { i, view -> view.visibility = if (i == index) View.VISIBLE else View.GONE }
            navItems.forEachIndexed { i, item -> UiKit.setNavSelected(item, i == index) }
        }
        homeNav.setOnClickListener { selectPage(0) }
        batchNav.setOnClickListener { selectPage(1) }
        aboutNav.setOnClickListener { selectPage(2) }
        selectPage(0)

        setContentView(screen)
    }

    private fun pageRoot(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(UiKit.BG)
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 22), UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 30))
        }
        scroll.addView(root)
        return scroll to root
    }

    private fun buildHomePage(): ScrollView {
        val (scroll, root) = pageRoot()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(UiKit.text(this@MainActivity, "单文件工作台", 27f, UiKit.TEXT, true))
            addView(UiKit.text(this@MainActivity, "转换与剪辑，一次专注一首音乐", 12.5f, UiKit.TEXT_3).apply {
                setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val miniIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        top.addView(miniIcon, LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)))
        root.addView(top)

        root.addView(UiKit.spacer(this, 22))
        val fileCard = UiKit.card(this, 26).apply {
            background = UiKit.rounded(Color.parseColor("#17142B"), 26, this@MainActivity, Color.parseColor("#352E62"), 1)
        }
        fileCard.addView(UiKit.text(this, "当前音乐", 12f, Color.parseColor("#AFA6E6"), true))
        val fileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiKit.dp(this@MainActivity, 13), 0, 0)
        }
        val fileIcon = UiKit.text(this, "♫", 27f, Color.parseColor("#D5CEFF"), true).apply {
            gravity = Gravity.CENTER
            background = UiKit.rounded(Color.parseColor("#2B2450"), 18, this@MainActivity)
        }
        fileRow.addView(fileIcon, LinearLayout.LayoutParams(UiKit.dp(this, 54), UiKit.dp(this, 54)))
        selectedText = UiKit.text(this, "尚未选择文件\n选择一首音乐开始处理", 14f, Color.parseColor("#D3CEE8")).apply {
            setLineSpacing(0f, 1.18f)
            setPadding(UiKit.dp(this@MainActivity, 14), 0, 0, 0)
        }
        fileRow.addView(selectedText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fileCard.addView(fileRow)
        root.addView(fileCard)

        root.addView(UiKit.spacer(this, 18))
        val selectButton = UiKit.wideButton(this, "+", "选择音乐文件", true)
        root.addView(selectButton)

        root.addView(UiKit.spacer(this, 20))
        root.addView(UiKit.sectionTitle(this, "处理方式", "转换格式或进入剪辑器"))
        root.addView(UiKit.spacer(this, 12))

        val convertButton = UiKit.actionTile(this, "⇄", "转换格式", "MP3 · FLAC · M4A · WAV", true)
        val editButton = UiKit.actionTile(this, "✂", "编辑 / 剪辑", "选区试听 · 暂停 · 保存")
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(convertButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = UiKit.dp(this@MainActivity, 6)
        })
        actionRow.addView(editButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = UiKit.dp(this@MainActivity, 6)
        })
        root.addView(actionRow)

        deleteAsk = CheckBox(this).apply {
            text = "处理完成后询问源文件操作"
            textSize = 13f
            setTextColor(UiKit.TEXT_2)
            isChecked = true
            setPadding(0, UiKit.dp(this@MainActivity, 17), 0, 0)
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(UiKit.ACCENT, UiKit.TEXT_3)
            )
        }
        root.addView(deleteAsk)
        root.addView(UiKit.text(this, "完成后可保留、删除，或用处理结果置换源文件。", 11.5f, UiKit.TEXT_3).apply {
            setPadding(UiKit.dp(this@MainActivity, 30), 0, 0, 0)
        })

        root.addView(UiKit.spacer(this, 22))
        val localCard = UiKit.card(this, 22)
        localCard.addView(UiKit.text(this, "本地处理", 15f, UiKit.TEXT, true))
        localCard.addView(UiKit.text(this, "文件解密、转换与剪辑均在设备端执行。输出默认保存至 Music/MusicConverter。", 12f, UiKit.TEXT_3).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 8), 0, 0)
            setLineSpacing(0f, 1.2f)
        })
        root.addView(localCard)

        selectButton.setOnClickListener { chooseFile() }
        convertButton.setOnClickListener { showConvertDialog() }
        editButton.setOnClickListener { openEditor() }
        return scroll
    }

    private fun buildBatchPage(): ScrollView {
        val (scroll, root) = pageRoot()

        root.addView(UiKit.text(this, "批量与工具", 27f, UiKit.TEXT, true))
        root.addView(UiKit.text(this, "扫描一个目录，一次处理全部支持的音乐", 12.5f, UiKit.TEXT_3).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0)
        })

        root.addView(UiKit.spacer(this, 22))
        val scanButton = UiKit.wideButton(this, "⌕", "扫描磁盘 / 音乐目录", true)
        root.addView(scanButton)

        ignoreFormatsButton = UiKit.wideButton(
            this,
            "⊘",
            "忽略格式 · ${IgnoredFormatPreferences.summary(this)}"
        )
        UiKit.margins(ignoreFormatsButton, 0, 10, 0, 0)
        root.addView(ignoreFormatsButton)

        root.addView(UiKit.spacer(this, 14))
        val batchCard = UiKit.card(this, 26)
        val batchHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val batchIcon = UiKit.text(this, "≋", 25f, Color.parseColor("#C8BFFF"), true).apply {
            gravity = Gravity.CENTER
            background = UiKit.rounded(Color.parseColor("#292344"), 18, this@MainActivity)
        }
        batchHead.addView(batchIcon, LinearLayout.LayoutParams(UiKit.dp(this, 52), UiKit.dp(this, 52)))
        batchText = UiKit.text(this, "尚未扫描目录\n选择内部存储或音乐目录开始", 13.5f, UiKit.TEXT_2).apply {
            setLineSpacing(0f, 1.18f)
            setPadding(UiKit.dp(this@MainActivity, 14), 0, 0, 0)
        }
        batchHead.addView(batchText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        batchCard.addView(batchHead)
        batchConvertButton = UiKit.wideButton(this, "⇉", "一键转换扫描结果", true).apply {
            isEnabled = false
            alpha = 0.45f
        }
        UiKit.margins(batchConvertButton, 0, 15, 0, 0)
        batchCard.addView(batchConvertButton)
        batchPauseButton = UiKit.wideButton(this, "Ⅱ", "完成当前任务后暂停").apply {
            isEnabled = false
            alpha = 0.45f
        }
        UiKit.margins(batchPauseButton, 0, 10, 0, 0)
        batchCard.addView(batchPauseButton)
        batchCard.addView(UiKit.text(this, "暂停不会中断正在处理的音频：点击后停止派发新任务，等待当前并行转换全部完成，再进入暂停状态。", 11.5f, UiKit.TEXT_3).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 10), 0, 0)
        })
        batchCard.addView(UiKit.text(this, "转换前可选择输出格式，并决定保留源文件或用结果置换源文件。", 11.5f, UiKit.TEXT_3).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 10), 0, 0)
        })
        root.addView(batchCard)

        root.addView(UiKit.spacer(this, 24))
        root.addView(UiKit.sectionTitle(this, "工具", "诊断、应用识别与处理记录"))
        root.addView(UiKit.spacer(this, 12))

        val backgroundButton = UiKit.wideButton(this, "⚡", "允许后台耗电 / 电池优化")
        val appScanButton = UiKit.wideButton(this, "⌁", "扫描已安装音乐 APP")
        val historyButton = UiKit.wideButton(this, "◷", "转换 / 剪辑历史")
        val diagnosticsButton = UiKit.wideButton(this, "✓", "FFmpeg 运行检测")
        root.addView(backgroundButton)
        UiKit.margins(appScanButton, 0, 10, 0, 0)
        root.addView(appScanButton)
        UiKit.margins(historyButton, 0, 10, 0, 0)
        root.addView(historyButton)
        UiKit.margins(diagnosticsButton, 0, 10, 0, 0)
        root.addView(diagnosticsButton)

        root.addView(UiKit.spacer(this, 18))
        val tip = UiKit.card(this, 20)
        tip.addView(UiKit.text(this, "扫描说明", 14f, UiKit.TEXT, true))
        tip.addView(UiKit.text(this, "Android 11+ 会限制 Android/data、Android/obb 等受保护目录。选择内部存储根目录可以扫描系统允许访问的其他区域。批量扫描会自动跳过你在“忽略格式”中勾选的文件。", 11.5f, UiKit.TEXT_3).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 8), 0, 0)
            setLineSpacing(0f, 1.2f)
        })
        root.addView(tip)

        scanButton.setOnClickListener { chooseScanFolder() }
        ignoreFormatsButton.setOnClickListener { showIgnoredFormatsDialog() }
        batchConvertButton.setOnClickListener { showBatchConvertDialog() }
        batchPauseButton.setOnClickListener {
            val snapshot = BatchConversionService.snapshot()
            val ok = if (snapshot.paused || snapshot.pausing) {
                BatchConversionService.requestResume(this)
            } else {
                BatchConversionService.requestPause(this)
            }
            if (!ok) toast("当前没有可暂停或继续的批量任务")
        }
        backgroundButton.setOnClickListener { requestBackgroundBatteryAccess() }
        appScanButton.setOnClickListener { scanMusicApps() }
        historyButton.setOnClickListener { showHistory() }
        diagnosticsButton.setOnClickListener {
            status.text = "状态：正在检测 FFmpeg…"
            Thread {
                val result = FfmpegEngine.availability()
                runOnUiThread { status.text = "状态：${result.message}" }
            }.start()
        }
        return scroll
    }

    private fun buildAboutPage(): ScrollView {
        val (scroll, root) = pageRoot()

        val intro = UiKit.card(this, 28).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = UiKit.rounded(Color.parseColor("#17142B"), 28, this@MainActivity, Color.parseColor("#352E62"), 1)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        intro.addView(icon, LinearLayout.LayoutParams(UiKit.dp(this, 76), UiKit.dp(this, 76)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        intro.addView(UiKit.text(this, "MusicConverter MIUI Pro", 22f, UiKit.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, UiKit.dp(this@MainActivity, 14), 0, 0)
        })
        intro.addView(UiKit.text(this, "v1.5.0 · Background Progress", 12f, Color.parseColor("#B9B1E8"), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0)
        })
        intro.addView(UiKit.text(this, "为本地音乐解密、格式转换与轻量音频编辑打造的设备端工具。", 13f, Color.parseColor("#CBC5EA")).apply {
            gravity = Gravity.CENTER
            setPadding(0, UiKit.dp(this@MainActivity, 16), 0, 0)
            setLineSpacing(0f, 1.2f)
        })
        root.addView(intro)

        root.addView(UiKit.spacer(this, 22))
        root.addView(UiKit.sectionTitle(this, "能力"))
        root.addView(UiKit.spacer(this, 12))
        val capability = UiKit.card(this, 22)
        capability.addView(UiKit.infoRow(this, "⇄", "格式转换", "MP3 / FLAC / M4A / WAV / OGG"))
        capability.addView(UiKit.infoRow(this, "◇", "加密格式", "NCM / QMC / KGM / KWM 等").also { UiKit.margins(it, 0, 12, 0, 0) })
        capability.addView(UiKit.infoRow(this, "✂", "音频剪辑", "选区、试听、暂停与导出").also { UiKit.margins(it, 0, 12, 0, 0) })
        capability.addView(UiKit.infoRow(this, "≋", "批量处理", "目录扫描后统一转换").also { UiKit.margins(it, 0, 12, 0, 0) })
        capability.addView(UiKit.infoRow(this, "⚡", "后台转换", "前台服务通知显示进度，并可申请忽略电池优化").also { UiKit.margins(it, 0, 12, 0, 0) })
        root.addView(capability)

        root.addView(UiKit.spacer(this, 18))
        val privacy = UiKit.card(this, 22)
        privacy.addView(UiKit.text(this, "隐私与文件", 15f, UiKit.TEXT, true))
        privacy.addView(UiKit.text(this, "核心处理在本机完成。应用通过 Android SAF 访问用户主动选择的文件或目录，并遵守系统存储权限。", 12f, UiKit.TEXT_3).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 8), 0, 0)
            setLineSpacing(0f, 1.2f)
        })
        root.addView(privacy)

        root.addView(UiKit.spacer(this, 18))
        val credits = UiKit.card(this, 22)
        credits.addView(UiKit.text(this, "开源组件", 15f, UiKit.TEXT, true))
        credits.addView(UiKit.text(this, "FFmpeg / FFmpegKit · Chaquopy · Room · music-geshizhuanhuan\n详细许可信息见项目 THIRD_PARTY_NOTICES.md。", 12f, UiKit.TEXT_3).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 8), 0, 0)
            setLineSpacing(0f, 1.2f)
        })
        root.addView(credits)

        root.addView(UiKit.spacer(this, 18))
        root.addView(UiKit.text(this, "请仅处理你有权访问和转换的本地音频文件。", 11.5f, UiKit.TEXT_3).apply {
            gravity = Gravity.CENTER
        })
        return scroll
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, pickFileCode)
    }

    private fun showIgnoredFormatsDialog() {
        val options = IgnoredFormatPreferences.options
        val selected = IgnoredFormatPreferences.selectedIds(this).toMutableSet()
        val labels = options.map { it.label }.toTypedArray()
        val checked = BooleanArray(options.size) { options[it].id in selected }

        AlertDialog.Builder(this)
            .setTitle("选择批量扫描要忽略的格式")
            .setMessage("勾选后的格式不会加入磁盘扫描结果，也不会进入一键批量转换。单文件转换和音频剪辑不受影响。")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val id = options[which].id
                if (isChecked) selected += id else selected -= id
            }
            .setNeutralButton("全部取消") { _, _ ->
                IgnoredFormatPreferences.saveSelectedIds(this, emptySet())
                onIgnoredFormatsChanged()
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                IgnoredFormatPreferences.saveSelectedIds(this, selected)
                onIgnoredFormatsChanged()
            }
            .show()
    }

    private fun onIgnoredFormatsChanged() {
        ignoreFormatsButton.text = "⊘   忽略格式 · ${IgnoredFormatPreferences.summary(this)}"
        scannedBatch = emptyList()
        batchConvertButton.isEnabled = false
        batchConvertButton.alpha = 0.45f
        batchText.text = "忽略格式已更新\n请重新扫描目录以应用新规则"
        status.text = "状态：忽略格式已更新 · 等待重新扫描"
    }

    private fun chooseScanFolder() {
        AlertDialog.Builder(this)
            .setTitle("扫描磁盘")
            .setMessage("请选择要扫描的目录。选择“内部存储”根目录即可扫描系统允许访问的整个磁盘。Android 11+ 会限制 Android/data、Android/obb 等受保护目录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("选择目录") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                }
                startActivityForResult(intent, pickTreeCode)
            }
            .show()
    }

    @Deprecated("Deprecated in Android API but kept to avoid adding a UI framework dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            pickFileCode -> handleSingleFile(uri, data.flags)
            pickTreeCode -> handleScanTree(uri, data.flags)
        }
    }

    private fun handleSingleFile(uri: Uri, dataFlags: Int) {
        try {
            val flags = dataFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Throwable) {}
        status.text = "状态：正在读取文件…"
        Thread {
            try {
                val prepared = AudioFileManager.prepareInput(this, uri)
                selected = prepared
                runOnUiThread {
                    selectedText.text = "${prepared.displayName}\n${AudioFormatDetector.label(prepared.displayName)}  ·  ${formatSize(prepared.size)}"
                    status.text = "状态：文件已准备"
                }
            } catch (t: Throwable) {
                runOnUiThread { status.text = "读取失败：${t.message}" }
            }
        }.start()
    }

    private fun handleScanTree(uri: Uri, dataFlags: Int) {
        try {
            val flags = dataFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Throwable) {}

        scannedBatch = emptyList()
        batchConvertButton.isEnabled = false
        batchConvertButton.alpha = 0.45f
        batchText.text = "正在扫描目录…\n请稍候，不需要逐个选择文件"
        status.text = "状态：开始扫描磁盘…"

        Thread {
            try {
                var skippedIgnored = 0
                val files = StorageAudioScanner.scan(this, uri) { progress ->
                    skippedIgnored = progress.skippedIgnored
                    if (progress.visitedFiles % 80 == 0 || progress.foundAudio > 0 || progress.skippedIgnored > 0) {
                        runOnUiThread {
                            status.text = "状态：扫描中 · 已检查 ${progress.visitedFiles} 个文件 · 可处理 ${progress.foundAudio} 首 · 已忽略 ${progress.skippedIgnored} 首"
                        }
                    }
                }
                scannedBatch = files
                val encrypted = files.count { AudioFormatDetector.isEncrypted(it.displayName) }
                val normal = files.size - encrypted
                val totalBytes = files.filter { it.size > 0 }.sumOf { it.size }
                runOnUiThread {
                    if (files.isEmpty()) {
                        batchText.text = if (skippedIgnored > 0) {
                            "扫描完成，没有需要批量转换的文件\n按当前规则已忽略 $skippedIgnored 首"
                        } else {
                            "扫描完成，但没有发现支持的音乐文件\n可尝试选择更上层目录或内部存储根目录"
                        }
                        status.text = "状态：扫描完成 · 未发现可处理文件 · 已忽略 $skippedIgnored 首"
                        batchConvertButton.isEnabled = false
                        batchConvertButton.alpha = 0.45f
                    } else {
                        batchText.text = "已发现 ${files.size} 个可处理文件\n加密 $encrypted · 普通 $normal · ${formatSize(totalBytes)}\n按规则已忽略 $skippedIgnored 首"
                        status.text = "状态：扫描完成 · ${files.size} 个文件等待批量转换 · 已忽略 $skippedIgnored 首"
                        batchConvertButton.isEnabled = true
                        batchConvertButton.alpha = 1f
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    batchText.text = "扫描失败\n${t.message ?: t.javaClass.simpleName}"
                    status.text = "状态：扫描失败"
                    batchConvertButton.isEnabled = false
                    batchConvertButton.alpha = 0.45f
                }
            }
        }.start()
    }

    private fun showConvertDialog() {
        val item = selected ?: run { toast("请先选择音乐文件"); return }
        val formats = AudioOutputFormat.values()
        AlertDialog.Builder(this)
            .setTitle("选择输出格式")
            .setItems(formats.map { it.label }.toTypedArray()) { _, which -> runConversion(item, formats[which]) }
            .show()
    }

    private fun runConversion(item: PreparedAudio, target: AudioOutputFormat) {
        status.text = "状态：正在处理 ${item.displayName} → ${target.label}…"
        Thread {
            val result = ConversionEngine(this).convert(item, target)
            runOnUiThread {
                if (!result.success) {
                    status.text = "处理失败：${result.error}"
                    return@runOnUiThread
                }
                status.text = "完成：Music/MusicConverter/${result.outputName}"
                if (deleteAsk.isChecked) showOriginalFileActions(item, result)
            }
        }.start()
    }

    private fun showBatchConvertDialog() {
        if (batchRunning) {
            toast("批量任务正在运行")
            return
        }

        val files = scannedBatch
        if (files.isEmpty()) {
            toast("请先扫描磁盘")
            return
        }

        val formats = AudioOutputFormat.values()
        val encrypted = files.count { AudioFormatDetector.isEncrypted(it.displayName) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 22), UiKit.dp(this@MainActivity, 4), UiKit.dp(this@MainActivity, 22), 0)
        }
        content.addView(UiKit.text(this, "请选择统一输出格式", 13f, UiKit.TEXT_2, true))

        val formatGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, UiKit.dp(this@MainActivity, 8), 0, 0)
        }
        formats.forEach { format ->
            val rb = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                tag = format
                text = if (format == AudioOutputFormat.MP3) "${format.label}（推荐）" else format.label
                textSize = 14f
                setTextColor(UiKit.TEXT)
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(UiKit.ACCENT, UiKit.TEXT_3)
                )
                isChecked = format == AudioOutputFormat.MP3
            }
            formatGroup.addView(rb)
        }
        content.addView(formatGroup)

        val recommendedWorkers = BatchConversionService.recommendedParallelism()
        content.addView(UiKit.text(this, "并行转换任务数", 13f, UiKit.TEXT_2, true).apply {
            setPadding(0, UiKit.dp(this@MainActivity, 14), 0, 0)
        })
        val workerGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0)
        }
        (1..4).forEach { workers ->
            val rb = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                tag = workers
                text = when (workers) {
                    1 -> "1 路（兼容 / 省电）"
                    recommendedWorkers -> "$workers 路（推荐）"
                    4 -> "4 路（高性能 / 高发热）"
                    else -> "$workers 路并行"
                }
                textSize = 13.5f
                setTextColor(UiKit.TEXT)
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(UiKit.ACCENT, UiKit.TEXT_3)
                )
                isChecked = workers == recommendedWorkers
            }
            workerGroup.addView(rb)
        }
        content.addView(workerGroup)
        content.addView(UiKit.text(
            this,
            "2～3 路通常更适合手机。FFmpeg 单个任务本身也会使用多核，4 路可能更热，且在慢速存储上不一定更快。",
            11.5f,
            UiKit.TEXT_3
        ).apply { setPadding(0, UiKit.dp(this@MainActivity, 5), 0, 0) })

        val replaceSource = CheckBox(this).apply {
            text = "用转换结果置换源文件"
            textSize = 14f
            setTextColor(UiKit.TEXT)
            isChecked = false
            setPadding(0, UiKit.dp(this@MainActivity, 12), 0, 0)
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(UiKit.ACCENT, UiKit.TEXT_3)
            )
        }
        content.addView(replaceSource)
        content.addView(UiKit.text(
            this,
            "置换模式：只有转换成功后才尝试在原位置写入新音频并按目标格式重命名。若文件提供方不支持写入/重命名，会保留源文件和已生成的输出，不会强制删除。",
            11.5f,
            UiKit.TEXT_3
        ).apply { setPadding(0, UiKit.dp(this@MainActivity, 5), 0, 0) })
        content.addView(UiKit.text(
            this,
            "共 ${files.size} 个 · 加密 $encrypted · 普通 ${files.size - encrypted}\n默认输出：Music/MusicConverter",
            12f,
            UiKit.TEXT_2
        ).apply { setPadding(0, UiKit.dp(this@MainActivity, 14), 0, 0) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("批量转换 · ${files.size} 个文件")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认转换", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isAllCaps = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isAllCaps = false
                setOnClickListener {
                    val checkedId = formatGroup.checkedRadioButtonId
                    val checked = formatGroup.findViewById<RadioButton>(checkedId)
                    val selectedFormat = (checked?.tag as? AudioOutputFormat) ?: AudioOutputFormat.MP3
                    val replace = replaceSource.isChecked
                    val workerChecked = workerGroup.findViewById<RadioButton>(workerGroup.checkedRadioButtonId)
                    val workers = (workerChecked?.tag as? Int) ?: recommendedWorkers
                    dialog.dismiss()
                    runBatchConversion(files, selectedFormat, replace, workers)
                }
            }
        }
        dialog.show()
    }

    private fun runBatchConversion(files: List<ScannedAudio>, target: AudioOutputFormat, replaceSource: Boolean, parallelism: Int) {
        if (batchRunning || BatchConversionService.snapshot().running) {
            toast("批量任务正在运行")
            return
        }
        requestNotificationPermissionIfNeeded()

        batchRunning = true
        batchConvertButton.isEnabled = false
        batchConvertButton.alpha = 0.55f
        if (::batchPauseButton.isInitialized) {
            batchPauseButton.isEnabled = true
            batchPauseButton.alpha = 1f
            batchPauseButton.text = "Ⅱ   完成当前任务后暂停"
        }
        batchText.text = "正在启动后台转换…\n目标格式：${target.label} · ${parallelism} 路并行${if (replaceSource) " · 置换源文件" else " · 保留源文件"}"
        status.text = "状态：正在启动后台转换服务…"

        val started = BatchConversionService.start(this, files, target, replaceSource, parallelism)
        if (!started) {
            batchRunning = false
            batchConvertButton.isEnabled = scannedBatch.isNotEmpty()
            batchConvertButton.alpha = if (scannedBatch.isNotEmpty()) 1f else 0.45f
            status.text = "状态：后台任务启动失败"
            toast("无法启动后台转换，请检查系统后台限制")
        }
    }

    private fun applyBatchSnapshot(snapshot: BatchProgressSnapshot, showResult: Boolean) {
        if (!::batchText.isInitialized || !::batchConvertButton.isInitialized || !::status.isInitialized) return
        batchRunning = snapshot.running
        if (snapshot.running) {
            batchConvertButton.isEnabled = false
            batchConvertButton.alpha = 0.55f

            if (::batchPauseButton.isInitialized) {
                batchPauseButton.isEnabled = true
                batchPauseButton.alpha = 1f
                batchPauseButton.text = when {
                    snapshot.paused -> "▶   继续批量转换"
                    snapshot.pausing -> "▶   取消暂停并继续"
                    else -> "Ⅱ   完成当前任务后暂停"
                }
            }

            val replaceText = if (snapshot.replaceSource) " · 已置换 ${snapshot.replaced}" else ""
            batchText.text = when {
                snapshot.paused -> {
                    "批量任务已暂停 ${snapshot.current} / ${snapshot.total}\n剩余 ${snapshot.total - snapshot.current} 个 · 成功 ${snapshot.success} · 失败 ${snapshot.failed}$replaceText\n点击“继续批量转换”恢复"
                }
                snapshot.pausing -> {
                    "正在等待暂停 ${snapshot.current} / ${snapshot.total}\n当前仍有 ${snapshot.active} 个转换运行 · 完成后自动暂停$replaceText\n${snapshot.currentName}"
                }
                else -> {
                    "正在后台转换 ${snapshot.current} / ${snapshot.total}\n${snapshot.parallelism} 路并行 · 活跃 ${snapshot.active} · 成功 ${snapshot.success} · 失败 ${snapshot.failed}$replaceText\n${snapshot.currentName}"
                }
            }
            status.text = when {
                snapshot.paused -> "状态：批量转换已暂停 · ${snapshot.current}/${snapshot.total}"
                snapshot.pausing -> "状态：等待当前 ${snapshot.active} 个任务完成后暂停"
                else -> "状态：并行转换 ${snapshot.current}/${snapshot.total} · 活跃 ${snapshot.active}/${snapshot.parallelism}"
            }
            return
        }

        if (::batchPauseButton.isInitialized) {
            batchPauseButton.isEnabled = false
            batchPauseButton.alpha = 0.45f
            batchPauseButton.text = "Ⅱ   完成当前任务后暂停"
        }

        if (snapshot.done) {
            if (snapshot.replaceSource) {
                scannedBatch = emptyList()
                batchConvertButton.isEnabled = false
                batchConvertButton.alpha = 0.45f
                batchText.text = if (snapshot.cancelled) {
                    "批量任务已停止\n成功 ${snapshot.success} · 失败 ${snapshot.failed} · 已置换 ${snapshot.replaced}\n请重新扫描目录以刷新文件列表"
                } else {
                    "批量任务完成\n成功 ${snapshot.success} · 失败 ${snapshot.failed} · 已置换 ${snapshot.replaced} · 置换失败 ${snapshot.replaceFailed}\n请重新扫描目录以刷新文件列表"
                }
            } else {
                batchConvertButton.isEnabled = scannedBatch.isNotEmpty()
                batchConvertButton.alpha = if (scannedBatch.isNotEmpty()) 1f else 0.45f
                batchText.text = if (snapshot.cancelled) {
                    "批量任务已停止\n成功 ${snapshot.success} · 失败 ${snapshot.failed} · 共 ${snapshot.total} 个"
                } else {
                    "批量任务完成\n成功 ${snapshot.success} · 失败 ${snapshot.failed} · 共 ${snapshot.total} 个"
                }
            }
            status.text = if (snapshot.cancelled) {
                "状态：批量任务已停止 · 成功 ${snapshot.success} · 失败 ${snapshot.failed}"
            } else {
                "状态：批量转换完成 · 成功 ${snapshot.success} · 失败 ${snapshot.failed}"
            }
            if (showResult && snapshot.message.isNotBlank() && !isFinishing) {
                AlertDialog.Builder(this)
                    .setTitle(if (snapshot.cancelled) "批量任务已停止" else "批量任务结果")
                    .setMessage(snapshot.message)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1401)
        }
    }

    private fun requestBackgroundBatteryAccess() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("当前 Android 版本无需设置电池优化")
            return
        }
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("后台耗电已允许")
                .setMessage("系统已允许 MusicConverter 忽略电池优化。批量转换仍会使用前台服务和常驻进度通知。\n\n在 MIUI / HyperOS 上，如仍被清理，可再到应用信息中允许后台运行或将电池策略设为“无限制”。")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("允许后台耗电")
            .setMessage("长时间批量转换时，系统省电策略可能暂停或结束任务。下一步会打开系统授权页，请允许 MusicConverter 忽略电池优化。\n\n转换期间仍会显示前台服务通知，并只在任务运行时保持 CPU 唤醒。")
            .setNegativeButton("取消", null)
            .setPositiveButton("去授权") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Throwable) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (_: Throwable) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                }
            }
            .show()
    }

    private fun openEditor() {
        val item = selected ?: run { toast("请先选择音乐文件"); return }
        status.text = "状态：正在准备编辑…"
        Thread {
            val (file, error) = ConversionEngine(this).prepareEditable(item)
            if (file == null) {
                runOnUiThread { status.text = "无法准备编辑：$error" }
                return@Thread
            }
            runOnUiThread {
                status.text = "状态：进入剪辑器"
                startActivity(Intent(this, AudioEditorActivity::class.java).apply {
                    putExtra("inputPath", file.absolutePath)
                    putExtra("displayName", if (AudioFormatDetector.isEncrypted(item.displayName)) file.name else item.displayName)
                    putExtra("sourceDisplayName", item.displayName)
                    putExtra("sourceUri", item.originalUri.toString())
                    putExtra("sourceBackupPath", item.localFile.absolutePath)
                })
            }
        }.start()
    }

    private fun scanMusicApps() {
        val apps = MusicAppScanner.scan(this)
        val text = if (apps.isEmpty()) "未发现已知音乐 APP。\nAndroid 11+ 只会显示 Manifest <queries> 中声明的应用。" else apps.joinToString("\n\n") {
            "${it.label}\n${it.packageName}\n常见目录：${it.commonPaths.joinToString()}"
        }
        AlertDialog.Builder(this).setTitle("已识别音乐 APP (${apps.size})").setMessage(text).setPositiveButton("确定", null).show()
    }

    private fun showHistory() {
        status.text = "状态：读取历史…"
        Thread {
            val items = HistoryRepository(this).recent()
            val message = if (items.isEmpty()) "暂无记录" else items.joinToString("\n\n") {
                "${it.operation} · ${it.status}\n${it.inputName}${if (it.outputName.isNotBlank()) " → ${it.outputName}" else ""}"
            }
            runOnUiThread {
                status.text = "状态：等待任务"
                AlertDialog.Builder(this).setTitle("处理历史").setMessage(message).setPositiveButton("确定", null).show()
            }
        }.start()
    }

    private fun showOriginalFileActions(item: PreparedAudio, result: ConversionResult) {
        val outputUri = result.outputUri
        val options = arrayOf("保留源文件", "删除源文件", "用转换结果置换源文件")
        AlertDialog.Builder(this)
            .setTitle("处理完成")
            .setMessage("已生成：${result.outputName}\n请选择源文件处理方式。")
            .setItems(options) { _, which ->
                when (which) {
                    1 -> {
                        val ok = DeleteManager.delete(this, item.originalUri)
                        toast(if (ok) "源文件已删除" else "无法删除源文件，请检查文件提供方权限")
                    }
                    2 -> {
                        if (outputUri == null) {
                            toast("找不到转换结果，未修改源文件")
                            return@setItems
                        }
                        val replace = AudioFileManager.replaceOriginal(
                            this,
                            item.originalUri,
                            item.displayName,
                            outputUri,
                            result.outputName,
                            item.localFile
                        )
                        if (replace.success) {
                            selected = null
                            selectedText.text = "源文件已被转换结果置换\n请重新选择文件继续处理"
                            status.text = "状态：已置换源文件 · ${result.outputName}"
                            toast("已用转换结果置换源文件")
                        } else {
                            toast("置换失败：${replace.message}；源文件已保留")
                        }
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "未知"
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
