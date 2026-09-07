package com.musicconverter.miui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.musicconverter.miui.MainActivity
import com.musicconverter.miui.core.AudioFileManager
import com.musicconverter.miui.core.IgnoredFormatPreferences
import com.musicconverter.miui.core.AudioOutputFormat
import com.musicconverter.miui.core.ConversionEngine
import com.musicconverter.miui.core.FfmpegEngine
import com.musicconverter.miui.scanner.ScannedAudio
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground batch conversion service.
 *
 * v1.6 adds a small parallel worker pool. This deliberately avoids spawning several full
 * Android app processes (which would duplicate Python/FFmpeg native memory and increase the
 * chance of low-memory kills). Instead, several independent conversion sessions run in
 * parallel inside the foreground service, which is the useful part for throughput.
 */
class BatchConversionService : Service() {

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    @Volatile private var paused = false
    @Volatile private var executor: ExecutorService? = null
    private val activeNames = linkedSetOf<String>()
    private val activeLock = Any()
    private val pauseLock = Object()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                synchronized(pauseLock) {
                    stopRequested = true
                    pauseRequested = false
                    paused = false
                    pauseLock.notifyAll()
                }
                // “停止”仍然是立即停止：取消正在执行的 FFmpeg，会停止继续派发任务。
                FfmpegEngine.cancelAll()
                val state = currentState.copy(
                    pausing = false,
                    paused = false,
                    currentName = "正在停止并行批量任务…",
                    message = "正在停止并行批量任务…"
                )
                updateState(state)
                notificationManager.notify(NOTIFICATION_ID, buildNotification(state, indeterminate = false))
                broadcast(state)
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                if (currentState.running && !currentState.done) {
                    synchronized(pauseLock) {
                        pauseRequested = true
                    }
                    val active = activeCount()
                    val state = currentState.copy(
                        pausing = true,
                        paused = false,
                        currentName = if (active > 0) "等待当前 $active 个转换完成后暂停" else "正在等待当前转换结束后暂停",
                        message = if (active > 0) {
                            "已请求暂停：不会再启动新转换，当前 $active 个转换完成后自动暂停。"
                        } else {
                            "已请求暂停：不会再启动新转换，队列会在已派发任务全部结束后进入暂停。"
                        }
                    )
                    paused = false
                    updateState(state)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state, indeterminate = false))
                    broadcast(state)
                }
                return START_NOT_STICKY
            }

            ACTION_RESUME -> {
                if (currentState.running && !currentState.done) {
                    synchronized(pauseLock) {
                        pauseRequested = false
                        paused = false
                        pauseLock.notifyAll()
                    }
                    val state = currentState.copy(
                        pausing = false,
                        paused = false,
                        currentName = "正在恢复剩余转换…",
                        message = "已继续批量转换。"
                    )
                    updateState(state)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state, indeterminate = false))
                    broadcast(state)
                }
                return START_NOT_STICKY
            }
        }

        val jobPath = intent?.getStringExtra(EXTRA_JOB_FILE) ?: return START_NOT_STICKY
        val target = runCatching {
            AudioOutputFormat.valueOf(intent.getStringExtra(EXTRA_TARGET) ?: AudioOutputFormat.MP3.name)
        }.getOrDefault(AudioOutputFormat.MP3)
        val replaceSource = intent.getBooleanExtra(EXTRA_REPLACE_SOURCE, false)
        val requestedParallelism = intent.getIntExtra(EXTRA_PARALLELISM, recommendedParallelism())
            .coerceIn(1, MAX_PARALLELISM)

        synchronized(lock) {
            if (currentState.running) return START_NOT_STICKY
            stopRequested = false
            pauseRequested = false
            paused = false
            updateState(
                BatchProgressSnapshot(
                    running = true,
                    current = 0,
                    total = 0,
                    success = 0,
                    failed = 0,
                    replaced = 0,
                    replaceFailed = 0,
                    currentName = "准备并行批量转换",
                    replaceSource = replaceSource,
                    parallelism = requestedParallelism,
                    active = 0,
                    pausing = false,
                    paused = false,
                    done = false,
                    cancelled = false,
                    message = "正在读取任务列表…"
                )
            )
        }

        startAsForeground(buildNotification(currentState, indeterminate = true))
        Thread {
            executeJob(File(jobPath), target, replaceSource, requestedParallelism)
        }.apply { name = "BatchCoordinator" }.start()
        return START_REDELIVER_INTENT
    }

    private fun executeJob(
        jobFile: File,
        target: AudioOutputFormat,
        replaceSource: Boolean,
        requestedParallelism: Int
    ) {
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MusicConverter:BatchConversion"
        )
        var pool: ExecutorService? = null
        try {
            wakeLock.acquire(6 * 60 * 60 * 1000L)
            // 二次防护：即使任务来自旧扫描结果，也应用用户当前的忽略格式规则。
            val files = readJob(jobFile).filterNot {
                IgnoredFormatPreferences.shouldIgnore(this, it.displayName)
            }
            val workers = requestedParallelism.coerceIn(1, minOf(MAX_PARALLELISM, files.size.coerceAtLeast(1)))
            val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            // FFmpeg can also use internal threads. Split the CPU budget between concurrent jobs
            // to avoid every session trying to consume all cores at once.
            val ffmpegThreadsPerJob = (cpuCount / workers).coerceAtLeast(1)

            var success = 0
            var failed = 0
            var replaced = 0
            var replaceFailed = 0
            val failedNames = ArrayList<String>()
            val replaceFailedNames = ArrayList<String>()

            if (files.isEmpty()) {
                finishJob(
                    files = files,
                    success = 0,
                    failed = 0,
                    replaced = 0,
                    replaceFailed = 0,
                    replaceSource = replaceSource,
                    parallelism = workers,
                    failedNames = failedNames,
                    replaceFailedNames = replaceFailedNames,
                    cancelled = false
                )
                return
            }

            updateProgress(
                current = 0,
                total = files.size,
                success = 0,
                failed = 0,
                replaced = 0,
                replaceFailed = 0,
                currentName = "启动 $workers 路并行转换",
                replaceSource = replaceSource,
                parallelism = workers,
                active = 0
            )

            val threadId = AtomicInteger(1)
            val createdPool = Executors.newFixedThreadPool(workers) { runnable ->
                Thread(runnable, "ConvertWorker-${threadId.getAndIncrement()}").apply {
                    priority = Thread.NORM_PRIORITY
                }
            }
            pool = createdPool
            executor = createdPool
            val completion = ExecutorCompletionService<WorkResult>(createdPool)

            var nextIndex = 0
            var submitted = 0
            var completed = 0

            fun submitNext(): Boolean = synchronized(pauseLock) {
                if (stopRequested || pauseRequested || nextIndex >= files.size) return@synchronized false
                val source = files[nextIndex++]
                completion.submit(Callable {
                    processOne(source, target, replaceSource, ffmpegThreadsPerJob)
                })
                submitted++
                true
            }

            updateProgress(
                current = 0,
                total = files.size,
                success = success,
                failed = failed,
                replaced = replaced,
                replaceFailed = replaceFailed,
                currentName = "正在启动并行任务",
                replaceSource = replaceSource,
                parallelism = workers,
                active = 0
            )

            while (completed < files.size && !stopRequested) {
                // 只补足空闲 worker，不提前排满整个队列。这样点“暂停”后可以立即阻止新任务启动。
                while (!pauseRequested && !stopRequested && (submitted - completed) < workers && nextIndex < files.size) {
                    if (!submitNext()) break
                }

                // 仍有正在执行的任务：等待它自然完成。暂停不会取消这些转换。
                if (completed < submitted) {
                    val work = completion.take().get()
                    completed++

                    if (work.success) {
                        success++
                        if (replaceSource) {
                            if (work.replaced) {
                                replaced++
                            } else if (work.replaceError.isNotBlank()) {
                                replaceFailed++
                                if (replaceFailedNames.size < 12) {
                                    replaceFailedNames += "${work.sourceName}: ${work.replaceError.take(90)}"
                                }
                            }
                        }
                    } else {
                        failed++
                        if (failedNames.size < 12) {
                            failedNames += "${work.sourceName}: ${work.error.take(90)}"
                        }
                    }

                    updateProgress(
                        current = completed,
                        total = files.size,
                        success = success,
                        failed = failed,
                        replaced = replaced,
                        replaceFailed = replaceFailed,
                        currentName = if (pauseRequested) {
                            activeSummary("正在等待当前转换完成后暂停")
                        } else {
                            activeSummary(work.sourceName)
                        },
                        replaceSource = replaceSource,
                        parallelism = workers,
                        active = activeCount()
                    )
                    continue
                }

                if (stopRequested || nextIndex >= files.size) break

                // 此时没有活动任务，并且 pauseRequested 为 true：正式进入暂停状态。
                if (pauseRequested) {
                    paused = true
                    val pausedState = BatchProgressSnapshot(
                        running = true,
                        current = completed,
                        total = files.size,
                        success = success,
                        failed = failed,
                        replaced = replaced,
                        replaceFailed = replaceFailed,
                        currentName = "已暂停 · 剩余 ${files.size - completed} 个",
                        replaceSource = replaceSource,
                        parallelism = workers,
                        active = 0,
                        pausing = false,
                        paused = true,
                        done = false,
                        cancelled = false,
                        message = "当前转换已全部完成，批量队列现已暂停。点击继续恢复剩余任务。"
                    )
                    updateState(pausedState)
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(pausedState, indeterminate = false))
                    broadcast(pausedState)

                    // 暂停期间不需要保持 CPU 唤醒；前台服务和通知仍保留。
                    if (wakeLock.isHeld) runCatching { wakeLock.release() }
                    synchronized(pauseLock) {
                        while (pauseRequested && !stopRequested) {
                            pauseLock.wait()
                        }
                    }
                    paused = false
                    if (stopRequested) break
                    if (!wakeLock.isHeld) wakeLock.acquire(6 * 60 * 60 * 1000L)

                    updateProgress(
                        current = completed,
                        total = files.size,
                        success = success,
                        failed = failed,
                        replaced = replaced,
                        replaceFailed = replaceFailed,
                        currentName = "继续转换剩余 ${files.size - completed} 个文件",
                        replaceSource = replaceSource,
                        parallelism = workers,
                        active = 0
                    )
                }
            }

            finishJob(
                files = files,
                success = success,
                failed = failed,
                replaced = replaced,
                replaceFailed = replaceFailed,
                replaceSource = replaceSource,
                parallelism = workers,
                failedNames = failedNames,
                replaceFailedNames = replaceFailedNames,
                cancelled = stopRequested,
                processed = completed
            )
        } catch (t: Throwable) {
            val finalState = currentState.copy(
                running = false,
                done = true,
                currentName = "并行批量任务失败",
                active = 0,
                pausing = false,
                paused = false,
                message = "批量任务失败：${t.message ?: t.javaClass.simpleName}"
            )
            updateState(finalState)
            notificationManager.notify(NOTIFICATION_ID, buildCompletionNotification(finalState))
            broadcast(finalState)
        } finally {
            executor = null
            pool?.shutdownNow()
            synchronized(activeLock) { activeNames.clear() }
            runCatching { jobFile.delete() }
            if (wakeLock.isHeld) runCatching { wakeLock.release() }
            stopForeground(false)
            stopSelf()
        }
    }

    private fun processOne(
        source: ScannedAudio,
        target: AudioOutputFormat,
        replaceSource: Boolean,
        ffmpegThreadsPerJob: Int
    ): WorkResult {
        synchronized(activeLock) { activeNames += source.displayName }
        var prepared: com.musicconverter.miui.core.PreparedAudio? = null
        try {
            if (stopRequested) return WorkResult(source.displayName, false, error = "任务已停止")

            prepared = AudioFileManager.prepareInput(applicationContext, source.uri)
            val result = ConversionEngine(applicationContext).convert(prepared, target, ffmpegThreadsPerJob)
            if (!result.success) {
                return WorkResult(source.displayName, false, error = result.error)
            }

            if (replaceSource && result.outputUri != null) {
                val replace = AudioFileManager.replaceOriginal(
                    applicationContext,
                    source.uri,
                    source.displayName,
                    result.outputUri,
                    result.outputName,
                    prepared.localFile
                )
                return if (replace.success) {
                    WorkResult(source.displayName, true, replaced = true)
                } else {
                    WorkResult(source.displayName, true, replaceError = replace.message)
                }
            }

            return WorkResult(source.displayName, true)
        } catch (t: Throwable) {
            return WorkResult(source.displayName, false, error = t.message ?: t.javaClass.simpleName)
        } finally {
            prepared?.localFile?.delete()
            synchronized(activeLock) { activeNames.remove(source.displayName) }
        }
    }

    private fun finishJob(
        files: List<ScannedAudio>,
        success: Int,
        failed: Int,
        replaced: Int,
        replaceFailed: Int,
        replaceSource: Boolean,
        parallelism: Int,
        failedNames: List<String>,
        replaceFailedNames: List<String>,
        cancelled: Boolean,
        processed: Int = files.size
    ) {
        val summary = buildString {
            append(if (cancelled) "并行批量任务已停止" else "并行批量转换完成")
            append("\n并行任务：${parallelism} 路")
            append("\n已处理：$processed/${files.size}")
            append("\n成功：$success\n失败：$failed")
            if (replaceSource) {
                append("\n成功置换：$replaced\n置换失败：$replaceFailed")
                if (replaceFailed > 0) append("\n置换失败时会保留源文件和转换结果。")
            } else {
                append("\n输出：Music/MusicConverter\n源文件：保留")
            }
            if (failedNames.isNotEmpty()) {
                append("\n\n部分转换失败：\n")
                append(failedNames.joinToString("\n"))
                if (failed > failedNames.size) append("\n…以及其他 ${failed - failedNames.size} 个")
            }
            if (replaceFailedNames.isNotEmpty()) {
                append("\n\n部分置换失败：\n")
                append(replaceFailedNames.joinToString("\n"))
                if (replaceFailed > replaceFailedNames.size) append("\n…以及其他 ${replaceFailed - replaceFailedNames.size} 个")
            }
        }

        val finalState = BatchProgressSnapshot(
            running = false,
            current = processed,
            total = files.size,
            success = success,
            failed = failed,
            replaced = replaced,
            replaceFailed = replaceFailed,
            currentName = if (cancelled) "任务已停止" else "全部处理完成",
            replaceSource = replaceSource,
            parallelism = parallelism,
            active = 0,
            pausing = false,
            paused = false,
            done = true,
            cancelled = cancelled,
            message = summary
        )
        updateState(finalState)
        notificationManager.notify(NOTIFICATION_ID, buildCompletionNotification(finalState))
        broadcast(finalState)
    }

    private fun readJob(jobFile: File): List<ScannedAudio> {
        val root = JSONObject(jobFile.readText(Charsets.UTF_8))
        val array = root.getJSONArray("files")
        val result = ArrayList<ScannedAudio>(array.length())
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            result += ScannedAudio(
                uri = Uri.parse(item.getString("uri")),
                displayName = item.optString("name", "audio"),
                size = item.optLong("size", -1L),
                relativePath = item.optString("path", "")
            )
        }
        return result
    }

    private fun activeCount(): Int = synchronized(activeLock) { activeNames.size }

    private fun activeSummary(fallback: String): String = synchronized(activeLock) {
        if (activeNames.isEmpty()) fallback
        else activeNames.take(2).joinToString(" / ") + if (activeNames.size > 2) " 等 ${activeNames.size} 个" else ""
    }

    private fun updateProgress(
        current: Int,
        total: Int,
        success: Int,
        failed: Int,
        replaced: Int,
        replaceFailed: Int,
        currentName: String,
        replaceSource: Boolean,
        parallelism: Int,
        active: Int
    ) {
        val state = BatchProgressSnapshot(
            running = true,
            current = current,
            total = total,
            success = success,
            failed = failed,
            replaced = replaced,
            replaceFailed = replaceFailed,
            currentName = currentName,
            replaceSource = replaceSource,
            parallelism = parallelism,
            active = active,
            pausing = pauseRequested && !paused,
            paused = paused,
            done = false,
            cancelled = false,
            message = when {
                paused -> "批量任务已暂停"
                pauseRequested -> "等待当前 $active 个转换完成后暂停"
                else -> "并行转换 $current/$total · 活跃 $active/$parallelism"
            }
        )
        updateState(state)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state, indeterminate = total <= 0))
        broadcast(state)
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: BatchProgressSnapshot, indeterminate: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseOrResumeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BatchConversionService::class.java).setAction(
                if (state.paused || state.pausing) ACTION_RESUME else ACTION_PAUSE
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BatchConversionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            state.paused -> "${state.current}/${state.total} · 已暂停 · 剩余 ${state.total - state.current} 个"
            state.pausing -> "${state.current}/${state.total} · 等待 ${state.active} 个当前任务完成后暂停"
            state.total > 0 -> "${state.current}/${state.total} · ${state.parallelism} 路并行 · 活跃 ${state.active} · 成功 ${state.success} · 失败 ${state.failed} · ${state.currentName}"
            else -> state.currentName
        }
        val title = when {
            state.paused -> "MusicConverter 批量转换已暂停"
            state.pausing -> "MusicConverter 正在等待暂停"
            else -> "MusicConverter 并行转换中"
        }
        val controlLabel = when {
            state.paused -> "继续"
            state.pausing -> "取消暂停"
            else -> "暂停"
        }
        val controlIcon = if (state.paused || state.pausing) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(
                state.total.coerceAtLeast(1),
                state.current.coerceAtMost(state.total.coerceAtLeast(1)),
                indeterminate && !state.paused
            )
            .addAction(controlIcon, controlLabel, pauseOrResumeIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()
    }

    private fun buildCompletionNotification(state: BatchProgressSnapshot): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (state.cancelled) "并行批量转换已停止" else if (state.failed > 0) "并行批量转换完成（有失败）" else "并行批量转换完成"
        val text = "${state.parallelism} 路 · 成功 ${state.success} · 失败 ${state.failed}${if (state.replaceSource) " · 置换 ${state.replaced}" else ""}"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(state.message))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音频转换进度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示批量并行音频转换的当前任务、数量和完成进度"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun broadcast(state: BatchProgressSnapshot) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, state.running)
            putExtra(EXTRA_CURRENT, state.current)
            putExtra(EXTRA_TOTAL, state.total)
            putExtra(EXTRA_SUCCESS, state.success)
            putExtra(EXTRA_FAILED, state.failed)
            putExtra(EXTRA_REPLACED, state.replaced)
            putExtra(EXTRA_REPLACE_FAILED, state.replaceFailed)
            putExtra(EXTRA_CURRENT_NAME, state.currentName)
            putExtra(EXTRA_REPLACE_SOURCE, state.replaceSource)
            putExtra(EXTRA_PARALLELISM, state.parallelism)
            putExtra(EXTRA_ACTIVE, state.active)
            putExtra(EXTRA_PAUSING, state.pausing)
            putExtra(EXTRA_PAUSED, state.paused)
            putExtra(EXTRA_DONE, state.done)
            putExtra(EXTRA_CANCELLED, state.cancelled)
            putExtra(EXTRA_MESSAGE, state.message)
        })
    }

    companion object {
        const val ACTION_PROGRESS = "com.musicconverter.miui.BATCH_PROGRESS"
        private const val ACTION_PAUSE = "com.musicconverter.miui.BATCH_PAUSE"
        private const val ACTION_RESUME = "com.musicconverter.miui.BATCH_RESUME"
        private const val ACTION_STOP = "com.musicconverter.miui.BATCH_STOP"
        private const val EXTRA_JOB_FILE = "jobFile"
        private const val EXTRA_TARGET = "target"
        const val EXTRA_REPLACE_SOURCE = "replaceSource"
        const val EXTRA_PARALLELISM = "parallelism"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_PAUSING = "pausing"
        const val EXTRA_PAUSED = "paused"

        const val EXTRA_RUNNING = "running"
        const val EXTRA_CURRENT = "current"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_FAILED = "failed"
        const val EXTRA_REPLACED = "replaced"
        const val EXTRA_REPLACE_FAILED = "replaceFailed"
        const val EXTRA_CURRENT_NAME = "currentName"
        const val EXTRA_DONE = "done"
        const val EXTRA_CANCELLED = "cancelled"
        const val EXTRA_MESSAGE = "message"

        private const val CHANNEL_ID = "conversion_progress"
        private const val NOTIFICATION_ID = 4102
        private const val MAX_PARALLELISM = 4
        private val lock = Any()

        @Volatile
        private var currentState = BatchProgressSnapshot()

        fun snapshot(): BatchProgressSnapshot = currentState

        private fun updateState(state: BatchProgressSnapshot) {
            currentState = state
        }

        /** A conservative default because each FFmpeg session is itself multi-threaded. */
        fun recommendedParallelism(): Int {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            return when {
                cores >= 10 -> 3
                cores >= 4 -> 2
                else -> 1
            }.coerceAtMost(MAX_PARALLELISM)
        }

        fun start(
            context: Context,
            files: List<ScannedAudio>,
            target: AudioOutputFormat,
            replaceSource: Boolean,
            parallelism: Int = recommendedParallelism()
        ): Boolean {
            val convertibleFiles = files.filterNot {
                IgnoredFormatPreferences.shouldIgnore(context, it.displayName)
            }
            if (convertibleFiles.isEmpty() || currentState.running) return false
            return try {
                val array = JSONArray()
                convertibleFiles.forEach { source ->
                    array.put(JSONObject().apply {
                        put("uri", source.uri.toString())
                        put("name", source.displayName)
                        put("size", source.size)
                        put("path", source.relativePath)
                    })
                }
                val jobDir = File(context.cacheDir, "batch_jobs").apply { mkdirs() }
                val jobFile = File(jobDir, "batch_${System.currentTimeMillis()}.json")
                jobFile.writeText(JSONObject().put("files", array).toString(), Charsets.UTF_8)

                val intent = Intent(context, BatchConversionService::class.java).apply {
                    putExtra(EXTRA_JOB_FILE, jobFile.absolutePath)
                    putExtra(EXTRA_TARGET, target.name)
                    putExtra(EXTRA_REPLACE_SOURCE, replaceSource)
                    putExtra(EXTRA_PARALLELISM, parallelism.coerceIn(1, MAX_PARALLELISM))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun requestPause(context: Context): Boolean = sendControl(context, ACTION_PAUSE)

        fun requestResume(context: Context): Boolean = sendControl(context, ACTION_RESUME)

        fun requestStop(context: Context): Boolean = sendControl(context, ACTION_STOP)

        private fun sendControl(context: Context, action: String): Boolean {
            if (!currentState.running || currentState.done) return false
            return try {
                context.startService(Intent(context, BatchConversionService::class.java).setAction(action))
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

private data class WorkResult(
    val sourceName: String,
    val success: Boolean,
    val error: String = "",
    val replaced: Boolean = false,
    val replaceError: String = ""
)

data class BatchProgressSnapshot(
    val running: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val replaced: Int = 0,
    val replaceFailed: Int = 0,
    val currentName: String = "",
    val replaceSource: Boolean = false,
    val parallelism: Int = 1,
    val active: Int = 0,
    val pausing: Boolean = false,
    val paused: Boolean = false,
    val done: Boolean = false,
    val cancelled: Boolean = false,
    val message: String = ""
)
