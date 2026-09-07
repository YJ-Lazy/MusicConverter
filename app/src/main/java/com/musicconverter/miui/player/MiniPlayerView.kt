package com.musicconverter.miui.player

import android.content.Context
import android.content.Intent
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.musicconverter.miui.ui.UiKit
import kotlin.math.abs

class MiniPlayerView(context: Context) : LinearLayout(context), PlayerController.Listener {
    private val cover = ImageView(context)
    private val title = UiKit.text(context, "暂无播放", 14f, UiKit.TEXT, true)
    private val artist = UiKit.text(context, "在线音乐或本地音乐选择歌曲", 11f, UiKit.TEXT_3)
    private val play = UiKit.text(context, "▶", 22f, UiKit.TEXT, true)
    private var alwaysVisible = true
    private var hasTrack = false

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (hasTrack) context.startActivity(Intent(context, NowPlayingActivity::class.java))
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!hasTrack) return false
            val s = e1 ?: return false
            val dx = e2.x - s.x
            val dy = e2.y - s.y
            if (abs(dx) > abs(dy) && abs(dx) > UiKit.dp(context, 65)) {
                if (dx < 0) PlayerController.next() else PlayerController.previous()
                return true
            }
            return false
        }
    })

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(UiKit.dp(context, 8), UiKit.dp(context, 7), UiKit.dp(context, 8), UiKit.dp(context, 7))
        background = UiKit.rounded(UiKit.SURFACE, 20, context)
        elevation = UiKit.dp(context, 4).toFloat()

        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.background = UiKit.rounded(UiKit.primaryContainer(context), 14, context)
        addView(cover, LayoutParams(UiKit.dp(context, 48), UiKit.dp(context, 48)))

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(UiKit.dp(context, 10), 0, UiKit.dp(context, 6), 0)
            addView(title)
            addView(artist)
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        play.gravity = Gravity.CENTER
        play.alpha = 0.55f
        play.setOnClickListener { if (hasTrack) PlayerController.toggle() }
        addView(play, LayoutParams(UiKit.dp(context, 48), UiKit.dp(context, 48)))

        setOnTouchListener { _, e -> gesture.onTouchEvent(e) }
        PlayerController.addListener(this)
        refreshVisibility()
    }

    fun setAlwaysVisible(enabled: Boolean) {
        alwaysVisible = enabled
        refreshVisibility()
    }

    private fun refreshVisibility() {
        visibility = if (alwaysVisible || hasTrack) View.VISIBLE else View.GONE
    }

    override fun onDetachedFromWindow() {
        PlayerController.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onQueue(queue: List<Track>, index: Int) {
        post {
            hasTrack = queue.isNotEmpty() && index in queue.indices
            if (!hasTrack) {
                title.text = "暂无播放"
                artist.text = "在线音乐或本地音乐选择歌曲"
                cover.setImageDrawable(null)
                play.text = "▶"
                play.alpha = 0.55f
            }
            refreshVisibility()
        }
    }

    override fun onTrack(track: Track?, index: Int) {
        post {
            hasTrack = track != null
            if (track == null) {
                title.text = "暂无播放"
                artist.text = "在线音乐或本地音乐选择歌曲"
                cover.setImageDrawable(null)
                play.text = "▶"
                play.alpha = 0.55f
            } else {
                title.text = track.title
                artist.text = track.artist ?: "左右滑切歌 · 点击展开"
                cover.setImageDrawable(null)
                play.alpha = 1f
            }
            refreshVisibility()
        }
        if (track != null) ImageLoader.load(track.artwork, cover)
    }

    override fun onState(playing: Boolean) {
        post {
            play.text = if (playing) "Ⅱ" else "▶"
            play.alpha = if (hasTrack) 1f else 0.55f
        }
    }
}
