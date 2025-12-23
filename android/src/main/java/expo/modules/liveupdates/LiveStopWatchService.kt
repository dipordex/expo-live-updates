package expo.modules.liveupdates

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt

class LiveStopWatchService : Service() {

    companion object {
        const val TAG = "LiveStopWatchService"
        const val CHANNEL_ID = "live_timer_channel"

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_LAP = "ACTION_LAP"
        const val ACTION_RESTART = "ACTION_RESTART"

        // Extras for intent
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val EXTRA_ACCUMULATED = "accumulated"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_LAP_COUNT = "lapCount"
    }

    // Store multiple timer states
    private data class TimerState(
        var seconds: Double = 0.0,
        var isRunning: Boolean = false,
        var lapCount: Int = 0,
        var mode: String = "stopwatch",
        var title: String = "Timer",
        var accumulated: Double = 0.0,
        var duration: Double = 0.0,
        val runnable: Runnable
    )

    private val handler = Handler(Looper.getMainLooper())
    private val timerStates = mutableMapOf<Int, TimerState>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1

        Log.d(TAG, "")
        Log.d(TAG, "============================================")
        Log.d(TAG, "onStartCommand called")
        Log.d(TAG, "Action: ${intent?.action}")
        Log.d(TAG, "Notification ID: $notificationId")
        Log.d(TAG, "Active timers: ${timerStates.size}")
        Log.d(TAG, "============================================")

        if (notificationId == -1 && intent?.action == ACTION_START) {
            Log.e(TAG, "ERROR: No notification ID provided for ACTION_START")
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> startTimer(intent, notificationId)
            ACTION_PAUSE -> pauseTimer(notificationId)
            ACTION_RESUME -> resumeTimer(notificationId)
            ACTION_STOP -> stopTimer(notificationId)
            ACTION_LAP -> addLap(notificationId)
            ACTION_RESTART -> restartTimer(notificationId)
        }

        return START_NOT_STICKY
    }

    private fun startTimer(intent: Intent, notificationId: Int) {
        Log.d(TAG, "startTimer called for ID: $notificationId")

        // Check if timer already exists
        if (timerStates.containsKey(notificationId)) {
            Log.w(TAG, "Timer $notificationId already exists, updating instead")
            resumeTimer(notificationId)
            return
        }

        // Extract data from intent
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "stopwatch"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Timer"
        val isRunning = intent.getBooleanExtra(EXTRA_IS_RUNNING, true)
        val accumulated = intent.getDoubleExtra(EXTRA_ACCUMULATED, 0.0)
        val duration = intent.getDoubleExtra(EXTRA_DURATION, 0.0)
        val lapCount = intent.getIntExtra(EXTRA_LAP_COUNT, 0)

        Log.d(TAG, "Creating new timer:")
        Log.d(TAG, "  mode: $mode, title: $title")
        Log.d(TAG, "  isRunning: $isRunning, accumulated: $accumulated")

        // Create runnable for this specific timer
        val timerRunnable = object : Runnable {
            override fun run() {
                timerStates[notificationId]?.let { state ->
                    if (state.isRunning) {
                        when (state.mode) {
                            "stopwatch" -> state.seconds++
                            "timer" -> {
                                if (state.seconds > 0) {
                                    state.seconds--
                                } else {
                                    state.isRunning = false
                                    Log.d(TAG, "Timer $notificationId finished!")
                                }
                            }
                        }
                        updateNotification(notificationId)
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }

        // Create and store timer state
        val timerState = TimerState(
            seconds = if (mode == "timer") duration else accumulated,
            isRunning = isRunning,
            lapCount = lapCount,
            mode = mode,
            title = title,
            accumulated = accumulated,
            duration = duration,
            runnable = timerRunnable
        )

        timerStates[notificationId] = timerState
        Log.d(TAG, "✅ Timer state created for ID: $notificationId")

        // Start foreground with this notification
        if (timerStates.size == 1) {
            // First timer - start foreground
            startForeground(notificationId, buildNotification(notificationId))
            Log.d(TAG, "✅ Started as foreground service")
        } else {
            // Additional timer - just show notification
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(notificationId, buildNotification(notificationId))
            Log.d(TAG, "✅ Added notification (service already foreground)")
        }

        // Start the runnable if timer is running
        if (isRunning) {
            handler.post(timerRunnable)
            Log.d(TAG, "✅ Timer runnable started")
        }

        NotificationStateEventEmitter.emit(notificationId, NotificationAction.STARTED)
    }

    private fun pauseTimer(notificationId: Int) {
        Log.d(TAG, "pauseTimer called for ID: $notificationId")

        timerStates[notificationId]?.let { state ->
            state.isRunning = false
            state.accumulated = state.seconds
            updateNotification(notificationId)
            NotificationStateEventEmitter.emit(notificationId, NotificationAction.UPDATED)
            Log.d(TAG, "✅ Timer $notificationId paused")
        } ?: Log.w(TAG, "Timer $notificationId not found")
    }

    private fun resumeTimer(notificationId: Int) {
        Log.d(TAG, "resumeTimer called for ID: $notificationId")

        timerStates[notificationId]?.let { state ->
            if (state.isRunning) {
                Log.d(TAG, "Timer $notificationId already running")
                return
            }

            state.isRunning = true
            handler.post(state.runnable)
            updateNotification(notificationId)
            NotificationStateEventEmitter.emit(notificationId, NotificationAction.UPDATED)
            Log.d(TAG, "✅ Timer $notificationId resumed")
        } ?: Log.w(TAG, "Timer $notificationId not found")
    }

    private fun stopTimer(notificationId: Int) {
        Log.d(TAG, "stopTimer called for ID: $notificationId")

        timerStates[notificationId]?.let { state ->
            state.isRunning = false
            handler.removeCallbacks(state.runnable)
            timerStates.remove(notificationId)

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.cancel(notificationId)

            NotificationStateEventEmitter.emit(notificationId, NotificationAction.STOPPED)
            Log.d(TAG, "✅ Timer $notificationId stopped and removed")

            // If no more timers, stop service
            if (timerStates.isEmpty()) {
                Log.d(TAG, "No more timers, stopping service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                Log.d(TAG, "Still ${timerStates.size} timer(s) running")
            }
        } ?: Log.w(TAG, "Timer $notificationId not found")
    }

    private fun addLap(notificationId: Int) {
        Log.d(TAG, "addLap called for ID: $notificationId")

        timerStates[notificationId]?.let { state ->
            state.lapCount++
            updateNotification(notificationId)
            NotificationStateEventEmitter.emit(notificationId, NotificationAction.UPDATED)
            Log.d(TAG, "✅ Timer $notificationId lap count: ${state.lapCount}")
        } ?: Log.w(TAG, "Timer $notificationId not found")
    }

    private fun restartTimer(notificationId: Int) {
        Log.d(TAG, "restartTimer called for ID: $notificationId")

        timerStates[notificationId]?.let { state ->
            if (state.isRunning) {
                Log.d(TAG, "Timer $notificationId already running")
                return
            }

            state.isRunning = true
            state.seconds = if (state.mode == "timer") state.duration else 0.0
            state.lapCount = 0
            state.accumulated = 0.0
            handler.post(state.runnable)
            updateNotification(notificationId)
            NotificationStateEventEmitter.emit(notificationId, NotificationAction.UPDATED)
            Log.d(TAG, "✅ Timer $notificationId restarted")
        } ?: Log.w(TAG, "Timer $notificationId not found")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed - cleaning up ${timerStates.size} timer(s)")

        timerStates.values.forEach { state ->
            handler.removeCallbacks(state.runnable)
        }
        timerStates.clear()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(notificationId: Int) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(notificationId, buildNotification(notificationId))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification $notificationId", e)
        }
    }

    private fun buildNotification(notificationId: Int): Notification {
        val state = timerStates[notificationId] ?: run {
            Log.e(TAG, "Cannot build notification - timer $notificationId not found")
            return buildEmptyNotification()
        }

        val remoteView = RemoteViews(packageName, R.layout.notification_stopwatch_timer)

        remoteView.setTextViewText(R.id.tvTimer, formatTime(state.seconds.toInt()))
        remoteView.setImageViewResource(
            R.id.ivPlay,
            if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play
        )
        remoteView.setViewVisibility(R.id.ivRestart, if (state.isRunning) View.GONE else View.VISIBLE)
        remoteView.setViewVisibility(R.id.ivFlag, if (state.isRunning) View.VISIBLE else View.GONE)
        remoteView.setViewVisibility(R.id.tvPause, if (state.isRunning) View.GONE else View.VISIBLE)

        if (state.lapCount > 0) {
            remoteView.setViewVisibility(R.id.tvLap, View.VISIBLE)
            remoteView.setTextViewText(R.id.tvLap, "Lap: ${state.lapCount}")
        } else {
            remoteView.setViewVisibility(R.id.tvLap, View.GONE)
        }

        // Create intents with notification ID
        val pauseResumeIntent = createActionIntent(
            notificationId,
            if (state.isRunning) ACTION_PAUSE else ACTION_RESUME
        )
        val restartIntent = createActionIntent(notificationId, ACTION_RESTART)
        val lapIntent = createActionIntent(notificationId, ACTION_LAP)

        remoteView.setOnClickPendingIntent(R.id.ivPlay, pauseResumeIntent)
        remoteView.setOnClickPendingIntent(R.id.ivFlag, lapIntent)
        remoteView.setOnClickPendingIntent(R.id.ivRestart, restartIntent)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(state.title)
            .setSmallIcon(R.drawable.ic_stopwatch)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteView)
            .setCustomBigContentView(remoteView)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setColorized(true)
            .setColor("#1B4332".toColorInt())
            .build()
    }

    private fun createActionIntent(notificationId: Int, action: String): PendingIntent {
        val intent = Intent(this, LiveStopWatchService::class.java).apply {
            this.action = action
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        // Use unique request code for each notification+action combination
        val requestCode = notificationId * 1000 + action.hashCode()
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Timer")
            .setSmallIcon(R.drawable.ic_stopwatch)
            .build()
    }

    private fun formatTime(seconds: Int): String {
        val hours = seconds / 3600
        val min = (seconds % 3600) / 60
        val sec = seconds % 60

        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, min, sec)
        } else {
            "%02d:%02d".format(min, sec)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Timer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live stopwatch and timer notifications"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)

            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }
}