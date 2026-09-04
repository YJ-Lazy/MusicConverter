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
import android.os.Environment
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
import com.musicconverter.miui.scanner.FullStorageAudioScanner
import com.musicconverter.miui.scanner.MusicAppScanner
import com.musicconverter.miui.scanner.ScannedAudio
import com.musicconverter.miui.scanner.StorageAudioScanner
import com.musicconverter.miui.service.BatchConversionService
import com.musicconverter.miui.service.BatchProgressSnapshot
import com.musicconverter.miui.ui.UiKit
import com.musicconverter.miui.ui.ThemePreferences
import com.musicconverter.miui.update.RemoteUpdateManager
import com.musicconverter.miui.update.UpdateCheckResult

class MainActivity : Activity() {
    private val pickFileCode = 1001
    private val pickTreeCode = 1002

    /**
     * 直接读取当前已安装 APK 的真实版本信息，
     * 不依赖 Gradle 自动生成的版本常量类。
     */
    private val appVersionCode: Int by lazy {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        info.longVersionCode.toInt()
    }

    private val appVersionName: String by lazy {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }

    private var selected: PreparedAudio? = null
    private var scannedBatch: List<ScannedAudio> = emptyList()
    @Volatile private var batchRunning = false

    private lateinit var selectedText: TextView
    private lateinit var batchText: TextView
    private lateinit var batchConvertButton: TextView
    private lateinit var batchPauseButton: TextView
    private lateinit var ignoreFormatsButton: TextView
    private lateinit var allFilesAccessButton: TextView
    private lateinit var fullStorageScanButton: TextView
    private lateinit var status: TextView
    private lateinit var deleteAsk: CheckBox
    private var batchReceiverRegistered = false
    private var mandatoryUpdateDialog: AlertDialog? = null
    @Volatile private var mandatoryCheckRunning = false

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
        UiKit.applyTheme(this)
        window.statusBarColor = UiKit.BG
        window.navigationBarColor = UiKit.BG
        window.decorView.systemUiVisibility =
            if (ThemePreferences.isDark(this)) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        buildUi()

        // 如果之前已经确认存在强制更新，启动时立即恢复门禁，防止断网绕过。
        RemoteUpdateManager.cachedMandatoryUpdate(this, appVersionCode)?.let {
            showMandatoryUpdateDialog(it)
        }

        // 强制更新检查每次启动都执行，不受普通“每天一次”检查节流影响。
        checkMandatoryUpdateOnLaunch()

        // 普通可选更新仍按原来的 24 小时间隔检查。
        maybeCheckRemoteUpdate(manual = false)
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

    override fun onResume() {
        super.onResume()

        // 用户从夸克 / 蓝奏云返回但尚未安装新版时，继续阻止进入 APP。
        RemoteUpdateManager.cachedMandatoryUpdate(this, appVersionCode)?.let {
            showMandatoryUpdateDialog(it)
        }

        // 从系统“所有文件访问权限”页面返回时，刷新按钮状态。
        refreshAllFilesAccessUi()
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
            setPadding(UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 8), UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 6))
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
            setPadding(UiKit.dp(this@MainActivity, 10), UiKit.dp(this@MainActivity, 8), UiKit.dp(this@MainActivity, 10), UiKit.dp(this@MainActivity, 10))
            background = UiKit.rounded(UiKit.SURFACE, 28, this@MainActivity)
            elevation = UiKit.dp(this@MainActivity, if (ThemePreferences.isDark(this@MainActivity)) 2 else 4).toFloat()
        }
        UiKit.margins(navigation, 12, 4, 12, 12)

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
            setPadding(UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 32))
        }
        scroll.addView(root)
        return scroll to root
    }

    private fun buildHomePage(): ScrollView {
        val (scroll, root) = pageRoot()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 8),
                UiKit.dp(this@MainActivity, 6),
                UiKit.dp(this@MainActivity, 8),
                0
            )
        }
        top.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(UiKit.text(this@MainActivity, "音乐工作台", 35f, UiKit.TEXT, true))
                addView(
                    UiKit.text(
                        this@MainActivity,
                        "解密 · 转换 · 剪辑，一次专注一首音乐",
                        12.5f,
                        UiKit.TEXT_3
                    ).apply {
                        setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0)
                    }
                )
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        top.addView(UiKit.pill(this, "LOCAL · OFFLINE"))
        root.addView(top)

        // 保留之前要求的“主操作区位于首屏中央”。
        val screenHeightDp = (
            resources.displayMetrics.heightPixels / resources.displayMetrics.density
        ).toInt()
        val heroTopSpaceDp = (screenHeightDp * 0.085f).toInt().coerceIn(46, 86)
        root.addView(UiKit.spacer(this, heroTopSpaceDp))

        val fileHero = UiKit.card(this, 28).apply {
            background = UiKit.rounded(
                UiKit.primaryContainer(this@MainActivity),
                28,
                this@MainActivity
            )
            setPadding(
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 20)
            )
        }

        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val musicIcon = UiKit.text(
            this,
            "♫",
            28f,
            Color.WHITE,
            true
        ).apply {
            gravity = Gravity.CENTER
            background = UiKit.rounded(UiKit.ACCENT_2, 18, this@MainActivity)
        }
        heroRow.addView(
            musicIcon,
            LinearLayout.LayoutParams(
                UiKit.dp(this, 56),
                UiKit.dp(this, 56)
            )
        )

        selectedText = UiKit.text(
            this,
            "尚未选择文件\n选择一首音乐开始处理",
            14.5f,
            UiKit.onPrimaryContainer(this)
        ).apply {
            setLineSpacing(0f, 1.18f)
            setPadding(UiKit.dp(this@MainActivity, 16), 0, 0, 0)
        }
        heroRow.addView(
            selectedText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        heroRow.addView(UiKit.badge(this, "当前音乐", true))
        fileHero.addView(heroRow)
        root.addView(fileHero)

        root.addView(UiKit.spacer(this, 14))
        val selectButton = UiKit.wideButton(this, "+", "选择音乐文件", true).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            minimumHeight = UiKit.dp(this@MainActivity, 58)
        }
        root.addView(
            selectButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 58)
            )
        )

        root.addView(UiKit.spacer(this, 28))
        root.addView(UiKit.sectionLabel(this, "功能中心"))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val convertButton = UiKit.featureTile(
            this,
            "⇄",
            "转换格式",
            "MP3 · FLAC · M4A · WAV",
            "常用",
            true
        )
        val editButton = UiKit.featureTile(
            this,
            "✂",
            "编辑 / 剪辑",
            "波形 · 选区 · 试听 · 导出"
        )
        actionRow.addView(
            convertButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = UiKit.dp(this@MainActivity, 6)
            }
        )
        actionRow.addView(
            editButton,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                leftMargin = UiKit.dp(this@MainActivity, 6)
            }
        )
        root.addView(actionRow)

        root.addView(UiKit.spacer(this, 20))
        root.addView(UiKit.sectionLabel(this, "文件处理"))

        val policyCard = UiKit.groupCard(this)
        deleteAsk = CheckBox(this).apply {
            text = "处理完成后询问源文件操作"
            textSize = 14f
            setTextColor(UiKit.TEXT)
            isChecked = true
            minimumHeight = UiKit.dp(this@MainActivity, 58)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 18),
                0,
                UiKit.dp(this@MainActivity, 18),
                0
            )
            buttonTintList = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(UiKit.ACCENT, UiKit.TEXT_3)
            )
        }
        policyCard.addView(deleteAsk)
        policyCard.addView(UiKit.groupDivider(this, 18))
        policyCard.addView(
            UiKit.groupRow(
                this,
                "⌁",
                "本地处理",
                "解密、转换与剪辑均在设备端执行；默认输出到 Music/MusicConverter",
                "离线"
            )
        )
        root.addView(policyCard)

        selectButton.setOnClickListener { chooseFile() }
        convertButton.setOnClickListener { showConvertDialog() }
        editButton.setOnClickListener { openEditor() }
        return scroll
    }

    private fun buildBatchPage(): ScrollView {
        val (scroll, root) = pageRoot()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 8),
                UiKit.dp(this@MainActivity, 6),
                UiKit.dp(this@MainActivity, 8),
                0
            )
            addView(UiKit.text(this@MainActivity, "批量处理", 34f, UiKit.TEXT, true))
            addView(
                UiKit.text(
                    this@MainActivity,
                    "扫描目录 · 过滤格式 · 并行处理",
                    12.5f,
                    UiKit.TEXT_3
                ).apply {
                    setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0)
                }
            )
        }
        root.addView(header)

        root.addView(UiKit.spacer(this, 20))
        root.addView(UiKit.sectionLabel(this, "扫描"))

        val scanCard = UiKit.groupCard(this)
        val scanButton = UiKit.groupButton(this, "⌕", "选择目录扫描")
        scanCard.addView(scanButton)

        fullStorageScanButton = UiKit.groupButton(
            this,
            "◎",
            "全盘扫描 · 已授权"
        ).apply {
            visibility = if (hasAllFilesAccess()) View.VISIBLE else View.GONE
        }
        if (hasAllFilesAccess()) {
            scanCard.addView(UiKit.groupDivider(this))
        }
        scanCard.addView(fullStorageScanButton)

        ignoreFormatsButton = UiKit.groupButton(
            this,
            "⊘",
            "扫描过滤 · ${IgnoredFormatPreferences.summary(this)}"
        )
        scanCard.addView(UiKit.groupDivider(this))
        scanCard.addView(ignoreFormatsButton)
        root.addView(scanCard)

        root.addView(UiKit.spacer(this, 20))
        root.addView(UiKit.sectionLabel(this, "任务状态"))

        val batchCard = UiKit.card(this, 26).apply {
            background = UiKit.rounded(
                UiKit.primaryContainer(this@MainActivity),
                26,
                this@MainActivity
            )
        }
        val batchHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val batchIcon = UiKit.text(
            this,
            "≋",
            24f,
            Color.WHITE,
            true
        ).apply {
            gravity = Gravity.CENTER
            background = UiKit.rounded(UiKit.ACCENT_2, 16, this@MainActivity)
        }
        batchHead.addView(
            batchIcon,
            LinearLayout.LayoutParams(
                UiKit.dp(this, 50),
                UiKit.dp(this, 50)
            )
        )
        batchText = UiKit.text(
            this,
            "尚未扫描目录\n选择目录后即可批量处理",
            13.5f,
            UiKit.onPrimaryContainer(this)
        ).apply {
            setLineSpacing(0f, 1.18f)
            setPadding(UiKit.dp(this@MainActivity, 14), 0, 0, 0)
        }
        batchHead.addView(
            batchText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        batchCard.addView(batchHead)

        batchConvertButton = UiKit.wideButton(
            this,
            "⇉",
            "一键转换扫描结果",
            true
        ).apply {
            isEnabled = false
            alpha = 0.45f
        }
        UiKit.margins(batchConvertButton, 0, 16, 0, 0)
        batchCard.addView(batchConvertButton)

        batchPauseButton = UiKit.wideButton(
            this,
            "Ⅱ",
            "完成当前任务后暂停"
        ).apply {
            isEnabled = false
            alpha = 0.45f
        }
        UiKit.margins(batchPauseButton, 0, 10, 0, 0)
        batchCard.addView(batchPauseButton)
        root.addView(batchCard)

        root.addView(UiKit.spacer(this, 20))
        root.addView(UiKit.sectionLabel(this, "工具"))

        val toolsCard = UiKit.groupCard(this)

        allFilesAccessButton = UiKit.groupButton(
            this,
            "▣",
            allFilesAccessButtonTitle()
        )
        toolsCard.addView(allFilesAccessButton)
        toolsCard.addView(UiKit.groupDivider(this))

        val backgroundButton = UiKit.groupButton(
            this,
            "⚡",
            "后台耗电 / 电池优化"
        )
        toolsCard.addView(backgroundButton)
        toolsCard.addView(UiKit.groupDivider(this))

        val appScanButton = UiKit.groupButton(
            this,
            "⌁",
            "扫描已安装音乐 APP"
        )
        toolsCard.addView(appScanButton)
        toolsCard.addView(UiKit.groupDivider(this))

        val themeButton = UiKit.groupButton(
            this,
            if (ThemePreferences.isDark(this)) "☾" else "☀",
            "主题模式 · ${ThemePreferences.label(this)}"
        )
        toolsCard.addView(themeButton)
        toolsCard.addView(UiKit.groupDivider(this))

        val historyButton = UiKit.groupButton(
            this,
            "◷",
            "转换 / 剪辑历史"
        )
        toolsCard.addView(historyButton)
        toolsCard.addView(UiKit.groupDivider(this))

        val diagnosticsButton = UiKit.groupButton(
            this,
            "✓",
            "FFmpeg 运行检测"
        )
        toolsCard.addView(diagnosticsButton)

        root.addView(toolsCard)

        root.addView(UiKit.spacer(this, 18))
        val tip = UiKit.card(this, 22)
        tip.addView(UiKit.text(this, "扫描说明", 14f, UiKit.TEXT, true))
        tip.addView(
            UiKit.text(
                this,
                "默认使用 SAF 选择目录。授权“所有文件访问”后会额外显示全盘扫描，由用户主动选择是否扫描全部共享存储。扫描过滤规则始终生效，并自动跳过 Music/MusicConverter 输出目录。",
                11.5f,
                UiKit.TEXT_3
            ).apply {
                setPadding(0, UiKit.dp(this@MainActivity, 8), 0, 0)
                setLineSpacing(0f, 1.18f)
            }
        )
        root.addView(tip)

        scanButton.setOnClickListener { chooseScanFolder() }
        fullStorageScanButton.setOnClickListener { confirmFullStorageScan() }
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
        allFilesAccessButton.setOnClickListener { requestOptionalAllFilesAccess() }
        backgroundButton.setOnClickListener { requestBackgroundBatteryAccess() }
        appScanButton.setOnClickListener { scanMusicApps() }
        themeButton.setOnClickListener {
            ThemePreferences.toggle(this)
            UiKit.applyTheme(this)
            recreate()
        }
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

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 8),
                UiKit.dp(this@MainActivity, 6),
                UiKit.dp(this@MainActivity, 8),
                0
            )
            addView(UiKit.text(this@MainActivity, "介绍", 34f, UiKit.TEXT, true))
            addView(
                UiKit.text(
                    this@MainActivity,
                    "应用信息 · 能力 · 更新与隐私",
                    12.5f,
                    UiKit.TEXT_3
                ).apply {
                    setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0)
                }
            )
        }
        root.addView(header)

        root.addView(UiKit.spacer(this, 18))

        val intro = UiKit.card(this, 28).apply {
            background = UiKit.rounded(
                UiKit.primaryContainer(this@MainActivity),
                28,
                this@MainActivity
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 20)
            )
        }

        val iconWrap = FrameLayout(this).apply {
            background = UiKit.rounded(UiKit.ACCENT_2, 18, this@MainActivity)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        iconWrap.addView(
            icon,
            FrameLayout.LayoutParams(
                UiKit.dp(this, 48),
                UiKit.dp(this, 48)
            ).apply {
                gravity = Gravity.CENTER
            }
        )
        intro.addView(
            iconWrap,
            LinearLayout.LayoutParams(
                UiKit.dp(this, 58),
                UiKit.dp(this, 58)
            )
        )

        intro.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(this@MainActivity, 16), 0, 8, 0)
                addView(
                    UiKit.text(
                        this@MainActivity,
                        "MusicConverter",
                        19f,
                        UiKit.onPrimaryContainer(this@MainActivity),
                        true
                    )
                )
                addView(
                    UiKit.text(
                        this@MainActivity,
                        "本地音乐解密 · 转换 · 编辑工具",
                        11.5f,
                        UiKit.onPrimaryContainer(this@MainActivity)
                    ).apply {
                        alpha = 0.78f
                        setPadding(0, UiKit.dp(this@MainActivity, 4), 0, 0)
                    }
                )
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )
        intro.addView(UiKit.badge(this, "v${appVersionName}", true))
        root.addView(intro)

        root.addView(UiKit.spacer(this, 18))
        root.addView(UiKit.sectionLabel(this, "能力"))

        val capability = UiKit.groupCard(this)
        capability.addView(
            UiKit.groupRow(
                this,
                "⇄",
                "格式转换",
                "MP3 / FLAC / M4A / WAV / OGG"
            )
        )
        capability.addView(UiKit.groupDivider(this))
        capability.addView(
            UiKit.groupRow(
                this,
                "◇",
                "加密格式",
                "NCM / QMC / KGM / KWM 等"
            )
        )
        capability.addView(UiKit.groupDivider(this))
        capability.addView(
            UiKit.groupRow(
                this,
                "✂",
                "音频剪辑",
                "波形、选区、试听、暂停与导出"
            )
        )
        capability.addView(UiKit.groupDivider(this))
        capability.addView(
            UiKit.groupRow(
                this,
                "≋",
                "批量处理",
                "SAF 目录扫描或授权后的全盘扫描"
            )
        )
        capability.addView(UiKit.groupDivider(this))
        capability.addView(
            UiKit.groupRow(
                this,
                "⚡",
                "后台转换",
                "前台服务显示进度，并可申请忽略电池优化"
            )
        )
        root.addView(capability)

        root.addView(UiKit.spacer(this, 18))
        root.addView(UiKit.sectionLabel(this, "软件更新"))

        val updateCard = UiKit.card(this, 24)
        updateCard.addView(
            UiKit.text(
                this,
                "当前版本  ·  v${appVersionName}",
                14.5f,
                UiKit.TEXT,
                true
            )
        )
        updateCard.addView(
            UiKit.text(
                this,
                "GitHub 优先 · 腾讯文档备用\nAPK 下载：夸克 / 蓝奏云",
                11.5f,
                UiKit.TEXT_3
            ).apply {
                setPadding(0, UiKit.dp(this@MainActivity, 7), 0, 0)
                setLineSpacing(0f, 1.18f)
            }
        )
        val checkUpdateButton = UiKit.wideButton(this, "↻", "检查更新", true)
        UiKit.margins(checkUpdateButton, 0, 14, 0, 0)
        updateCard.addView(checkUpdateButton)
        root.addView(updateCard)
        checkUpdateButton.setOnClickListener { maybeCheckRemoteUpdate(manual = true) }

        root.addView(UiKit.spacer(this, 18))
        root.addView(UiKit.sectionLabel(this, "信息"))

        val infoCard = UiKit.groupCard(this)
        infoCard.addView(
            UiKit.groupRow(
                this,
                "▣",
                "隐私与文件",
                "核心处理在本机完成；默认通过 Android SAF 访问用户主动选择的文件"
            )
        )
        infoCard.addView(UiKit.groupDivider(this))
        infoCard.addView(
            UiKit.groupRow(
                this,
                "⌘",
                "开源组件",
                "FFmpeg / FFmpegKit · Chaquopy · Room · music-geshizhuanhuan"
            )
        )
        root.addView(infoCard)

        root.addView(UiKit.spacer(this, 20))
        root.addView(
            UiKit.text(
                this,
                "请仅处理你有权访问和转换的本地音频文件。",
                11.5f,
                UiKit.TEXT_3
            ).apply {
                gravity = Gravity.CENTER
            }
        )
        return scroll
    }

    private fun checkMandatoryUpdateOnLaunch() {
        if (mandatoryCheckRunning) return
        mandatoryCheckRunning = true

        Thread {
            val result = RemoteUpdateManager.check(appVersionCode)
            mandatoryCheckRunning = false

            runOnUiThread {
                when (result) {
                    is UpdateCheckResult.Available -> {
                        if (RemoteUpdateManager.isMandatory(result.info, appVersionCode)) {
                            RemoteUpdateManager.cacheMandatoryUpdate(this, result.info)
                            showMandatoryUpdateDialog(result.info)
                            status.text = "状态：检测到必须更新版本 v${result.info.versionName}"
                        }
                    }
                    is UpdateCheckResult.Latest -> {
                        // 安装版本已经满足远程要求时，清理旧门禁缓存。
                        RemoteUpdateManager.clearMandatoryUpdate(this)
                    }
                    is UpdateCheckResult.Error -> {
                        // 网络失败时：
                        // - 若以前已经确认强制更新，cachedMandatoryUpdate 会继续拦截。
                        // - 若从未确认过强制更新，则允许离线使用，避免 GitHub 故障导致误锁 APP。
                        RemoteUpdateManager.cachedMandatoryUpdate(this, appVersionCode)?.let {
                            showMandatoryUpdateDialog(it)
                        }
                    }
                }
            }
        }.start()
    }

    private fun maybeCheckRemoteUpdate(manual: Boolean) {
        RemoteUpdateManager.clearIgnoredVersionIfInstalled(this, appVersionCode)
        if (!manual && !RemoteUpdateManager.shouldAutoCheck(this)) return

        if (manual) {
            status.text = "状态：正在检查更新 · GitHub 优先…"
        }

        Thread {
            val result = RemoteUpdateManager.check(appVersionCode)
            if (!manual) RemoteUpdateManager.markAutoChecked(this)

            runOnUiThread {
                when (result) {
                    is UpdateCheckResult.Available -> {
                        val mustUpdate = RemoteUpdateManager.isMandatory(
                            result.info,
                            appVersionCode
                        )

                        if (mustUpdate) {
                            RemoteUpdateManager.cacheMandatoryUpdate(this, result.info)
                            showMandatoryUpdateDialog(result.info)
                            status.text = "状态：检测到必须更新版本 v${result.info.versionName}"
                            return@runOnUiThread
                        }

                        val suppressed = !manual &&
                            RemoteUpdateManager.shouldSuppressIgnoredUpdate(
                                this,
                                result.info.versionCode,
                                appVersionCode
                            )
                        if (suppressed) {
                            // 用户明确忽略过更新：自动检查保持安静，直到远程再跨 2 个 versionCode。
                            status.text = "状态：已按设置暂时忽略更新提醒"
                        } else {
                            showRemoteUpdateDialog(result.info)
                            status.text = "状态：发现新版本 v${result.info.versionName}"
                        }
                    }
                    is UpdateCheckResult.Latest -> {
                        RemoteUpdateManager.clearIgnoredVersionIfInstalled(this, appVersionCode)
                        RemoteUpdateManager.clearMandatoryUpdate(this)
                        if (manual) {
                            appDialogBuilder()
                                .setTitle("已经是最新版本")
                                .setMessage("当前版本：v${appVersionName}\nGitHub 暂未发布更高版本。")
                                .setPositiveButton("确定", null)
                                .show()
                        }
                        status.text = "状态：当前已是最新版本"
                    }
                    is UpdateCheckResult.Error -> {
                        if (manual) {
                            appDialogBuilder()
                                .setTitle("检查更新失败")
                                .setMessage("无法读取 GitHub 更新清单。\n\n${result.message}")
                                .setPositiveButton("确定", null)
                                .show()
                        }
                        status.text = "状态：更新检查失败"
                    }
                }
            }
        }.start()
    }

    private fun showRemoteUpdateDialog(info: com.musicconverter.miui.update.RemoteUpdateInfo) {
        val mustUpdate = RemoteUpdateManager.isMandatory(info, appVersionCode)
        if (mustUpdate) {
            RemoteUpdateManager.cacheMandatoryUpdate(this, info)
            showMandatoryUpdateDialog(info)
            return
        }

        val hasQuark = info.quarkUrl.startsWith("https://pan.quark.cn/")
        val hasLanzou = info.lanzouUrl.isNotBlank()

        val message = buildString {
            append("当前版本：v${appVersionName}\n")
            append("最新版本：v${info.versionName}\n")
            append("下载来源：")
            val sources = mutableListOf<String>()
            if (hasQuark) sources += "夸克网盘"
            if (hasLanzou) sources += "蓝奏云"
            append(sources.joinToString(" / "))
            if (hasLanzou && info.lanzouPassword.isNotBlank()) {
                append("\n蓝奏云密码：${info.lanzouPassword}（选择后自动复制）")
            }
            append("\n\n")
            if (info.changelog.isNotBlank()) append(info.changelog)
            append("\n\n选择“忽略更新”后：下一个版本不会再自动提醒；累计发布 2 个新版本后才重新提醒。")
        }

        val builder = appDialogBuilder()
            .setTitle(if (info.title.isBlank()) "发现新版本" else info.title)
            .setMessage(message)
            .setPositiveButton("下载更新") { _, _ ->
                showUpdateDownloadSourceDialog(info)
            }

        builder.setNegativeButton("忽略更新") { _, _ ->
            RemoteUpdateManager.ignoreVersion(this, info.versionCode)
            status.text = "状态：已忽略 v${info.versionName}，累计再发布 2 个版本后提醒"
            toast("已忽略本次更新；再发布 2 个版本后会重新提醒")
        }
        builder.setNeutralButton("稍后", null)
        builder.show()
    }

    private fun showMandatoryUpdateDialog(info: com.musicconverter.miui.update.RemoteUpdateInfo) {
        if (appVersionCode >= maxOf(info.versionCode, info.minSupportedVersionCode)) {
            RemoteUpdateManager.clearMandatoryUpdate(this)
            mandatoryUpdateDialog?.dismiss()
            mandatoryUpdateDialog = null
            return
        }

        if (mandatoryUpdateDialog?.isShowing == true) return

        val message = buildString {
            append("当前版本：v${appVersionName}\n")
            append("必须更新到：v${info.versionName}\n\n")
            if (info.changelog.isNotBlank()) {
                append(info.changelog)
                append("\n\n")
            }
            append("此版本为强制更新。继续使用 MusicConverter 前必须完成更新。\n")
            append("你可以前往下载新版，或退出应用。")
        }

        mandatoryUpdateDialog = appDialogBuilder()
            .setTitle(if (info.title.isBlank()) "必须更新" else info.title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("下载更新") { _, _ ->
                showUpdateDownloadSourceDialog(info)
            }
            .setNegativeButton("退出应用") { _, _ ->
                finishAffinity()
            }
            .create()
            .also { dialog ->
                dialog.setCanceledOnTouchOutside(false)
                dialog.setOnDismissListener {
                    mandatoryUpdateDialog = null

                    // 只要仍未安装要求版本且 Activity 还活着，就重新恢复门禁。
                    if (!isFinishing && !isDestroyed) {
                        RemoteUpdateManager.cachedMandatoryUpdate(
                            this,
                            appVersionCode
                        )?.let { cached ->
                            window.decorView.postDelayed({
                                if (!isFinishing && !isDestroyed) {
                                    showMandatoryUpdateDialog(cached)
                                }
                            }, 250)
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showUpdateDownloadSourceDialog(info: com.musicconverter.miui.update.RemoteUpdateInfo) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (info.quarkUrl.startsWith("https://pan.quark.cn/")) {
            labels += "夸克网盘"
            actions += { openQuarkUpdate(info.quarkUrl) }
        }
        if (info.lanzouUrl.isNotBlank()) {
            labels += if (info.lanzouPassword.isBlank()) {
                "蓝奏云"
            } else {
                "蓝奏云（自动复制密码 ${info.lanzouPassword}）"
            }
            actions += { openLanzouUpdate(info.lanzouUrl, info.lanzouPassword) }
        }

        if (labels.isEmpty()) {
            toast("没有可用的更新下载源")
            return
        }

        if (labels.size == 1) {
            actions.first().invoke()
            return
        }

        appDialogBuilder()
            .setTitle("选择下载来源")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openQuarkUpdate(url: String) {
        if (!url.startsWith("https://pan.quark.cn/")) {
            toast("夸克更新链接无效")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure { toast("无法打开夸克链接，请确认已安装浏览器或夸克 APP") }
    }

    private fun openLanzouUpdate(url: String, password: String) {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (!url.startsWith("https://") || !host.contains("lanzou")) {
            toast("蓝奏云更新链接无效")
            return
        }

        if (password.isNotBlank()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("蓝奏云提取密码", password)
            )
            toast("蓝奏云密码 $password 已复制")
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure { toast("无法打开蓝奏云链接，请确认已安装浏览器") }
    }

    private fun appDialogBuilder(): AlertDialog.Builder {
        val dialogTheme = if (ThemePreferences.isDark(this)) {
            android.R.style.Theme_Material_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Light_Dialog_Alert
        }
        return AlertDialog.Builder(this, dialogTheme)
    }

    private fun tintDialogButtons(dialog: AlertDialog) {
        val accent = if (ThemePreferences.isDark(this)) {
            UiKit.ACCENT
        } else {
            Color.parseColor("#2563EB")
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accent)
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

        // 某些 MIUI / HyperOS 弹窗样式下，setMessage 与 setMultiChoiceItems
        // 同时使用会导致多选列表不显示，因此改为自定义复选框列表。
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 8),
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 8)
            )
        }

        content.addView(
            UiKit.text(
                this,
                "勾选后，该格式不会加入批量扫描与一键转换。\n单文件转换和音频剪辑不受影响。",
                13f,
                UiKit.TEXT_2
            ).apply {
                setLineSpacing(0f, 1.18f)
                setPadding(0, 0, 0, UiKit.dp(this@MainActivity, 10))
            }
        )

        val checkBoxes = mutableListOf<CheckBox>()

        options.forEach { option ->
            val checkBox = CheckBox(this).apply {
                text = option.label
                textSize = 15f
                setTextColor(UiKit.TEXT)
                isChecked = option.id in selected
                setPadding(
                    UiKit.dp(this@MainActivity, 4),
                    UiKit.dp(this@MainActivity, 8),
                    UiKit.dp(this@MainActivity, 4),
                    UiKit.dp(this@MainActivity, 8)
                )
                buttonTintList = android.content.res.ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(UiKit.ACCENT, UiKit.TEXT_3)
                )
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += option.id
                    else selected -= option.id
                }
            }

            checkBoxes += checkBox
            content.addView(
                checkBox,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = appDialogBuilder()
            .setTitle("扫描过滤格式")
            .setView(scroll)
            .setNeutralButton("全部不忽略", null)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        dialog.setOnShowListener {
            tintDialogButtons(dialog)

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                selected.clear()
                checkBoxes.forEach { it.isChecked = false }
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                IgnoredFormatPreferences.saveSelectedIds(this, selected)
                onIgnoredFormatsChanged()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun onIgnoredFormatsChanged() {
        ignoreFormatsButton.text = "⊘   扫描过滤 · ${IgnoredFormatPreferences.summary(this)}"
        scannedBatch = emptyList()
        batchConvertButton.isEnabled = false
        batchConvertButton.alpha = 0.45f
        batchText.text = "忽略格式已更新\n请重新扫描目录以应用新规则"
        status.text = "状态：忽略格式已更新 · 等待重新扫描"
    }

    private fun confirmFullStorageScan() {
        if (!hasAllFilesAccess()) {
            requestOptionalAllFilesAccess()
            return
        }

        appDialogBuilder()
            .setTitle("全盘扫描")
            .setMessage(
                "将扫描当前设备可访问的全部共享存储，包含没有被系统媒体库索引的 " +
                    "NCM、QMC、KGM、KWM 等音乐文件。\n\n" +
                    "扫描会遵守“扫描过滤”设置，并自动跳过 Music/MusicConverter 输出目录。" +
                    "文件较多时可能需要一些时间。\n\n是否开始？"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("开始全盘扫描") { _, _ ->
                runFullStorageScan()
            }
            .show()
    }

    private fun runFullStorageScan() {
        if (!hasAllFilesAccess()) {
            refreshAllFilesAccessUi()
            toast("请先授权“所有文件访问权限”")
            return
        }

        scannedBatch = emptyList()
        batchConvertButton.isEnabled = false
        batchConvertButton.alpha = 0.45f
        fullStorageScanButton.isEnabled = false
        fullStorageScanButton.alpha = 0.55f
        batchText.text = "正在全盘扫描…\n正在检查全部共享存储"
        status.text = "状态：开始全盘扫描…"

        Thread {
            try {
                var skippedIgnored = 0
                val files = FullStorageAudioScanner.scan(this) { progress ->
                    skippedIgnored = progress.skippedIgnored
                    if (progress.visitedFiles % 80 == 0 ||
                        progress.foundAudio > 0 ||
                        progress.skippedIgnored > 0
                    ) {
                        runOnUiThread {
                            status.text =
                                "状态：全盘扫描中 · 已检查 ${progress.visitedFiles} 个文件 · " +
                                    "可处理 ${progress.foundAudio} 首 · " +
                                    "已忽略 ${progress.skippedIgnored} 首"
                        }
                    }
                }

                scannedBatch = files
                val encrypted = files.count {
                    AudioFormatDetector.isEncrypted(it.displayName)
                }
                val normal = files.size - encrypted
                val totalBytes = files
                    .filter { it.size > 0 }
                    .sumOf { it.size }

                runOnUiThread {
                    fullStorageScanButton.isEnabled = true
                    fullStorageScanButton.alpha = 1f

                    if (files.isEmpty()) {
                        batchText.text = if (skippedIgnored > 0) {
                            "全盘扫描完成，没有需要批量转换的文件\n" +
                                "按当前规则已忽略 $skippedIgnored 首"
                        } else {
                            "全盘扫描完成，但没有发现支持的音乐文件"
                        }
                        status.text =
                            "状态：全盘扫描完成 · 未发现可处理文件 · " +
                                "已忽略 $skippedIgnored 首"
                        batchConvertButton.isEnabled = false
                        batchConvertButton.alpha = 0.45f
                    } else {
                        batchText.text =
                            "全盘扫描发现 ${files.size} 个可处理文件\n" +
                                "加密 $encrypted · 普通 $normal · ${formatSize(totalBytes)}\n" +
                                "按规则已忽略 $skippedIgnored 首"
                        status.text =
                            "状态：全盘扫描完成 · ${files.size} 个文件等待批量转换 · " +
                                "已忽略 $skippedIgnored 首"
                        batchConvertButton.isEnabled = true
                        batchConvertButton.alpha = 1f
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    fullStorageScanButton.isEnabled = true
                    fullStorageScanButton.alpha = 1f
                    refreshAllFilesAccessUi()
                    batchText.text =
                        "全盘扫描失败\n${t.message ?: t.javaClass.simpleName}"
                    status.text = "状态：全盘扫描失败"
                    batchConvertButton.isEnabled = false
                    batchConvertButton.alpha = 0.45f
                }
            }
        }.start()
    }

    private fun chooseScanFolder() {
        appDialogBuilder()
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
        appDialogBuilder()
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

        val dialogScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = appDialogBuilder()
            .setTitle("批量转换 · ${files.size} 个文件")
            .setView(dialogScroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认转换", null)
            .create()

        dialog.setOnShowListener {
            tintDialogButtons(dialog)
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
                appDialogBuilder()
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

    private fun allFilesAccessButtonTitle(): String {
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                "所有文件访问 · 当前系统无需此权限"
            Environment.isExternalStorageManager() ->
                "所有文件访问 · 已授权"
            else ->
                "所有文件访问 · 未授权"
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()

    private fun refreshAllFilesAccessUi() {
        if (::allFilesAccessButton.isInitialized) {
            allFilesAccessButton.text = "▣   ${allFilesAccessButtonTitle()}"
        }
        if (::fullStorageScanButton.isInitialized) {
            fullStorageScanButton.visibility =
                if (hasAllFilesAccess()) View.VISIBLE else View.GONE
            fullStorageScanButton.text =
                if (hasAllFilesAccess()) "◎   全盘扫描 · 已授权"
                else "◎   全盘扫描"
        }
    }

    private fun requestOptionalAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("Android 10 及以下无需单独开启“所有文件访问权限”")
            return
        }

        val alreadyGranted = Environment.isExternalStorageManager()
        val title = if (alreadyGranted) "管理所有文件访问权限" else "可选：所有文件访问权限"
        val message = if (alreadyGranted) {
            "当前已经授权。你可以前往系统设置继续保留，或手动关闭此权限。关闭后仍可使用系统 SAF 选择目录进行扫描。"
        } else {
            "此权限不是使用 MusicConverter 的必需条件。\n\n开启后，系统会允许应用访问更广泛的共享存储文件；不开启时仍可继续使用“选择目录”方式扫描和处理音乐。\n\n是否授权完全由你决定。"
        }

        appDialogBuilder()
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (alreadyGranted) "管理权限" else "前往授权") { _, _ ->
                openAllFilesAccessSettings()
            }
            .show()
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val appPage = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        val generalPage = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

        val opened = runCatching {
            startActivity(appPage)
            true
        }.getOrElse {
            runCatching {
                startActivity(generalPage)
                true
            }.getOrDefault(false)
        }

        if (!opened) {
            toast("无法打开系统权限页面，请在系统设置中手动管理文件访问权限")
        }
    }

    private fun requestBackgroundBatteryAccess() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("当前 Android 版本无需设置电池优化")
            return
        }
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            appDialogBuilder()
                .setTitle("后台耗电已允许")
                .setMessage("系统已允许 MusicConverter 忽略电池优化。批量转换仍会使用前台服务和常驻进度通知。\n\n在 MIUI / HyperOS 上，如仍被清理，可再到应用信息中允许后台运行或将电池策略设为“无限制”。")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        appDialogBuilder()
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
        appDialogBuilder().setTitle("已识别音乐 APP (${apps.size})").setMessage(text).setPositiveButton("确定", null).show()
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
                appDialogBuilder().setTitle("处理历史").setMessage(message).setPositiveButton("确定", null).show()
            }
        }.start()
    }

    private fun showOriginalFileActions(item: PreparedAudio, result: ConversionResult) {
        val outputUri = result.outputUri
        val options = arrayOf("保留源文件", "删除源文件", "用转换结果置换源文件")
        appDialogBuilder()
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
