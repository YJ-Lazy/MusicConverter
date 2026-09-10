package com.musicconverter.miui.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.musicconverter.miui.player.NowPlayingActivity
import java.util.concurrent.CopyOnWriteArrayList

object PlayerController {
    enum class PlayMode(val label: String) {
        SEQUENTIAL("顺序播放"),
        REPEAT_ONE("单曲循环"),
        SHUFFLE("随机播放")
    }
    interface Listener {
        fun onTrack(track: Track?, index: Int) {}
        fun onState(playing: Boolean) {}
        fun onQueue(queue: List<Track>, index: Int) {}
        fun onProgress(position: Int, duration: Int) {}
        fun onError(message: String) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val queue = mutableListOf<Track>()
    private val main = Handler(Looper.getMainLooper())
    private var index = -1
    private var player: MediaPlayer? = null
    private var prepared = false
    private var appContext: Context? = null
    private var playMode = PlayMode.SEQUENTIAL

    private const val NOTIFICATION_CHANNEL_ID = "music_playback"
    private const val NOTIFICATION_ID = 2201

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && prepared) {
                val pos = runCatching { p.currentPosition }.getOrDefault(0)
                val dur = runCatching { p.duration }.getOrDefault(0)
                listeners.forEach { it.onProgress(pos, dur) }
            }
            main.postDelayed(this, 500)
        }
    }

    init { main.post(ticker) }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureNotificationChannel()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val context = appContext ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "音乐播放",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示当前正在播放的音乐"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun updatePlaybackNotification(track: Track?, playing: Boolean) {
        val context = appContext ?: return
        if (track == null) {
            cancelPlaybackNotification()
            return
        }
        ensureNotificationChannel()
        val intent = Intent(context, NowPlayingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(context, 2201, intent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track.title.ifBlank { "正在播放" })
            .setContentText(track.artist.ifBlank { if (playing) "正在播放" else "已暂停" })
            .setSubText(if (playing) "MusicConverter · 正在播放" else "MusicConverter · 已暂停")
            .setContentIntent(pendingIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelPlaybackNotification() {
        val context = appContext ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    fun mode(): PlayMode = playMode

    fun cycleMode(): PlayMode {
        playMode = when (playMode) {
            PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.SEQUENTIAL
        }
        return playMode
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
        l.onQueue(queue.toList(), index)
        l.onTrack(queue.getOrNull(index), index)
        l.onState(isPlaying())
    }

    fun removeListener(l: Listener) { listeners.remove(l) }
    fun snapshot() = queue.toList()
    fun currentIndex() = index
    fun current() = queue.getOrNull(index)
    fun isPlaying() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun add(track: Track, playNow: Boolean = true) {
        val existing = queue.indexOfFirst { it.id == track.id }
        val target = if (existing >= 0) existing else {
            queue += track
            queue.lastIndex
        }
        listeners.forEach { it.onQueue(queue.toList(), index) }
        if (playNow) play(target)
    }

    fun addAll(tracks: List<Track>, playNow: Boolean = true): Int {
        if (tracks.isEmpty()) return 0

        val firstTrackId = tracks.first().id
        val queuedIds = queue.mapTo(mutableSetOf()) { it.id }
        var addedCount = 0
        tracks.forEach { track ->
            if (queuedIds.add(track.id)) {
                queue += track
                addedCount++
            }
        }

        listeners.forEach { it.onQueue(queue.toList(), index) }
        if (playNow) {
            val target = queue.indexOfFirst { it.id == firstTrackId }
            if (target >= 0) play(target)
        }
        return addedCount
    }

    fun play(target: Int) {
        val track = queue.getOrNull(target) ?: return
        index = target
        prepared = false
        runCatching { player?.release() }
        val p = MediaPlayer()
        player = p
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        p.setOnPreparedListener {
            prepared = true
            runCatching { it.start() }
            listeners.forEach { l -> l.onState(true) }
            updatePlaybackNotification(track, true)
        }
        p.setOnCompletionListener {
            cancelPlaybackNotification()
            when (playMode) {
                PlayMode.REPEAT_ONE -> play(index)
                PlayMode.SHUFFLE -> play(randomNextIndex())
                PlayMode.SEQUENTIAL -> {
                    if (index >= queue.lastIndex) {
                        prepared = false
                        listeners.forEach { l -> l.onState(false) }
                    } else {
                        play(index + 1)
                    }
                }
            }
        }
        p.setOnErrorListener { _, what, extra ->
            listeners.forEach { it.onError("播放失败 $what/$extra") }
            cancelPlaybackNotification()
            true
        }
        try {
            if (track.streamUrl.startsWith("content://")) {
                val context = appContext ?: throw IllegalStateException("播放器尚未初始化")
                p.setDataSource(context, Uri.parse(track.streamUrl))
            } else {
                p.setDataSource(track.streamUrl)
            }
            p.prepareAsync()
            listeners.forEach { it.onTrack(track, index) }
            listeners.forEach { it.onQueue(queue.toList(), index) }
        } catch (t: Throwable) {
            listeners.forEach { it.onError(t.message ?: "无法播放") }
            cancelPlaybackNotification()
        }
    }

    fun toggle() {
        val p = player ?: run {
            if (queue.isNotEmpty()) play(if (index >= 0) index else 0)
            return
        }
        if (!prepared) return
        if (p.isPlaying) p.pause() else p.start()
        val playing = p.isPlaying
        listeners.forEach { it.onState(playing) }
        if (playing) {
            updatePlaybackNotification(current(), true)
        } else {
            cancelPlaybackNotification()
        }
    }

    fun next() {
        if (queue.isEmpty()) return
        if (playMode == PlayMode.SHUFFLE) {
            play(randomNextIndex())
        } else {
            play(if (index >= queue.lastIndex) 0 else index + 1)
        }
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (playMode == PlayMode.SHUFFLE) {
            play(randomNextIndex())
        } else {
            play(if (index <= 0) queue.lastIndex else index - 1)
        }
    }

    private fun randomNextIndex(): Int {
        if (queue.size <= 1) return 0
        var candidate = index
        while (candidate == index) candidate = queue.indices.random()
        return candidate
    }

    fun clearQueue() {
        runCatching { player?.release() }
        player = null
        prepared = false
        queue.clear()
        index = -1
        listeners.forEach { it.onTrack(null, -1) }
        listeners.forEach { it.onState(false) }
        listeners.forEach { it.onQueue(emptyList(), -1) }
        cancelPlaybackNotification()
    }

    fun seekTo(ms: Int) {
        if (prepared) runCatching { player?.seekTo(ms) }
    }

    fun removeAt(pos: Int) {
        if (pos !in queue.indices) return
        val wasCurrent = pos == index
        queue.removeAt(pos)
        if (queue.isEmpty()) {
            index = -1
            runCatching { player?.release() }
            player = null
            prepared = false
            listeners.forEach { it.onTrack(null, -1) }
            listeners.forEach { it.onState(false) }
            cancelPlaybackNotification()
        } else if (pos < index) {
            index--
        } else if (wasCurrent) {
            index = index.coerceAtMost(queue.lastIndex)
            play(index)
        }
        listeners.forEach { it.onQueue(queue.toList(), index) }
    }
}
