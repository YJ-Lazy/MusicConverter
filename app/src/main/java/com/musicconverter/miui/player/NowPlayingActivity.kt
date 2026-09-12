package com.musicconverter.miui.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.musicconverter.miui.ui.UiKit
import kotlin.math.abs

class NowPlayingActivity : Activity(), PlayerController.Listener {
    private val addTracksCode = 1601

    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var play: TextView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var queueButton: TextView
    private lateinit var modeButton: TextView
    private var dragging = false
    private var queueDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.musicconverter.miui.ui.UiKit.prepareActivity(this)
        super.onCreate(savedInstanceState)
        PlayerController.initialize(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                UiKit.dp(this@NowPlayingActivity, 20),
                UiKit.dp(this@NowPlayingActivity, 16),
                UiKit.dp(this@NowPlayingActivity, 20),
                UiKit.dp(this@NowPlayingActivity, 24)
            )
            setBackgroundColor(UiKit.BG)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = UiKit.text(this, "‹", 34f, UiKit.TEXT, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)))
        top.addView(
            UiKit.text(this, "正在播放", 16f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1f)
        )
        queueButton = UiKit.text(this, "播放列表 0", 11.5f, UiKit.ACCENT_2, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showQueueDialog() }
        }
        top.addView(queueButton, LinearLayout.LayoutParams(UiKit.dp(this, 92), UiKit.dp(this, 48)))
        root.addView(top, LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50))

        root.addView(UiKit.spacer(this, 22))
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = UiKit.rounded(
                UiKit.primaryContainer(this@NowPlayingActivity),
                28,
                this@NowPlayingActivity
            )
        }
        val size = (resources.displayMetrics.widthPixels - UiKit.dp(this, 48))
            .coerceAtMost(UiKit.dp(this, 430))
        root.addView(cover, LinearLayout.LayoutParams(size, size))
        root.addView(UiKit.spacer(this, 24))

        title = UiKit.text(this, "未播放", 25f, UiKit.TEXT, true).apply { gravity = Gravity.CENTER }
        artist = UiKit.text(this, "", 14f, UiKit.TEXT_3).apply { gravity = Gravity.CENTER }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(artist, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        root.addView(UiKit.spacer(this, 20))
        seek = SeekBar(this)
        time = UiKit.text(this, "00:00 / 00:00", 12f, UiKit.TEXT_3).apply { gravity = Gravity.END }
        root.addView(seek, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(time, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        val prev = UiKit.text(this, "‹‹", 30f, UiKit.TEXT, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { PlayerController.previous() }
        }
        play = UiKit.text(this, "▶", 34f, UiKit.TEXT, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { PlayerController.toggle() }
        }
        val next = UiKit.text(this, "››", 30f, UiKit.TEXT, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { PlayerController.next() }
        }
        controls.addView(prev, LinearLayout.LayoutParams(UiKit.dp(this, 80), UiKit.dp(this, 72)))
        controls.addView(play, LinearLayout.LayoutParams(UiKit.dp(this, 96), UiKit.dp(this, 84)))
        controls.addView(next, LinearLayout.LayoutParams(UiKit.dp(this, 80), UiKit.dp(this, 72)))
        root.addView(controls)

        modeButton = UiKit.text(
            this,
            "↻ ${PlayerController.mode().label}",
            13f,
            UiKit.ACCENT_2,
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, UiKit.dp(this@NowPlayingActivity, 8), 0, UiKit.dp(this@NowPlayingActivity, 8))
            setOnClickListener {
                text = "↻ ${PlayerController.cycleMode().label}"
            }
        }
        root.addView(modeButton, LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44))

        root.addView(
            UiKit.text(this, "封面左右滑：上一首 / 下一首", 12f, UiKit.TEXT_3).apply {
                gravity = Gravity.CENTER
            }
        )

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                PlayerController.seekTo(s.progress)
                dragging = false
            }
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
        })

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                vx: Float,
                vy: Float
            ): Boolean {
                val s = e1 ?: return false
                val dx = e2.x - s.x
                val dy = e2.y - s.y
                if (abs(dx) > abs(dy) && abs(dx) > UiKit.dp(this@NowPlayingActivity, 70)) {
                    if (dx < 0) PlayerController.next() else PlayerController.previous()
                    return true
                }
                return false
            }
        })
        cover.setOnTouchListener { _, e -> gd.onTouchEvent(e) }

        setContentView(root)
    }

    private fun showQueueDialog() {
        queueDialog?.dismiss()
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@NowPlayingActivity, 14),
                UiKit.dp(this@NowPlayingActivity, 6),
                UiKit.dp(this@NowPlayingActivity, 14),
                UiKit.dp(this@NowPlayingActivity, 8)
            )
        }
        scroll.addView(box)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val add = UiKit.text(this, "＋ 添加本地音乐", 13f, UiKit.ACCENT_2, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { chooseTracksForQueue() }
        }
        actions.addView(add, LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1f))
        val clear = UiKit.text(this, "清空", 12f, UiKit.TEXT_3, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                PlayerController.clearQueue()
                showQueueDialog()
            }
        }
        actions.addView(clear, LinearLayout.LayoutParams(UiKit.dp(this, 70), UiKit.dp(this, 48)))
        box.addView(actions)

        val queue = PlayerController.snapshot()
        val current = PlayerController.currentIndex()
        if (queue.isEmpty()) {
            box.addView(
                UiKit.text(this, "当前播放列表为空", 13f, UiKit.TEXT_3).apply {
                    setPadding(0, UiKit.dp(this@NowPlayingActivity, 18), 0, UiKit.dp(this@NowPlayingActivity, 18))
                    gravity = Gravity.CENTER
                }
            )
        } else {
            queue.forEachIndexed { index, track ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, UiKit.dp(this@NowPlayingActivity, 5), 0, UiKit.dp(this@NowPlayingActivity, 5))
                }
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        UiKit.text(
                            this@NowPlayingActivity,
                            if (index == current) "▶ ${track.title}" else track.title,
                            13.5f,
                            if (index == current) UiKit.ACCENT_2 else UiKit.TEXT,
                            index == current
                        )
                    )
                    addView(UiKit.text(this@NowPlayingActivity, track.artist, 11f, UiKit.TEXT_3))
                    setOnClickListener {
                        PlayerController.play(index)
                    }
                }
                row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                val remove = UiKit.text(this, "删除", 11.5f, UiKit.TEXT_3, true).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        PlayerController.removeAt(index)
                        showQueueDialog()
                    }
                }
                row.addView(remove, LinearLayout.LayoutParams(UiKit.dp(this, 60), UiKit.dp(this, 44)))
                box.addView(row)
                if (index < queue.lastIndex) box.addView(UiKit.groupDivider(this, 0))
            }
        }

        queueDialog = AlertDialog.Builder(this)
            .setTitle("当前播放列表")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun chooseTracksForQueue() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, addTracksCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != addTracksCode || resultCode != RESULT_OK || data == null) return

        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        data.data?.let { if (it !in uris) uris += it }

        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = displayName(uri)
            PlayerController.add(
                Track(
                    id = "picker:${uri}",
                    title = name.substringBeforeLast('.').ifBlank { name },
                    artist = "本地音乐",
                    streamUrl = uri.toString(),
                    source = "local"
                ),
                playNow = false
            )
        }
        if (PlayerController.current() == null && PlayerController.snapshot().isNotEmpty()) {
            PlayerController.play(0)
        }
        showQueueDialog()
    }

    private fun displayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else null
                }
        }.getOrNull() ?: "本地音乐"
    }

    override fun onStart() {
        super.onStart()
        PlayerController.addListener(this)
    }

    override fun onStop() {
        PlayerController.removeListener(this)
        super.onStop()
    }

    override fun onTrack(track: Track?, index: Int) {
        runOnUiThread {
            title.text = track?.title ?: "未播放"
            artist.text = track?.artist.orEmpty()
            cover.setImageDrawable(null)
        }
        if (track != null) ImageLoader.load(track.artwork, cover)
    }

    override fun onQueue(queue: List<Track>, index: Int) {
        runOnUiThread {
            queueButton.text = "播放列表 ${queue.size}"
        }
    }

    override fun onState(playing: Boolean) {
        runOnUiThread { play.text = if (playing) "Ⅱ" else "▶" }
    }

    override fun onProgress(position: Int, duration: Int) {
        runOnUiThread {
            seek.max = duration.coerceAtLeast(1)
            if (!dragging) seek.progress = position.coerceIn(0, seek.max)
            time.text = "${fmt(position)} / ${fmt(duration)}"
        }
    }

    private fun fmt(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
