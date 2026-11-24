package com.musicconverter.miui

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.ActivityNotFoundException
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
import android.provider.MediaStore
import android.content.ContentUris
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.provider.OpenableColumns
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import com.musicconverter.miui.core.*
import com.musicconverter.miui.data.HistoryRepository
import com.musicconverter.miui.editor.AudioEditorActivity
import com.musicconverter.miui.editor.AdvancedAudioEditorActivity
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
import com.musicconverter.miui.cover.OnlineCoverResolver
import com.musicconverter.miui.player.ImageLoader
import com.musicconverter.miui.player.MiniPlayerView
import com.musicconverter.miui.player.PlayerController
import com.musicconverter.miui.player.Track

class MainActivity : Activity() {
    private val localMusicPickCode = 1502
    private val IMPORTED_MUSIC_DIRECTORY = "手动添加"
    private var importedLocalMusic: List<Track> = emptyList()
    private var localMusicFileNames: Map<String, String> = emptyMap()
    private var allLocalMusicTracks: List<Track> = emptyList()
    private var localMusicContainer: FrameLayout? = null
    private var localMusicHome: ScrollView? = null
    private var localMusicSearchPage: LinearLayout? = null
    private var localMusicSearchField: EditText? = null
    private var localMusicSearchList: LinearLayout? = null
    private var localMusicSearchScroll: ScrollView? = null
    private var localMusicSearchStatus: TextView? = null
    private var localMusicSearchDirectory: String? = null
    private var localMusicSearchMatches: List<Track> = emptyList()
    private var localMusicSearchRenderedCount = 0
    private val localMusicSearchRefresh = Runnable { updateLocalMusicSearch() }
    private var savedLocalMusicState: Bundle? = null

    private val pickFileCode = 1001
    private val pickTreeCode = 1002
    private val saveConvertedAsCode = 1003

    private var pendingConvertedFile: java.io.File? = null
    private var pendingConvertedName: String = ""
    private var pendingConvertedSource: PreparedAudio? = null

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
    private var miniPlayerView: MiniPlayerView? = null
    private var localMusicHost: LinearLayout? = null
    private var localMusicStatus: TextView? = null
    private var localMusicTracks: List<Track> = emptyList()
    private var localMusicDirectories: Map<String, String> = emptyMap()
    private var localMusicVisibleTracks: List<Track> = emptyList()
    private var localMusicDirectoryOrder: List<String> = emptyList()
    private var localMusicSelectedDirectory: String? = null
    private var localMusicDirectoryTabs: LinearLayout? = null
    private var localMusicDirectoryTabScroll: HorizontalScrollView? = null
    private var localMusicRenderedCount = 0
    private var localMusicPageLoading = false
    private val localMusicPageSize = 40
    private var selectedMainPage = 0
    private var selectMainPage: ((Int) -> Unit)? = null
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
        savedLocalMusicState = savedInstanceState?.getBundle("local_music_ui")
        com.musicconverter.miui.ui.UiKit.prepareActivity(this)
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
        PlayerController.initialize(this)
        buildUi(savedInstanceState?.getInt("selected_main_page") ?: 0)
        enforceDisclaimerConsent()
        window.decorView.post { ensureLocalMusicPermissionAndLoad() }

        // 如果之前已经确认存在强制更新，启动时立即恢复门禁，防止断网绕过。
        RemoteUpdateManager.cachedMandatoryUpdate(this, appVersionCode)?.let {
            showMandatoryUpdateDialog(it)
        }

        // 强制更新检查每次启动都执行，不受普通“每天一次”检查节流影响。
        checkMandatoryUpdateOnLaunch()

        // 普通可选更新仍按原来的 24 小时间隔检查。
        maybeCheckRemoteUpdate(manual = false)
    }

    private fun enforceDisclaimerConsent() {
        val prefs = getSharedPreferences("legal_preferences", Context.MODE_PRIVATE)
        val currentVersion = 1
        if (prefs.getInt("disclaimer_version", 0) >= currentVersion) return

        val text = TextView(this).apply {
            setPadding(UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 12), UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 12))
            setTextColor(UiKit.TEXT)
            textSize = 14f
            text = DISCLAIMER_TEXT
        }
        val scroll = ScrollView(this).apply { addView(text) }
        val dialog = appDialogBuilder()
            .setTitle("免责声明与使用须知")
            .setView(scroll)
            .setCancelable(false)
            .setNegativeButton("不同意并退出") { _, _ -> finishAffinity() }
            .setPositiveButton("我已阅读并同意", null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            tintDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                prefs.edit().putInt("disclaimer_version", currentVersion).apply()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    companion object {
        private const val ROOT_MUSIC_DIRECTORY = "内部存储根目录"
        private val DISCLAIMER_TEXT = """
            请在使用 MusicConverter 前仔细阅读本声明。点击“我已阅读并同意”表示你已阅读并理解以下内容。
            
            1. 软件性质
            MusicConverter 是用于本地音频处理、格式转换、编辑与播放的工具。软件本身不提供、存储或运营任何商业音乐曲库，也不授予任何音乐作品、录音制品、专辑封面或其他第三方内容的版权许可。
            
            2. 合法使用与版权
            你应仅处理自己依法拥有、已获得授权，或法律允许使用的音频及其他内容。请勿利用本软件实施侵权、规避技术保护措施、传播违法内容或其他违反适用法律法规及第三方服务条款的行为。因用户自行导入、转换、编辑、保存、分享或使用内容产生的法律责任，由实际行为人依法承担。
            
            3. 联网封面与第三方信息
            本地音乐缺少封面时，软件可能根据歌曲名称、歌手等信息联网查询第三方公开数据以补充封面。搜索结果可能存在匹配错误、缺失或第三方权利限制。用户应自行判断其使用是否合法、适当。
            
            4. 本地文件操作风险
            音频转换、剪辑、批量处理、替换源文件、删除源文件及清理缓存等操作可能改变或删除本地数据。请在重要操作前自行备份。软件会尽合理努力降低异常风险，但本声明不排除或限制依法不能排除或限制的责任。
            
            5. 第三方组件与服务
            软件可能调用第三方开源组件、系统能力、网站、接口或应用。相关第三方服务由其各自运营者负责，其可用性、内容及规则不受本软件控制。第三方组件的许可与声明以项目内 THIRD_PARTY_NOTICES、相应许可证及上游项目说明为准。
            
            6. 无担保与责任边界
            本软件按现状提供。除法律另有强制规定外，不对特定用途适用性、第三方服务持续可用性或用户设备、文件、网络环境造成的间接损失作额外保证。任何免责声明均不应被解释为排除或限制法律规定不得排除或限制的消费者权利、人身损害责任或其他法定责任。
            
            7. 协议更新
            如本声明因功能或法律要求发生实质变化，软件可提高协议版本并再次要求确认。继续使用前请重新阅读。
            
            如你不同意以上内容，请选择“不同意并退出”并停止使用本软件。
        """.trimIndent()
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_main_page", selectedMainPage)
        saveLocalMusicState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selectedMainPage == 0 && handleLocalMusicBack()) return
        super.onBackPressed()
    }

    private fun buildUi(initialPage: Int) {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG)
        }
        // 保留系统状态栏，但让应用内容从状态栏下方开始，避免标题被遮挡。
        screen.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val topInset = insets.systemWindowInsetTop
            val bottomInset = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (view.paddingTop != topInset || view.paddingBottom != bottomInset) {
                view.setPadding(view.paddingLeft, topInset, view.paddingRight, bottomInset)
            }
            insets
        }
        screen.requestApplyInsets()

        val pageHost = FrameLayout(this).apply {
            setBackgroundColor(UiKit.BG)
        }
        screen.addView(pageHost, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val homePage = buildHomePage()
        val editorPage = buildEditorPage()
        val batchPage = buildBatchPage()
        val aboutPage = buildAboutPage()
        val pages = listOf<View>(homePage, editorPage, batchPage, aboutPage)
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

        val miniPlayer = MiniPlayerView(this).apply {
            setAlwaysVisible(
                getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                    .getBoolean("mini_player_always_visible", true)
            )
        }
        miniPlayerView = miniPlayer
        screen.addView(
            miniPlayer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 64)
            ).apply {
                leftMargin = UiKit.dp(this@MainActivity, 12)
                rightMargin = UiKit.dp(this@MainActivity, 12)
                bottomMargin = UiKit.dp(this@MainActivity, 4)
            }
        )

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(UiKit.dp(this@MainActivity, 10), UiKit.dp(this@MainActivity, 8), UiKit.dp(this@MainActivity, 10), UiKit.dp(this@MainActivity, 10))
            background = UiKit.rounded(UiKit.SURFACE, 0, this@MainActivity, UiKit.BORDER, 1)
        }
        UiKit.margins(navigation, 0, 3, 0, 0)

        val homeNav = UiKit.navItem(this, "music", "本地音乐")
        val editorNav = UiKit.navItem(this, "edit", "编辑")
        val batchNav = UiKit.navItem(this, "convert", "转码")
        val aboutNav = UiKit.navItem(this, "settings", "设置")
        val navItems = listOf(homeNav, editorNav, batchNav, aboutNav)
        navItems.forEachIndexed { index, item ->
            navigation.addView(item, LinearLayout.LayoutParams(0, UiKit.dp(this, 58), 1f).apply {
                if (index > 0) leftMargin = UiKit.dp(this@MainActivity, 2)
            })
        }
        screen.addView(navigation)

        fun selectPage(index: Int) {
            if (index != 0 && selectedMainPage == 0) hideLocalMusicSearchKeyboard()
            selectedMainPage = index
            pages.forEachIndexed { i, view -> view.visibility = if (i == index) View.VISIBLE else View.GONE }
            navItems.forEachIndexed { i, item -> UiKit.setNavSelected(item, i == index.coerceAtMost(3)) }
        }
        selectMainPage = ::selectPage
        navItems.forEachIndexed { index, item -> item.setOnClickListener { selectPage(index) } }
        selectPage(initialPage.coerceIn(pages.indices))

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

    private fun buildHomePage(): FrameLayout {
        val (scroll, root) = pageRoot()
        val container = FrameLayout(this)
        localMusicContainer = container
        localMusicHome = scroll
        container.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            UiKit.text(this, "本地音乐", 22f, UiKit.TEXT, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(UiKit.badge(this, "正式版"))
        root.addView(header)
        root.addView(UiKit.spacer(this, 16))
        val actionCard = UiKit.card(this, 22).apply {
            background = UiKit.rounded(UiKit.primaryContainer(context), 22, context)
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(context, 16), UiKit.dp(context, 16), UiKit.dp(context, 16), UiKit.dp(context, 12))
        }
        actionCard.addView(UiKit.text(this, "你的本地音乐", 25f, UiKit.onPrimaryContainer(this), true))
        actionCard.addView(UiKit.spacer(this, 5))
        actionCard.addView(UiKit.text(this, "浏览、播放和管理手机里的音频。", 12.5f, UiKit.onPrimaryContainer(this)))
        actionCard.addView(UiKit.spacer(this, 12))
        actionCard.addView(UiKit.wideButton(this, "＋", "添加音乐文件", primary = true).apply {
            setOnClickListener { pickLocalMusicDocuments() }
        })
        root.addView(actionCard)
        root.addView(UiKit.spacer(this, 12))
        root.addView(UiKit.wideButton(this, "⌕", "搜索本地音乐").apply {
            contentDescription = "搜索本地音乐"
            setOnClickListener { openLocalMusicSearch() }
        })

        root.addView(UiKit.spacer(this, 18))
        val directoryHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        localMusicStatus = UiKit.text(
            this,
            "正在读取本地音乐…",
            12.5f,
            UiKit.TEXT_3
        )
        directoryHeader.addView(
            localMusicStatus,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val playDirectory = UiKit.text(this, "▶", 18f, UiKit.TEXT, true).apply {
            gravity = Gravity.CENTER
            background = UiKit.rounded(UiKit.SURFACE, 18, this@MainActivity)
            contentDescription = "播放当前目录全部歌曲"
            setOnClickListener { playSelectedLocalMusicDirectory() }
        }
        directoryHeader.addView(
            playDirectory,
            LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)).apply {
                marginStart = UiKit.dp(this@MainActivity, 10)
            }
        )
        val refresh = UiKit.text(this, "↻", 21f, UiKit.ACCENT_2, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "刷新本地音乐"
        }
        directoryHeader.addView(refresh, LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)))
        root.addView(UiKit.sectionLabel(this, "音乐目录"))
        root.addView(directoryHeader)

        root.addView(UiKit.spacer(this, 6))
        localMusicDirectoryTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        localMusicDirectoryTabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                localMusicDirectoryTabs,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            localMusicDirectoryTabScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 48)
            )
        )

        root.addView(UiKit.spacer(this, 10))
        root.addView(UiKit.sectionLabel(this, "音乐列表"))
        root.addView(UiKit.spacer(this, 8))
        localMusicHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(localMusicHost)

        refresh.setOnClickListener { refreshLocalMusic() }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            val remaining = child.measuredHeight - (scrollY + scroll.height)
            if (remaining < UiKit.dp(this@MainActivity, 320)) {
                appendLocalMusicPage()
            }
        }
        return container
    }

    private fun openLocalMusicSearch(restored: Bundle? = null) {
        val container = localMusicContainer ?: return
        localMusicHome?.visibility = View.INVISIBLE
        localMusicSearchPage?.let {
            it.visibility = View.VISIBLE
            it.bringToFront()
            updateLocalMusicSearch()
            return
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG)
            setPadding(UiKit.dp(context, 18), UiKit.dp(context, 12), UiKit.dp(context, 18), 0)
        }
        localMusicSearchPage = column
        container.addView(column, FrameLayout.LayoutParams(-1, -1))

        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val back = UiKit.wideButton(this, "‹", "返回").apply {
            contentDescription = "返回本地音乐列表"
            setOnClickListener { handleLocalMusicBack() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 48)))
        toolbar.addView(UiKit.text(this, "搜索本地音乐", 19f, UiKit.TEXT, true).apply {
            setPadding(UiKit.dp(context, 12), 0, 0, 0)
        })
        column.addView(toolbar)
        column.addView(UiKit.spacer(this, 10))

        val field = EditText(this).apply {
            setSingleLine()
            hint = "输入歌名、文件名、歌手或专辑"
            setHintTextColor(UiKit.TEXT_3)
            setTextColor(UiKit.TEXT)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(UiKit.dp(context, 14), 0, UiKit.dp(context, 14), 0)
            background = UiKit.rounded(UiKit.SURFACE, 14, context, UiKit.ACCENT_2, 1)
        }
        localMusicSearchField = field
        val inputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        inputRow.addView(field, LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1f))
        val clear = UiKit.text(this, "×", 22f, UiKit.ACCENT_2, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "清除搜索文字"
            setOnClickListener { field.text.clear() }
        }
        inputRow.addView(clear, LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)))
        column.addView(inputRow)
        column.addView(UiKit.spacer(this, 8))

        val scope = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val all = RadioButton(this).apply {
            id = View.generateViewId()
            text = "全部音乐"
            minHeight = UiKit.dp(context, 48)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(UiKit.TEXT)
        }
        val currentDirectory = localMusicSelectedDirectory
        val current = RadioButton(this).apply {
            id = View.generateViewId()
            text = "当前目录：${currentDirectory?.let(::directoryDisplayName).orEmpty()}"
            minHeight = UiKit.dp(context, 48)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(UiKit.TEXT)
            visibility = if (currentDirectory == null) View.GONE else View.VISIBLE
        }
        scope.addView(all, RadioGroup.LayoutParams(0, -2, 1f))
        scope.addView(current, RadioGroup.LayoutParams(0, -2, 1.6f))
        val restoreScope = currentDirectory != null && restored?.getBoolean("scope") == true
        scope.check(if (restoreScope) current.id else all.id)
        localMusicSearchDirectory = if (restoreScope) currentDirectory else null
        scope.setOnCheckedChangeListener { _, checkedId ->
            localMusicSearchDirectory = if (checkedId == current.id) currentDirectory else null
            updateLocalMusicSearch()
        }
        scope.contentDescription = "搜索范围"
        column.addView(scope)
        localMusicSearchStatus = UiKit.text(this, "", 13f, UiKit.TEXT_3, true).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        localMusicSearchStatus?.let(column::addView)
        column.addView(UiKit.spacer(this, 10))

        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val resultScroll = ScrollView(this).apply {
            addView(results)
        }
        localMusicSearchList = results
        localMusicSearchScroll = resultScroll
        column.addView(resultScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        resultScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (results.height - scrollY - resultScroll.height < UiKit.dp(this, 320)) {
                appendLocalMusicSearchResults()
            }
        }
        field.setText(restored?.getString("query").orEmpty())
        field.setSelection(field.length())
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                field.removeCallbacks(localMusicSearchRefresh)
                field.postDelayed(localMusicSearchRefresh, 120)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        field.setOnEditorActionListener { _, action, _ ->
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                false
            } else {
                field.removeCallbacks(localMusicSearchRefresh)
                updateLocalMusicSearch()
                hideLocalMusicSearchKeyboard()
                true
            }
        }
        updateLocalMusicSearch()
        if (restored != null) return
        field.requestFocus()
        field.post {
            if (localMusicSearchField === field && localMusicSearchPage?.isShown == true) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun hideLocalMusicSearchKeyboard() {
        val field = localMusicSearchField ?: return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
    }

    private fun handleLocalMusicBack(): Boolean {
        val container = localMusicContainer ?: return false
        val search = localMusicSearchPage ?: return false
        if (!container.isShown || search.visibility != View.VISIBLE) return false
        hideLocalMusicSearchKeyboard()
        localMusicSearchField?.removeCallbacks(localMusicSearchRefresh)
        container.removeView(search)
        localMusicSearchPage = null
        localMusicSearchField = null
        localMusicSearchList = null
        localMusicSearchScroll = null
        localMusicSearchStatus = null
        localMusicSearchDirectory = null
        localMusicSearchMatches = emptyList()
        localMusicSearchRenderedCount = 0
        localMusicHome?.visibility = View.VISIBLE
        return true
    }

    private fun updateLocalMusicSearch() {
        val field = localMusicSearchField ?: return
        val results = localMusicSearchList ?: return
        val query = field.text.toString().trim()
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val scope = localMusicSearchDirectory
        localMusicSearchMatches = if (words.isEmpty()) emptyList() else allLocalMusicTracks.filter { track ->
            val directory = localMusicDirectory(track)
            (scope == null || directory == scope) && words.all { word ->
                track.title.contains(word, ignoreCase = true) ||
                    track.artist.contains(word, ignoreCase = true) ||
                    track.album.contains(word, ignoreCase = true) ||
                    localMusicFileNames[track.id].orEmpty().contains(word, ignoreCase = true)
            }
        }
        localMusicSearchRenderedCount = 0
        results.removeAllViews()
        localMusicSearchScroll?.scrollTo(0, 0)
        localMusicSearchStatus?.text = when {
            query.isEmpty() -> "输入关键词搜索本地音乐"
            localMusicSearchMatches.isEmpty() -> "没有找到匹配的音乐，可更换关键词或搜索范围"
            else -> "搜索结果 · ${localMusicSearchMatches.size} 首"
        }
        appendLocalMusicSearchResults()
    }

    private fun appendLocalMusicSearchResults() {
        val host = localMusicSearchList ?: return
        val from = localMusicSearchRenderedCount
        val to = minOf(from + localMusicPageSize, localMusicSearchMatches.size)
        localMusicSearchRenderedCount = to
        for (index in from until to) {
            if (index > 0) host.addView(UiKit.spacer(this, 8))
            host.addView(buildLocalMusicRow(localMusicSearchMatches[index], showDirectory = true))
        }
    }

    private fun pickLocalMusicDocuments() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, localMusicPickCode)
        } catch (_: ActivityNotFoundException) {
            toast("找不到可用的文件选择器")
        }
    }

    private fun importedLocalMusicPrefs() = getSharedPreferences("imported_local_music", Context.MODE_PRIVATE)

    private fun loadImportedLocalMusic() {
        val uris = importedLocalMusicPrefs().getStringSet("documents", emptySet()).orEmpty()
        importedLocalMusic = uris.mapNotNull { describeLocalMusicDocument(Uri.parse(it)) }
    }

    private fun describeLocalMusicDocument(uri: Uri): Track? {
        if (uri.scheme != android.content.ContentResolver.SCHEME_CONTENT) return null
        val label = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Track(
            id = "document:$uri", title = label, artist = "本地文件", album = "",
            artwork = "media-thumb:$uri", streamUrl = uri.toString(), source = "local"
        )
    }

    private fun addLocalMusicDocuments(data: Intent) {
        val selection = buildList {
            data.data?.let { add(it) }
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { add(it) }
            }
        }.distinct()
        val remembered = importedLocalMusicPrefs()
            .getStringSet("documents", emptySet()).orEmpty().toMutableSet()
        val previouslyAdded = remembered.size
        val known = importedLocalMusic.map { it.streamUrl }.toSet()
        val newTracks = selection.mapNotNull { uri ->
            if (uri.toString() in known) return@mapNotNull null
            val track = describeLocalMusicDocument(uri) ?: return@mapNotNull null
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onSuccess {
                remembered.add(uri.toString())
            }
            track
        }
        if (newTracks.isEmpty()) {
            toast("未添加新文件，文件可能已存在或无法读取")
            return
        }
        importedLocalMusic = importedLocalMusic + newTracks
        importedLocalMusicPrefs().edit().putStringSet("documents", remembered).apply()
        renderLocalMusic()
        selectLocalMusicDirectory(IMPORTED_MUSIC_DIRECTORY)
        val temporaryCount = newTracks.size - (remembered.size - previouslyAdded)
        toast(if (temporaryCount > 0) "已添加 ${newTracks.size} 首，其中 $temporaryCount 首仅本次可用" else "已添加 ${newTracks.size} 首")
    }

    private fun ensureLocalMusicPermissionAndLoad() {
        loadImportedLocalMusic()
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            // 已经有缓存时直接恢复，不再每次启动都扫描 MediaStore。
            restoreLocalMusicCacheOrScan()
        } else {
            renderLocalMusic()
            localMusicStatus?.text = "授权后可扫描本地音乐，也可手动添加文件"
            requestPermissions(arrayOf(permission), 1501)
        }
    }

    private fun refreshLocalMusic() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            loadLocalMusic()
        } else {
            localMusicStatus?.text = "需要音乐读取权限才能刷新本地歌曲"
            requestPermissions(arrayOf(permission), 1501)
        }
    }

    private fun localMusicCacheFile(): java.io.File =
        java.io.File(filesDir, "local_music_cache.json")

    private fun restoreLocalMusicCacheOrScan() {
        val cache = localMusicCacheFile()
        if (!cache.exists() || cache.length() <= 2L) {
            loadLocalMusic()
            return
        }
        localMusicStatus?.text = "正在恢复上次音乐列表…"
        Thread {
            val restored = runCatching {
                val array = JSONArray(cache.readText())
                val restoredDirectories = LinkedHashMap<String, String>()
                val restoredFileNames = LinkedHashMap<String, String>()
                val tracks = ArrayList<Track>(array.length()).apply {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        if (!item.has("directory") || !item.has("fileName")) {
                            throw IllegalStateException("旧版缓存缺少目录或文件名信息")
                        }
                        val track = Track(
                                id = item.optString("id"),
                                title = item.optString("title", "未知歌曲"),
                                artist = item.optString("artist", "未知歌手"),
                                album = item.optString("album"),
                                artwork = item.optString("artwork").takeIf { it.isNotBlank() }
                                    ?: item.optString("streamUrl").takeIf { it.startsWith("content://") }?.let { "media-thumb:$it" },
                                streamUrl = item.optString("streamUrl"),
                                source = "local"
                            )
                        add(track)
                        restoredFileNames[track.id] = item.optString("fileName")
                        restoredDirectories[track.id] =
                            normalizeLocalMusicDirectory(item.optString("directory"))
                    }
                }
                Triple(tracks, restoredDirectories, restoredFileNames)
            }.getOrNull()

            if (restored == null) {
                runCatching { cache.delete() }
                runOnUiThread { loadLocalMusic() }
                return@Thread
            }
            runOnUiThread {
                localMusicTracks = restored.first
                localMusicDirectories = restored.second
                localMusicFileNames = restored.third
                renderLocalMusic()
            }
        }.start()
    }

    private fun saveLocalMusicCache(songs: List<Track>) {
        val directories = localMusicDirectories
        val names = localMusicFileNames
        Thread {
            runCatching {
                val array = JSONArray()
                songs.forEach { track ->
                    array.put(
                        JSONObject().apply {
                            put("id", track.id)
                            put("title", track.title)
                            put("artist", track.artist)
                            put("album", track.album)
                            put("artwork", track.artwork ?: "")
                            put("streamUrl", track.streamUrl)
                            put("directory", directories[track.id] ?: ROOT_MUSIC_DIRECTORY)
                            put("fileName", names[track.id].orEmpty())
                        }
                    )
                }
                val target = localMusicCacheFile()
                val temp = java.io.File(target.parentFile, target.name + ".tmp")
                temp.writeText(array.toString())
                if (target.exists()) target.delete()
                temp.renameTo(target)
            }
        }.start()
    }

    private fun loadLocalMusic() {
        localMusicStatus?.text = "正在读取本地音乐…"
        Thread {
            val songs = mutableListOf<Track>()
            val directories = LinkedHashMap<String, String>()
            val names = LinkedHashMap<String, String>()
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            runCatching {
                contentResolver.query(
                    uri,
                    projection,
                    selection,
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val directoryCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    val displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val contentUri = ContentUris.withAppendedId(uri, id)
                        val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "未知歌曲"
                        val artist = cursor.getString(artistCol)?.takeIf {
                            it.isNotBlank() && it != "<unknown>"
                        } ?: "未知歌手"
                        val album = cursor.getString(albumCol).orEmpty()
                        val track = Track(
                            id = "local:$id",
                            title = title,
                            artist = artist,
                            album = album,
                            artwork = "media-thumb:${contentUri}",
                            streamUrl = contentUri.toString(),
                            source = "local"
                        )
                        songs += track
                        if (displayNameCol >= 0) names[track.id] = cursor.getString(displayNameCol).orEmpty()
                        directories[track.id] = normalizeLocalMusicDirectory(
                            if (directoryCol >= 0) cursor.getString(directoryCol) else null
                        )
                    }
                }
            }.onFailure {
                runOnUiThread {
                    renderLocalMusic()
                    localMusicStatus?.text = "读取本地音乐失败：${it.message ?: "未知错误"}"
                }
                return@Thread
            }
            runOnUiThread {
                localMusicTracks = songs
                localMusicDirectories = directories
                localMusicFileNames = names
                saveLocalMusicCache(songs)
                renderLocalMusic()
            }
        }.start()
    }

    private fun normalizeLocalMusicDirectory(value: String?): String {
        return value
            ?.replace('\\', '/')
            ?.trim()
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
            ?: ROOT_MUSIC_DIRECTORY
    }

    private fun directoryDisplayName(directory: String): String {
        if (directory == ROOT_MUSIC_DIRECTORY) return "根目录"
        return directory.substringAfterLast('/').ifBlank { directory }
    }

    private fun localMusicDirectory(track: Track): String =
        if (track.id.startsWith("document:")) IMPORTED_MUSIC_DIRECTORY
        else localMusicDirectories[track.id] ?: ROOT_MUSIC_DIRECTORY

    private fun renderLocalMusic() {
        allLocalMusicTracks = localMusicTracks + importedLocalMusic
        localMusicDirectoryOrder = allLocalMusicTracks.groupingBy(::localMusicDirectory).eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }
        val restored = savedLocalMusicState
        if (restored != null) {
            localMusicSelectedDirectory = restored.getString("directory")
            savedLocalMusicState = null
        }
        if (localMusicSelectedDirectory !in localMusicDirectoryOrder) {
            localMusicSelectedDirectory = null
        }
        renderLocalMusicDirectoryTabs()
        renderSelectedLocalMusicDirectory()
        if (restored != null) {
            val rendered = minOf(restored.getInt("home_rows"), localMusicVisibleTracks.size)
            while (localMusicRenderedCount < rendered) appendLocalMusicPage()
            localMusicHome?.let { scroll ->
                scroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                                                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                        if (bottom <= top) return
                        view.removeOnLayoutChangeListener(this)
                        scroll.scrollTo(0, restored.getInt("home_scroll"))
                    }
                })
                scroll.requestLayout()
            }
            if (restored.getBoolean("search_open")) {
                openLocalMusicSearch(restored)
            }
        }
        if (localMusicSearchField != null && restored == null) updateLocalMusicSearch()
    }

    private fun saveLocalMusicState(outState: Bundle) {
        outState.putBundle("local_music_ui", Bundle().apply {
            putString("directory", localMusicSelectedDirectory)
            putInt("home_scroll", localMusicHome?.scrollY ?: 0)
            putInt("home_rows", localMusicRenderedCount)
            putBoolean("search_open", localMusicSearchPage != null)
            putString("query", localMusicSearchField?.text?.toString())
            putBoolean("scope", localMusicSearchDirectory != null)
        })
    }

    private fun renderLocalMusicDirectoryTabs() {
        val tabs = localMusicDirectoryTabs ?: return
        tabs.removeAllViews()

        val pages = listOf<String?>(null) + localMusicDirectoryOrder
        pages.forEachIndexed { index, directory ->
            val selected = directory == localMusicSelectedDirectory
            val count = if (directory == null) {
                allLocalMusicTracks.size
            } else {
                allLocalMusicTracks.count {
                    localMusicDirectory(it) == directory
                }
            }
            val label = if (directory == null) "全部音乐" else directoryDisplayName(directory)
            val tab = UiKit.text(
                this,
                "$label  $count",
                12f,
                if (selected) UiKit.onPrimaryContainer(this) else UiKit.TEXT_2,
                selected
            ).apply {
                gravity = Gravity.CENTER
                setPadding(
                    UiKit.dp(this@MainActivity, 14),
                    0,
                    UiKit.dp(this@MainActivity, 14),
                    0
                )
                background = UiKit.rounded(
                    if (selected) UiKit.primaryContainer(this@MainActivity) else UiKit.SURFACE,
                    16,
                    this@MainActivity
                )
                contentDescription = if (directory == null) {
                    "全部本地音乐，共 $count 首"
                } else {
                    "目录 $directory，共 $count 首"
                }
                setOnClickListener { selectLocalMusicDirectory(directory) }
            }
            tabs.addView(
                tab,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    UiKit.dp(this, 38)
                ).apply {
                    if (index > 0) leftMargin = UiKit.dp(this@MainActivity, 8)
                }
            )
        }
    }

    private fun selectLocalMusicDirectory(directory: String?) {
        if (directory == localMusicSelectedDirectory) return
        localMusicSelectedDirectory = directory
        renderLocalMusicDirectoryTabs()
        renderSelectedLocalMusicDirectory()
    }

    private fun renderSelectedLocalMusicDirectory() {
        val host = localMusicHost ?: return
        host.removeAllViews()
        localMusicRenderedCount = 0
        localMusicPageLoading = false

        localMusicVisibleTracks = localMusicSelectedDirectory?.let { selectedDirectory ->
            allLocalMusicTracks.filter {
                localMusicDirectory(it) == selectedDirectory
            }
        } ?: allLocalMusicTracks

        val directory = localMusicSelectedDirectory
        localMusicStatus?.text = when {
            allLocalMusicTracks.isEmpty() ->
                "未发现本地音乐 · 点击刷新重新扫描"
            directory == null ->
                "全部目录 · 共 ${allLocalMusicTracks.size} 首 · 左右滑动查看目录标签，点击切换"
            else ->
                "目录：$directory · ${localMusicVisibleTracks.size} 首 · 左右滑动查看目录标签，点击切换"
        }
        appendLocalMusicPage()
    }

    private fun playSelectedLocalMusicDirectory() {
        val tracks = localMusicVisibleTracks
        if (tracks.isEmpty()) {
            toast("当前目录没有可播放的歌曲")
            return
        }

        val addedCount = PlayerController.addAll(tracks, playNow = true)
        val scope = if (localMusicSelectedDirectory == null) "全部目录" else "当前目录"
        toast(
            if (addedCount > 0) {
                "$scope ${tracks.size} 首已加入播放列表"
            } else {
                "${scope}歌曲已在播放列表中"
            }
        )
    }

    private fun appendLocalMusicPage() {
        if (localMusicPageLoading) return
        val host = localMusicHost ?: return
        val songs = localMusicVisibleTracks
        if (localMusicRenderedCount >= songs.size) return
        localMusicPageLoading = true

        val from = localMusicRenderedCount
        val to = minOf(from + localMusicPageSize, songs.size)
        for (index in from until to) {
            val track = songs[index]
            if (index > 0) host.addView(UiKit.spacer(this, 8))
            host.addView(buildLocalMusicRow(track))
        }
        localMusicRenderedCount = to
        localMusicPageLoading = false
    }

    private fun buildLocalMusicRow(track: Track, showDirectory: Boolean = false): View {
        val card = UiKit.card(this, 20).apply {
            setPadding(
                UiKit.dp(this@MainActivity, 14),
                UiKit.dp(this@MainActivity, 12),
                UiKit.dp(this@MainActivity, 10),
                UiKit.dp(this@MainActivity, 12)
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var displayTrack = track
        val artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(UiKit.SURFACE)
        }
        row.addView(
            artworkView,
            LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)).apply {
                marginEnd = UiKit.dp(this@MainActivity, 12)
            }
        )
        fun syncOnlineCover(forceRefresh: Boolean = false) {
            if (showDirectory || track.id.startsWith("document:")) return
            OnlineCoverResolver.resolve(this, track.title, track.artist, forceRefresh) { coverUrl ->
                if (coverUrl.isNullOrBlank()) return@resolve
                displayTrack = track.copy(artwork = coverUrl)
                ImageLoader.load(coverUrl, artworkView)
                val pos = localMusicTracks.indexOfFirst { it.id == track.id }
                if (pos >= 0) {
                    val updated = localMusicTracks.toMutableList()
                    updated[pos] = displayTrack
                    localMusicTracks = updated
                    saveLocalMusicCache(updated)
                }
            }
        }

        val artwork = if (showDirectory) {
            track.artwork?.takeIf { it.startsWith("media-thumb:") }
                ?: track.streamUrl.takeIf { it.startsWith("content://") }?.let { "media-thumb:$it" }
        } else track.artwork
        if (!artwork.isNullOrBlank()) {
            ImageLoader.load(artwork, artworkView) { loaded ->
                if (!loaded && track.source == "local") {
                    syncOnlineCover(forceRefresh = true)
                }
            }
        } else {
            syncOnlineCover()
        }
        val textBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(UiKit.text(this@MainActivity, track.title, 14.5f, UiKit.TEXT, true))
            addView(
                UiKit.text(
                    this@MainActivity,
                    listOfNotNull(
                        directoryDisplayName(localMusicDirectory(track)).takeIf { showDirectory },
                        track.artist,
                        track.album.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    11.5f,
                    UiKit.TEXT_3
                )
            )
        }
        row.addView(textBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val add = UiKit.text(this, "＋", 22f, UiKit.ACCENT_2, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                PlayerController.add(displayTrack, playNow = false)
                toast("已加入当前播放列表")
            }
        }
        row.addView(add, LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)))

        val playNow = UiKit.text(this, "▶", 19f, UiKit.TEXT, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { PlayerController.add(displayTrack, playNow = true) }
        }
        row.addView(playNow, LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)))
        card.addView(row)
        card.setOnClickListener { PlayerController.add(displayTrack, playNow = true) }
        return card
    }

private fun buildEditorHubPage(): ScrollView {
    val (scroll, root) = pageRoot()
    root.addView(UiKit.text(this,"编辑",31f,UiKit.TEXT,true))
    root.addView(UiKit.text(this,"音频编辑 · 批量处理",12.5f,UiKit.TEXT_3))
    root.addView(UiKit.spacer(this,18))
    val single=UiKit.featureTile(this,"✂","音频编辑","波形剪辑、拼接、分割、升降调、多轨混音","进入",true)
    val batch=UiKit.featureTile(this,"≋","批量处理","目录扫描、格式过滤、并行转换","批量",false)
    root.addView(single); root.addView(UiKit.spacer(this,12)); root.addView(batch)
    single.setOnClickListener {
        val dialog=AlertDialog.Builder(this).setTitle("音频编辑").create()
        val page=buildEditorPage()
        dialog.setView(page); dialog.setButton(AlertDialog.BUTTON_NEGATIVE,"关闭"){d,_->d.dismiss()}; dialog.show()
    }
    batch.setOnClickListener {
        val dialog=AlertDialog.Builder(this).setTitle("批量处理").create()
        val page=buildBatchPage()
        dialog.setView(page); dialog.setButton(AlertDialog.BUTTON_NEGATIVE,"关闭"){d,_->d.dismiss()}; dialog.show()
    }
    return scroll
}

    private fun buildEditorPage(): ScrollView {
        val (scroll, root) = pageRoot()

        root.addView(UiKit.text(this, "音频编辑", 31f, UiKit.TEXT, true))
        root.addView(
            UiKit.text(
                this,
                "剪辑 · 拼接 · 分割 · 升降调 · 多轨混音",
                12.5f,
                UiKit.TEXT_3
            ).apply {
                setPadding(0, UiKit.dp(this@MainActivity, 6), 0, 0)
            }
        )

        root.addView(UiKit.spacer(this, 22))

        val trim = UiKit.featureTile(
            this,
            "✂",
            "波形剪辑",
            "直接拖动波形建立选区，试听后导出",
            "波形",
            true
        )
        trim.setOnClickListener { openEditor() }
        root.addView(trim)

        root.addView(UiKit.spacer(this, 12))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val concat = UiKit.featureTile(
            this,
            "⇥",
            "音频拼接",
            "多段音频按选择顺序合并"
        )
        val split = UiKit.featureTile(
            this,
            "┃",
            "音频分割",
            "指定时间点一分为二"
        )
        row1.addView(
            concat,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = UiKit.dp(this@MainActivity, 6)
            }
        )
        row1.addView(
            split,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = UiKit.dp(this@MainActivity, 6)
            }
        )
        root.addView(row1)

        root.addView(UiKit.spacer(this, 12))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pitch = UiKit.featureTile(
            this,
            "♯",
            "升降调",
            "-12 ～ +12 半音，尽量保持时长"
        )
        val multitrack = UiKit.featureTile(
            this,
            "≡",
            "多轨编辑",
            "最多 4 轨 · 偏移 · 音量 · 混音"
        )
        row2.addView(
            pitch,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = UiKit.dp(this@MainActivity, 6)
            }
        )
        row2.addView(
            multitrack,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = UiKit.dp(this@MainActivity, 6)
            }
        )
        root.addView(row2)

        root.addView(UiKit.spacer(this, 20))
        val note = UiKit.card(this, 20)
        note.addView(
            UiKit.text(
                this,
                "所有处理均在本机完成。高级编辑结果默认保存到 Music/MusicConverter。",
                12.5f,
                UiKit.TEXT_2
            ).apply {
                setLineSpacing(0f, 1.2f)
            }
        )
        root.addView(note)

        concat.setOnClickListener { openAdvancedEditor("CONCAT") }
        split.setOnClickListener { openAdvancedEditor("SPLIT") }
        pitch.setOnClickListener { openAdvancedEditor("PITCH") }
        multitrack.setOnClickListener { openAdvancedEditor("MULTITRACK") }

        return scroll
    }

    private fun openAdvancedEditor(mode: String) {
        startActivity(Intent(this, AdvancedAudioEditorActivity::class.java).apply {
            putExtra("mode", mode)
        })
    }

    private fun buildBatchPage(): View {
        val (scroll, root) = pageRoot()
        val context = this
        val gap = UiKit.dp(this, 7)
        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG)
        }
        workspace.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, UiKit.dp(this, 16))

        fun section(label: String): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(UiKit.spacer(context, 16))
            addView(UiKit.sectionLabel(context, label))
            root.addView(this)
        }

        fun optionRow(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun option(row: LinearLayout, label: String): TextView =
            UiKit.text(context, label, 12f, UiKit.TEXT, true).apply {
                gravity = Gravity.CENTER
                minimumHeight = UiKit.dp(context, 48)
                isClickable = true
                isFocusable = true
                row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (row.childCount > 0) marginStart = gap
                })
            }

        fun setOption(view: TextView, active: Boolean) {
            view.isSelected = active
            view.setTextColor(if (active) UiKit.onPrimaryContainer(context) else UiKit.TEXT)
            view.background = UiKit.ripple(context, if (active) UiKit.primaryContainer(context) else UiKit.SURFACE_2, 12)
        }

        val titleRow = optionRow()
        titleRow.addView(UiKit.text(this, "转码", 29f, UiKit.TEXT, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val history = UiKit.wideButton(this, "◷", "转换记录").apply {
            textSize = 12f
            minimumHeight = UiKit.dp(context, 48)
            setOnClickListener { showHistory() }
        }
        titleRow.addView(history)
        root.addView(titleRow)
        root.addView(UiKit.text(this, "先确认文件，再选输出格式。", 13f, UiKit.TEXT_2).apply {
            setPadding(0, UiKit.dp(context, 6), 0, UiKit.dp(context, 15))
        })

        val modes = optionRow().also { root.addView(it) }
        val singleMode = option(modes, "单文件")
        val batchMode = option(modes, "批量")

        val singleSection = section("待转换文件")
        val singleCard = UiKit.card(this, 17)
        selectedText = UiKit.text(
            this, "尚未选择文件", 14f, UiKit.TEXT
        ).apply { setLineSpacing(0f, 1.15f) }
        singleCard.addView(selectedText)
        val chooseSingle = UiKit.wideButton(this, "+", "选择音乐文件").apply {
            setOnClickListener { chooseFile() }
        }
        UiKit.margins(chooseSingle, 0, 10, 0, 0)
        singleCard.addView(chooseSingle)
        singleSection.addView(singleCard)

        val batchSection = section("待转换文件")
        val scanCard = UiKit.groupCard(this)
        val scanButton = UiKit.groupButton(this, "⌕", "选择目录扫描").apply {
            setOnClickListener { chooseScanFolder() }
        }
        scanCard.addView(scanButton)
        fullStorageScanButton = UiKit.groupButton(this, "◎", "全盘扫描 · 已授权").apply {
            visibility = if (hasAllFilesAccess()) View.VISIBLE else View.GONE
            setOnClickListener { confirmFullStorageScan() }
        }
        scanCard.addView(fullStorageScanButton)
        batchSection.addView(scanCard)
        batchSection.addView(UiKit.spacer(this, 8))
        val batchCard = UiKit.card(this, 17)
        batchText = UiKit.text(
            this, "尚未扫描目录\n选择目录后即可批量处理", 13.5f, UiKit.TEXT
        ).apply { setLineSpacing(0f, 1.18f) }
        batchCard.addView(batchText)
        batchPauseButton = UiKit.wideButton(this, "Ⅱ", "完成当前任务后暂停").apply {
            isEnabled = false
            visibility = View.GONE
            setOnClickListener {
                val snapshot = BatchConversionService.snapshot()
                val ok = if (snapshot.paused || snapshot.pausing) {
                    BatchConversionService.requestResume(context)
                } else {
                    BatchConversionService.requestPause(context)
                }
                if (!ok) toast("当前没有可暂停或继续的批量任务")
            }
        }
        UiKit.margins(batchPauseButton, 0, 10, 0, 0)
        batchCard.addView(batchPauseButton)
        batchSection.addView(batchCard)

        val formatSection = section("输出格式")
        val formats = optionRow().also { formatSection.addView(it) }
        val commonFormats = listOf(AudioOutputFormat.MP3, AudioOutputFormat.FLAC, AudioOutputFormat.WAV)
        val formatOptions = commonFormats.map { it to option(formats, it.label) }
        val moreFormats = AudioOutputFormat.values().filterNot { it in commonFormats }
        val more = option(formats, "更多")
        var target = AudioOutputFormat.MP3
        fun setTarget(format: AudioOutputFormat) {
            target = format
            formatOptions.forEach { (value, view) -> setOption(view, value == target) }
            val extraSelected = target in moreFormats
            setOption(more, extraSelected)
            more.text = if (extraSelected) target.extension.uppercase(java.util.Locale.ROOT) else "更多"
            more.contentDescription = if (extraSelected) "更多格式，已选择 ${target.label}" else "更多格式"
        }
        formatOptions.forEach { (value, view) ->
            view.setOnClickListener { setTarget(value) }
        }
        more.setOnClickListener {
            appDialogBuilder().setTitle("更多输出格式")
                .setItems(moreFormats.map { it.label }.toTypedArray()) { _, index ->
                    setTarget(moreFormats[index])
                }
                .show()
        }
        setTarget(AudioOutputFormat.MP3)

        root.addView(UiKit.spacer(this, 12))
        val outputCard = UiKit.groupCard(this)
        val outputInfo = UiKit.text(this, "", 13f, UiKit.TEXT_2).apply {
            setPadding(UiKit.dp(context, 16), UiKit.dp(context, 15), UiKit.dp(context, 16), UiKit.dp(context, 15))
        }
        outputCard.addView(outputInfo)
        val advancedToggle = UiKit.groupButton(this, "⌄", "高级选项")
        outputCard.addView(advancedToggle)
        val advancedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val singleAdvanced = UiKit.text(
            this, "转换完成后可另存为、保存到默认位置，或选择替换源文件。", 12f, UiKit.TEXT_2
        ).apply {
            setPadding(UiKit.dp(context, 16), 0, UiKit.dp(context, 16), UiKit.dp(context, 15))
        }
        advancedContent.addView(singleAdvanced)
        val batchAdvanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ignoreFormatsButton = UiKit.groupButton(this, "⊘", "扫描过滤 · ${IgnoredFormatPreferences.summary(this)}").apply {
            setOnClickListener { showIgnoredFormatsDialog() }
        }
        batchAdvanced.addView(ignoreFormatsButton)
        batchAdvanced.addView(UiKit.text(
            this,
            "扫描自动跳过输出目录。并行任务数及源文件置换可在确认转换时调整。",
            12f,
            UiKit.TEXT_2
        ).apply {
            setPadding(UiKit.dp(context, 16), 0, UiKit.dp(context, 16), UiKit.dp(context, 15))
        })
        advancedContent.addView(batchAdvanced)
        outputCard.addView(advancedContent)
        root.addView(outputCard)
        advancedToggle.setOnClickListener {
            val expand = advancedContent.visibility != View.VISIBLE
            advancedContent.visibility = if (expand) View.VISIBLE else View.GONE
            advancedToggle.text = if (expand) "⌃   收起高级选项" else "⌄   高级选项"
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(context, 18), UiKit.dp(context, 3),
                UiKit.dp(context, 18), UiKit.dp(context, 4)
            )
        }
        val convertSingle = UiKit.wideButton(this, "⇄", "开始转换", true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                val item = selected
                if (item == null) toast("请先选择音乐文件")
                else runConversion(item, target)
            }
        }
        footer.addView(convertSingle)
        batchConvertButton = UiKit.wideButton(this, "⇉", "开始批量转换", true).apply {
            gravity = Gravity.CENTER
            isEnabled = false
            alpha = 0.45f
            setOnClickListener { showBatchConvertDialog(target) }
        }
        footer.addView(batchConvertButton)
        workspace.addView(footer)

        fun setBatchMode(isBatch: Boolean) {
            setOption(singleMode, !isBatch)
            setOption(batchMode, isBatch)
            singleSection.visibility = if (isBatch) View.GONE else View.VISIBLE
            batchSection.visibility = if (isBatch) View.VISIBLE else View.GONE
            singleAdvanced.visibility = if (isBatch) View.GONE else View.VISIBLE
            batchAdvanced.visibility = if (isBatch) View.VISIBLE else View.GONE
            convertSingle.visibility = if (isBatch) View.GONE else View.VISIBLE
            batchConvertButton.visibility = if (isBatch) View.VISIBLE else View.GONE
            outputInfo.text = if (isBatch) {
                "默认保存：Music/MusicConverter"
            } else {
                "保存位置：转换完成后选择"
            }
        }
        singleMode.setOnClickListener { setBatchMode(false) }
        batchMode.setOnClickListener { setBatchMode(true) }
        setBatchMode(BatchConversionService.snapshot().running)
        return workspace
    }

    private fun buildAboutPage(): ScrollView {
        val (scroll, root) = pageRoot()

        root.addView(UiKit.sectionTitle(this, "设置", "管理外观、存储、更新和应用选项"))
        root.addView(UiKit.spacer(this, 14))
        root.addView(UiKit.sectionLabel(this, "偏好设置"))
        val appearanceCard = UiKit.groupCard(this)
        val themeButton = UiKit.groupRow(this, "◐", "外观", ThemePreferences.label(this), "更改 ›")
        appearanceCard.addView(themeButton)
        appearanceCard.addView(UiKit.groupDivider(this))
        val historyButton = UiKit.groupRow(this, "◷", "转换 / 剪辑历史", "查看此前的本地处理结果", "查看 ›")
        historyButton.setOnClickListener { showHistory() }
        appearanceCard.addView(historyButton)
        appearanceCard.addView(UiKit.groupDivider(this))
        val miniPlayerSetting = CheckBox(this).apply {
            text = "底部播放器 · 默认显示"
            textSize = 14f
            setTextColor(UiKit.TEXT)
            setPadding(
                UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 12),
                UiKit.dp(this@MainActivity, 18), UiKit.dp(this@MainActivity, 12)
            )
            isChecked = getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                .getBoolean("mini_player_always_visible", true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
                    .edit().putBoolean("mini_player_always_visible", checked).apply()
                miniPlayerView?.setAlwaysVisible(checked)
            }
        }
        appearanceCard.addView(miniPlayerSetting)
        root.addView(appearanceCard)
        themeButton.setOnClickListener { showAppearanceDialog() }

        root.addView(UiKit.spacer(this, 18))
        root.addView(UiKit.sectionLabel(this, "系统与工具"))
        val toolsCard = UiKit.groupCard(this)
        allFilesAccessButton = UiKit.groupButton(this, "▣", allFilesAccessButtonTitle())
        toolsCard.addView(allFilesAccessButton)
        toolsCard.addView(UiKit.groupDivider(this))
        val backgroundButton = UiKit.groupButton(this, "⚡", "后台耗电 / 电池优化")
        toolsCard.addView(backgroundButton)
        toolsCard.addView(UiKit.groupDivider(this))
        val appScanButton = UiKit.groupButton(this, "⌁", "扫描已安装音乐 APP")
        toolsCard.addView(appScanButton)
        toolsCard.addView(UiKit.groupDivider(this))
        val diagnosticsButton = UiKit.groupButton(this, "✓", "FFmpeg 运行检测")
        toolsCard.addView(diagnosticsButton)
        root.addView(toolsCard)

        allFilesAccessButton.setOnClickListener { requestOptionalAllFilesAccess() }
        backgroundButton.setOnClickListener { requestBackgroundBatteryAccess() }
        appScanButton.setOnClickListener { scanMusicApps() }
        diagnosticsButton.setOnClickListener {
            status.text = "状态：正在检测 FFmpeg…"
            Thread {
                val result = FfmpegEngine.availability()
                runOnUiThread { status.text = "状态：${result.message}" }
            }.start()
        }

        root.addView(UiKit.spacer(this, 18))
        root.addView(UiKit.sectionLabel(this, "存储"))
        val storageCard = UiKit.groupCard(this)
        val clearCacheButton = UiKit.groupButton(this, "⌫", "清理缓存")
        storageCard.addView(clearCacheButton)
        root.addView(storageCard)
        clearCacheButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清理缓存")
                .setMessage("将清除本地音乐列表缓存、联网封面缓存和应用临时缓存。不会删除音乐文件、转换结果、播放设置或其他个人设置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理") { _, _ ->
                    runCatching { localMusicCacheFile().delete() }
                    getSharedPreferences("online_cover_cache_v1", Context.MODE_PRIVATE).edit().clear().apply()
                    getSharedPreferences("online_cover_cache_v2", Context.MODE_PRIVATE).edit().clear().apply()
                    runCatching {
                        cacheDir.deleteRecursively()
                        cacheDir.mkdirs()
                    }
                    toast("缓存已清理；本地音乐列表可在首页重新刷新")
                }
                .show()
        }

        root.addView(UiKit.spacer(this, 18))
        root.addView(UiKit.sectionLabel(this, "更新与社区"))
        val updateCard = UiKit.card(this, 24)
        updateCard.addView(UiKit.text(this, "当前版本  ·  v${appVersionName}", 14.5f, UiKit.TEXT, true))
        updateCard.addView(
            UiKit.text(
                this,
                "GitHub 优先 · 腾讯文档备用\nAPK 下载：GitHub / 夸克 / 蓝奏云",
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
        val telegramRow = UiKit.groupRow(this, "✈", "Telegram 群聊", "MusicConverter_YJ_Lazy", "加入 ›").apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val telegramUri = Uri.parse("https://t.me/MusicConverter_YJ_Lazy")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, telegramUri))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "未检测到可打开 Telegram 的应用", Toast.LENGTH_LONG).show()
                }
            }
        }
        updateCard.addView(UiKit.spacer(this, 8))
        updateCard.addView(UiKit.groupDivider(this))
        updateCard.addView(telegramRow)
        root.addView(updateCard)
        checkUpdateButton.setOnClickListener { maybeCheckRemoteUpdate(manual = true) }

        // 介绍保留在设置页最下方。
        root.addView(UiKit.spacer(this, 24))
        root.addView(UiKit.sectionLabel(this, "介绍"))
        val intro = UiKit.card(this, 28).apply {
            background = UiKit.rounded(UiKit.primaryContainer(this@MainActivity), 28, this@MainActivity)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 20), UiKit.dp(this@MainActivity, 20)
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
            FrameLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)).apply { gravity = Gravity.CENTER }
        )
        intro.addView(iconWrap, LinearLayout.LayoutParams(UiKit.dp(this, 58), UiKit.dp(this, 58)))
        intro.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(UiKit.dp(this@MainActivity, 16), 0, 8, 0)
                addView(UiKit.text(this@MainActivity, "MusicConverter", 19f, UiKit.onPrimaryContainer(this@MainActivity), true))
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
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        intro.addView(UiKit.badge(this, "v${appVersionName}", true))
        root.addView(intro)

        root.addView(UiKit.spacer(this, 18))
        val capability = UiKit.groupCard(this)
        capability.addView(UiKit.groupRow(this, "⇄", "格式转换", "MP3 / FLAC / M4A / WAV / OGG"))
        capability.addView(UiKit.groupDivider(this))
        capability.addView(UiKit.groupRow(this, "◇", "加密格式", "NCM / QMC / KGM / KWM 等"))
        capability.addView(UiKit.groupDivider(this))
        capability.addView(UiKit.groupRow(this, "✂", "音频剪辑", "波形、选区、试听、暂停与导出"))
        capability.addView(UiKit.groupDivider(this))
        capability.addView(UiKit.groupRow(this, "≋", "批量处理", "SAF 目录扫描或授权后的全盘扫描"))
        capability.addView(UiKit.groupDivider(this))
        capability.addView(UiKit.groupRow(this, "⚡", "后台转换", "前台服务显示进度，并可申请忽略电池优化"))
        root.addView(capability)

        root.addView(UiKit.spacer(this, 18))
        val infoCard = UiKit.groupCard(this)
        infoCard.addView(UiKit.groupRow(this, "▣", "隐私与文件", "核心处理在本机完成；默认通过 Android SAF 访问用户主动选择的文件"))
        infoCard.addView(UiKit.groupDivider(this))
        infoCard.addView(UiKit.groupRow(this, "⌘", "开源组件", "FFmpeg / FFmpegKit · Chaquopy · Room · music-geshizhuanhuan · QMCDecode · Kugo-Music-Converter"))
        root.addView(infoCard)

        root.addView(UiKit.spacer(this, 20))
        root.addView(
            UiKit.text(this, "请仅处理你有权访问和转换的本地音频文件。", 11.5f, UiKit.TEXT_3).apply {
                gravity = Gravity.CENTER
            }
        )
        return scroll
    }

    private fun showAppearanceDialog() {
        val modes = ThemePreferences.Mode.values()
        val selected = ThemePreferences.mode(this)
        appDialogBuilder()
            .setTitle("外观")
            .setSingleChoiceItems(modes.map { it.title }.toTypedArray(), selected.ordinal) { dialog, index ->
                dialog.dismiss()
                if (modes[index] != selected) {
                    ThemePreferences.setMode(this, modes[index])
                    recreate()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

                        if (!manual && RemoteUpdateManager.isStayAllowed(result.info, appVersionCode)) {
                            // 后台允许当前版本长期停留：自动检查完全静默，不打扰用户。
                            status.text = "状态：当前版本可继续使用"
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

        val hasGithub = info.githubUrl.isNotBlank()
        val hasQuark = info.quarkUrl.startsWith("https://pan.quark.cn/")
        val hasLanzou = info.lanzouUrl.isNotBlank()

        val message = buildString {
            append("当前版本：v${appVersionName}\n")
            append("最新版本：v${info.versionName}\n")
            append("下载来源：")
            val sources = mutableListOf<String>()
            if (hasGithub) sources += "GitHub"
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

        if (info.githubUrl.isNotBlank()) {
            labels += "GitHub Releases"
            actions += { openGithubUpdate(info.githubUrl) }
        }
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

    private fun openGithubUpdate(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val valid = url.startsWith("https://") &&
            uri?.host.equals("github.com", ignoreCase = true) &&
            uri?.path.orEmpty().startsWith("/YJ-Lazy/MusicConverter/releases")
        if (!valid || uri == null) {
            toast("GitHub 更新链接无效")
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { toast("无法打开 GitHub 链接，请确认已安装浏览器") }
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
            com.musicconverter.miui.R.style.AppDialogLight
        }
        return AlertDialog.Builder(this, dialogTheme)
    }

    private fun tintDialogButtons(dialog: AlertDialog) {
        val accent = if (ThemePreferences.isDark(this)) {
            UiKit.ACCENT
        } else {
            UiKit.ACCENT
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
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1501) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                loadLocalMusic()
            } else {
                localMusicStatus?.text = "未授权音乐读取权限，无法显示本地音乐"
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == localMusicPickCode) {
            if (resultCode == RESULT_OK && data != null) addLocalMusicDocuments(data)
            return
        }

        if (requestCode == saveConvertedAsCode) {
            if (resultCode != RESULT_OK) {
                status.text = "状态：已取消另存为"
                return
            }
            val target = data?.data ?: return
            val source = pendingConvertedFile
            val name = pendingConvertedName
            val item = pendingConvertedSource
            if (source == null || !source.exists()) {
                toast("转换结果已失效，请重新转换")
                return
            }
            status.text = "状态：正在另存为…"
            Thread {
                try {
                    AudioFileManager.saveToUri(this, source, target)
                    item?.let { HistoryRepository(this).record(it.displayName, name, "保存", "用户选择位置") }
                    source.delete()
                    clearPendingConverted(source)
                    runOnUiThread {
                        status.text = "状态：另存为完成 · $name"
                        toast("文件已保存到所选位置")
                    }
                } catch (t: Throwable) {
                    runOnUiThread { status.text = "另存为失败：${t.message}" }
                }
            }.start()
            return
        }

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

    private fun runConversion(item: PreparedAudio, target: AudioOutputFormat) {
        status.text = "状态：正在处理 ${item.displayName} → ${target.label}…"
        Thread {
            val result = ConversionEngine(this).convertToTemp(item, target)
            runOnUiThread {
                if (!result.success || result.tempFile == null) {
                    status.text = "处理失败：${result.error.ifBlank { "找不到转换结果" }}"
                    return@runOnUiThread
                }
                pendingConvertedFile?.takeIf { it != result.tempFile }?.delete()
                pendingConvertedFile = result.tempFile
                pendingConvertedName = result.outputName
                pendingConvertedSource = item
                status.text = "状态：转换完成 · 请选择保存方式"
                showConvertedSaveOptions(item, result.tempFile, result.outputName)
            }
        }.start()
    }

    private fun showConvertedSaveOptions(
        item: PreparedAudio,
        convertedFile: java.io.File,
        outputName: String
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 22),
                UiKit.dp(this@MainActivity, 8),
                UiKit.dp(this@MainActivity, 22),
                UiKit.dp(this@MainActivity, 8)
            )
        }

        content.addView(
            UiKit.text(
                this,
                "已生成：$outputName\n请选择保存方式。",
                15f,
                UiKit.TEXT
            ).apply {
                setLineSpacing(0f, 1.18f)
                setPadding(0, 0, 0, UiKit.dp(this@MainActivity, 16))
            }
        )

        val saveAsButton = UiKit.wideButton(
            this,
            "⇩",
            "另存为…",
            true
        )
        val replaceButton = UiKit.wideButton(
            this,
            "⇄",
            "替换源文件"
        )
        val defaultButton = UiKit.wideButton(
            this,
            "✓",
            "保存到默认位置"
        )

        content.addView(
            saveAsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 54)
            )
        )

        content.addView(
            replaceButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 54)
            ).apply {
                topMargin = UiKit.dp(this@MainActivity, 10)
            }
        )

        content.addView(
            defaultButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 54)
            ).apply {
                topMargin = UiKit.dp(this@MainActivity, 10)
            }
        )

        val dialog = appDialogBuilder()
            .setTitle("处理完成")
            .setView(content)
            .setNegativeButton("取消") { _, _ ->
                status.text = "状态：转换结果暂未保存"
            }
            .create()

        dialog.setOnShowListener {
            tintDialogButtons(dialog)

            saveAsButton.setOnClickListener {
                dialog.dismiss()
                launchSaveAsPicker(outputName)
            }

            replaceButton.setOnClickListener {
                dialog.dismiss()
                replaceSourceWithConverted(item, convertedFile, outputName)
            }

            defaultButton.setOnClickListener {
                dialog.dismiss()
                saveConvertedToDefault(item, convertedFile, outputName)
            }
        }

        dialog.show()
    }

    private fun launchSaveAsPicker(outputName: String) {
        val file = pendingConvertedFile
        if (file == null || !file.exists()) {
            toast("转换结果已失效，请重新转换")
            return
        }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = AudioFileManager.mimeFor(outputName)
                putExtra(Intent.EXTRA_TITLE, outputName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            },
            saveConvertedAsCode
        )
    }

    private fun saveConvertedToDefault(item: PreparedAudio, convertedFile: java.io.File, outputName: String) {
        status.text = "状态：正在保存到默认位置…"
        Thread {
            try {
                AudioFileManager.publishAudio(this, convertedFile, outputName)
                HistoryRepository(this).record(item.displayName, outputName, "保存", "默认位置")
                convertedFile.delete()
                clearPendingConverted(convertedFile)
                runOnUiThread {
                    status.text = "完成：Music/MusicConverter/$outputName"
                    toast("已保存到 Music/MusicConverter")
                }
            } catch (t: Throwable) {
                runOnUiThread { status.text = "保存失败：${t.message}" }
            }
        }.start()
    }

    private fun replaceSourceWithConverted(item: PreparedAudio, convertedFile: java.io.File, outputName: String) {
        status.text = "状态：正在替换源文件…"
        Thread {
            var published: Uri? = null
            try {
                published = AudioFileManager.publishAudio(this, convertedFile, outputName)
                val replaced = AudioFileManager.replaceOriginal(
                    this, item.originalUri, item.displayName, published, outputName, item.localFile
                )
                if (!replaced.success) {
                    runCatching { contentResolver.delete(published, null, null) }
                    runOnUiThread {
                        status.text = "替换失败：${replaced.message}"
                        toast("替换失败，源文件已保留")
                    }
                    return@Thread
                }
                convertedFile.delete()
                clearPendingConverted(convertedFile)
                HistoryRepository(this).record(item.displayName, outputName, "转换", "已替换源文件")
                runOnUiThread {
                    selected = null
                    selectedText.text = "源文件已被转换结果替换\n请重新选择文件继续处理"
                    status.text = "状态：已替换源文件 · $outputName"
                    toast("已替换源文件")
                }
            } catch (t: Throwable) {
                published?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
                runOnUiThread { status.text = "替换失败：${t.message}" }
            }
        }.start()
    }

    private fun clearPendingConverted(file: java.io.File) {
        if (pendingConvertedFile?.absolutePath == file.absolutePath) {
            pendingConvertedFile = null
            pendingConvertedName = ""
            pendingConvertedSource = null
        }
    }

    private fun showBatchConvertDialog(target: AudioOutputFormat) {
        if (batchRunning) {
            toast("批量任务正在运行")
            return
        }

        val files = scannedBatch
        if (files.isEmpty()) {
            toast("请先扫描磁盘")
            return
        }

        val encrypted = files.count { AudioFormatDetector.isEncrypted(it.displayName) }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiKit.dp(this@MainActivity, 22), UiKit.dp(this@MainActivity, 4), UiKit.dp(this@MainActivity, 22), 0)
        }
        content.addView(UiKit.text(this, "输出格式：${target.label}", 14f, UiKit.TEXT, true))
        val advancedToggle = UiKit.groupButton(this, "⌄", "高级选项").apply {
            setPadding(0, 0, 0, 0)
        }
        content.addView(advancedToggle)
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(advanced)
        advancedToggle.setOnClickListener {
            val expand = advanced.visibility != View.VISIBLE
            advanced.visibility = if (expand) View.VISIBLE else View.GONE
            advancedToggle.text = if (expand) "⌃   收起高级选项" else "⌄   高级选项"
        }

        val recommendedWorkers = BatchConversionService.recommendedParallelism()
        advanced.addView(UiKit.text(this, "并行转换任务数", 13f, UiKit.TEXT_2, true).apply {
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
            }
            workerGroup.addView(rb)
        }
        val recommendedButton = (0 until workerGroup.childCount)
            .mapNotNull { workerGroup.getChildAt(it) as? RadioButton }
            .firstOrNull { it.tag == recommendedWorkers }
        recommendedButton?.let { workerGroup.check(it.id) }
        advanced.addView(workerGroup)
        advanced.addView(UiKit.text(
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
        advanced.addView(replaceSource)
        advanced.addView(UiKit.text(
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
                    val replace = replaceSource.isChecked
                    val workerChecked = workerGroup.findViewById<RadioButton>(workerGroup.checkedRadioButtonId)
                    val workers = (workerChecked?.tag as? Int) ?: recommendedWorkers
                    dialog.dismiss()
                    runBatchConversion(files, target, replace, workers)
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
            batchPauseButton.visibility = View.VISIBLE
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
            batchPauseButton.visibility = View.GONE
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
        val item = selected
        if (item == null) {
            status.text = "状态：进入剪辑器 · 可在页面内选择文件"
            startActivity(Intent(this, AudioEditorActivity::class.java))
            return
        }
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 22),
                UiKit.dp(this@MainActivity, 8),
                UiKit.dp(this@MainActivity, 22),
                UiKit.dp(this@MainActivity, 8)
            )
        }

        content.addView(
            UiKit.text(
                this,
                "已生成：${result.outputName}\n请选择源文件处理方式。",
                15f,
                UiKit.TEXT
            ).apply {
                setLineSpacing(0f, 1.18f)
                setPadding(
                    0,
                    0,
                    0,
                    UiKit.dp(this@MainActivity, 16)
                )
            }
        )

        val keepButton = UiKit.wideButton(
            this,
            "✓",
            "保留源文件",
            true
        )
        val deleteButton = UiKit.wideButton(
            this,
            "⌫",
            "删除源文件"
        )
        val replaceButton = UiKit.wideButton(
            this,
            "⇄",
            "用转换结果置换源文件"
        )

        content.addView(
            keepButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 54)
            )
        )

        UiKit.margins(deleteButton, 0, 10, 0, 0)
        content.addView(
            deleteButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 54)
            )
        )

        UiKit.margins(replaceButton, 0, 10, 0, 0)
        content.addView(
            replaceButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiKit.dp(this, 54)
            )
        )

        val dialog = appDialogBuilder()
            .setTitle("处理完成")
            .setView(content)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            keepButton.setOnClickListener {
                status.text = "状态：转换完成 · 已保留源文件"
                dialog.dismiss()
            }

            deleteButton.setOnClickListener {
                val ok = DeleteManager.delete(this, item.originalUri)
                toast(
                    if (ok) {
                        "源文件已删除"
                    } else {
                        "无法删除源文件，请检查文件提供方权限"
                    }
                )

                if (ok) {
                    status.text = "状态：转换完成 · 源文件已删除"
                    dialog.dismiss()
                }
            }

            replaceButton.setOnClickListener {
                if (outputUri == null) {
                    toast("找不到转换结果，未修改源文件")
                    return@setOnClickListener
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
                    selectedText.text =
                        "源文件已被转换结果置换\n请重新选择文件继续处理"
                    status.text =
                        "状态：已置换源文件 · ${result.outputName}"
                    toast("已用转换结果置换源文件")
                    dialog.dismiss()
                } else {
                    toast("置换失败：${replace.message}；源文件已保留")
                }
            }
        }

        dialog.show()
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
