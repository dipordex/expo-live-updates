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

class LiveTimerService : Service() {

    companion object {
        const val TAG = "LiveTimerService"
        const val CHANNEL_ID = "live_timer_channel"

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_DISMISS = "ACTION_DISMISS"

        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_CONFIG = "EXTRA_CONFIG"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val EXTRA_DURATION = "EXTRA_DURATION"
        const val EXTRA_REMAINING = "EXTRA_REMAINING"
        const val EXTRA_SUBTITLE = "subtitle"

        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }

    data class TimerState(
        var id: String,
        var duration: Double,
        var isRunning: Boolean,
        var remaining: Double,
        var title: String,
        var subtitle: String?,
        var mode: String,
        val runnable: Runnable,
        var isDismissed: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val timerStates:MutableMap<Int, TimerState> = mutableMapOf<Int, TimerState>()
    private var currentForegroundId: Int? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "========================================")
        Log.d(TAG, "LiveTimerService onCreate() CALLED")
        Log.d(TAG, "========================================")
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Timer",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "========================================")
        Log.d(TAG, "onStartCommand CALLED")
        Log.d(TAG, "Action: ${intent?.action}")
        Log.d(TAG, "Active timers: ${timerStates.size}")
        Log.d(TAG, "========================================")

        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1
        Log.d(TAG, "Notification ID: $notificationId")

        if (notificationId == -1) {
            Log.e(TAG, "Invalid notification ID!")
            return START_STICKY
        }

        val fromNotification = intent?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true
        Log.d(TAG, "From notification: $fromNotification")

        val config: LiveUpdateConfig? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_CONFIG, LiveUpdateConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_CONFIG)
        }

        Log.d(TAG, "Config: $config")

        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Handling ACTION_START")
                startTimer(intent, notificationId)
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "Handling ACTION_PAUSE")
                pauseTimer(notificationId, fromNotification)
            }
            ACTION_STOP -> {
                Log.d(TAG, "Handling ACTION_STOP")
                stopTimer(notificationId, fromNotification)
            }
            ACTION_RESUME -> {
                Log.d(TAG, "Handling ACTION_RESUME")
                resumeTimer(notificationId, fromNotification)
            }
            ACTION_DISMISS -> {
                Log.d(TAG, "Handling ACTION_DISMISS")
                dismissTimer(notificationId)
            }
            else -> {
                Log.e(TAG, "Unknown action: ${intent?.action}")
            }
        }

        // Changed from START_NOT_STICKY to START_STICKY
        // This ensures the service persists even when paused
        return START_NOT_STICKY
    }

    private fun startTimer(intent: Intent, notificationId: Int) {
        Log.d(TAG, "startTimer called for ID: $notificationId")

        if (timerStates.containsKey(notificationId)) {
            Log.d(TAG, "Timer already exists, resuming")
            resumeTimer(notificationId, false)
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Timer"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        val duration = intent.getDoubleExtra(EXTRA_DURATION, 0.00)
        val remaining = intent.getDoubleExtra(EXTRA_REMAINING, duration)
        val isRunning = intent.getBooleanExtra(EXTRA_IS_RUNNING, false)

        Log.d(TAG, "Timer config:")
        Log.d(TAG, "  title: $title")
        Log.d(TAG, "  duration: $duration")
        Log.d(TAG, "  remaining: $remaining")
        Log.d(TAG, "  isRunning: $isRunning")

        if (duration <= 0) {
            Log.e(TAG, "Invalid duration: $duration")
            return
        }

        val runnable = object : Runnable {
            override fun run() {
                val state = timerStates[notificationId] ?: return
                if (!state.isRunning) return

                if (state.remaining > 0) {
                    state.remaining--
                    updateNotification(notificationId)
                    handler.postDelayed(this, 1000)

                    if (state.remaining == 0.0) {
                        state.isRunning = false
                        updateNotification(notificationId)
                    }
                } else {
                    state.remaining = 0.0
                    state.isRunning = false
                    updateNotification(notificationId)
                }
            }
        }

        timerStates[notificationId] = TimerState(
            id = notificationId.toString(),
            duration = duration,
            isRunning = isRunning,
            remaining = remaining,
            title = title,
            subtitle = subtitle,
            mode = "timer",
            runnable = runnable,
            isDismissed = false
        )

        Log.d(TAG, "Starting foreground with notification ${timerStates}")
        try {
            startForeground(notificationId, buildNotification(notificationId))
            currentForegroundId = notificationId
            Log.d(TAG, "✅ Foreground started successfully with ID: $notificationId")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start foreground", e)
            e.printStackTrace()
        }

        if (isRunning) {
            Log.d(TAG, "Starting timer countdown")
            handler.postDelayed(runnable, 1000)
        } else {
            Log.d(TAG, "Timer paused, not starting countdown")
        }
    }

    private fun pauseTimer(id: Int, fromNotification: Boolean) {
        Log.d(TAG, "pauseTimer called for ID: $id, fromNotification: $fromNotification")

        val state = timerStates[id]
        if (state == null) {
            Log.e(TAG, "Timer state not found for ID: ${timerStates[id]}")
            Log.e(TAG, "Available timer IDs: ${timerStates.keys}")
            return
        }

        Log.d(TAG, "Timer state before pause - isRunning: ${state.isRunning}, remaining: ${state.remaining}")

        state.isRunning = false
        state.isDismissed = false
        handler.removeCallbacks(state.runnable)

        // CRITICAL: Update notification while maintaining foreground state
        updateNotification(id)

        Log.d(TAG, "Notification updated after pause")
        Log.d(TAG, "Timer states count after pause: ${timerStates.size}")

        if (fromNotification) {
            NotificationStateTriggredEventEmitter.emit(id, "pause", "timer")
        }
    }

    private fun stopTimer(id: Int, fromNotification: Boolean) {
        Log.d(TAG, "stopTimer called for ID: $id, fromNotification: $fromNotification")

        val state = timerStates[id]
        if (state == null) {
            Log.e(TAG, "Timer state not found for ID: $id")
            return
        }

        state.isRunning = false
        handler.removeCallbacks(state.runnable)

        if (state.remaining > 0.0 || fromNotification) {
            timerStates.remove(id)
            Log.d(TAG, "Timer state removed for ID: $id")

            if (state.remaining != 0.0) {
                // Cancel the notification
                getSystemService(NotificationManager::class.java)?.cancel(id)
                Log.d(TAG, "Notification cancelled for ID: $id")
            }

            if (fromNotification) {
                NotificationStateTriggredEventEmitter.emit(id, "stop", "timer")
            }
        }

        // If this was the foreground notification, update or stop foreground
        if (currentForegroundId == id) {
            if (timerStates.isNotEmpty()) {
                // Promote another timer to foreground
                val nextId = timerStates.keys.first()
                currentForegroundId = nextId
                startForeground(nextId, buildNotification(nextId))
                Log.d(TAG, "Promoted timer $nextId to foreground")
            } else {
                // No more timers, stop foreground
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                currentForegroundId = null
                Log.d(TAG, "Stopped foreground service")
            }
        }

        if (timerStates.isEmpty()) {
            Log.d(TAG, "No more timers, stopping service")
            stopSelf()
        }
    }

    private fun dismissTimer(id: Int) {
        timerStates[id]?.apply {
            isDismissed = true
        }
        Log.d(TAG, "Timer $id dismissed")
    }

    private fun updateNotification(id: Int) {
        Log.d(TAG, "updateNotification called for ID: $id")

        val state = timerStates[id] ?: return
        if (state.isDismissed) {
            Log.d(TAG, "Timer $id is dismissed, not updating notification")
            return
        }

        val notification = buildNotification(id)

        // If this is the foreground notification, update foreground state
        if (currentForegroundId == id) {
            try {
                startForeground(id, notification)
                Log.d(TAG, "✅ Foreground notification updated for ID: $id")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update foreground notification", e)
            }
        } else {
            // Regular notification update
            getSystemService(NotificationManager::class.java)?.notify(id, notification)
            Log.d(TAG, "Regular notification updated for ID: $id")
        }
    }

    private fun buildNotification(id: Int): Notification {
        val state = timerStates[id]

        if (state == null) {
            Log.e(TAG, "buildNotification: Timer state not found for ID: $id")
            return NotificationCompat.Builder(this, CHANNEL_ID).build()
        }

        val rv = RemoteViews(packageName, R.layout.notification_timer)
        rv.setTextViewText(R.id.tvTimer, formatTime(state.remaining.toInt()))
        rv.setTextViewText(R.id.tvTitle, state.title)
        val isDark = isDarkMode()

        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }

        val timerIcon = if (isDark) R.drawable.ic_timer else R.drawable.ic_timer_black
        val playIcon = if (isDark){
            if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play
        }else{
            if (state.isRunning) R.drawable.ic_pause_black else R.drawable.ic_play_black
        }
        val stopIcon = if (isDark) R.drawable.ic_stop else R.drawable.ic_stop_black

        rv.setImageViewResource( R.id.ivView,timerIcon )
        rv.setImageViewResource(  R.id.ivPlay1,playIcon)
        rv.setImageViewResource(  R.id.ivStop,stopIcon)
        rv.setTextColor(R.id.tvTitle,textColor)
        rv.setTextColor(R.id.tvTimer,textColor)

        if (!state.subtitle.isNullOrBlank()) {
            rv.setViewVisibility(R.id.tvSubtitle, View.VISIBLE)
            rv.setTextViewText(R.id.tvSubtitle, state.subtitle)
            rv.setTextColor(R.id.tvSubtitle, textColor)
        } else {
            rv.setViewVisibility(R.id.tvSubtitle, View.GONE)
        }

        rv.setOnClickPendingIntent(
            R.id.ivPlay1,
            createActionIntent(id, if (state.isRunning) ACTION_PAUSE else ACTION_RESUME)
        )

        rv.setViewVisibility(
            R.id.tvPause,
            if (state.isRunning && state.remaining != 0.0) View.GONE else View.VISIBLE
        )

        rv.setViewVisibility(
            R.id.ivPlay1,
            if (state.remaining == 0.0) View.GONE else View.VISIBLE
        )

        rv.setOnClickPendingIntent(
            R.id.ivStop,
            createActionIntent(id, ACTION_STOP)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setCustomBigContentView(rv)
            .setCustomContentView(rv)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setDeleteIntent(createActionIntent(id, ACTION_DISMISS))
            .build()
    }

    private fun isDarkMode(): Boolean {
        val nightModeFlags =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }


    private fun createActionIntent(id: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            id + action.hashCode(),
            Intent(this, LiveTimerService::class.java).apply {
                this.action = action
                putExtra(EXTRA_NOTIFICATION_ID, id)
                putExtra(EXTRA_FROM_NOTIFICATION, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun formatTime(sec: Int): String {
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        val seconds = sec % 60

        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun resumeTimer(id: Int, fromNotification: Boolean) {
        Log.d(TAG, "resumeTimer called for ID: $id, fromNotification: $fromNotification")

        val state = timerStates[id]
        if (state == null) {
            Log.e(TAG, "Timer state not found for ID: $id")
            return
        }

        state.isDismissed = false

        if (!state.isRunning) {
            state.isRunning = true
            handler.postDelayed(state.runnable, 1000)
            updateNotification(id)
            Log.d(TAG, "Timer resumed for ID: $id")

            if (fromNotification) {
                NotificationStateTriggredEventEmitter.emit(id, "resume", "timer")
            }
        } else {
            Log.d(TAG, "Timer already running for ID: $id")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "========================================")
        Log.d(TAG, "LiveTimerService onDestroy() CALLED")
        Log.d(TAG, "========================================")

        // Clean up all handlers
        timerStates.values.forEach { state ->
            handler.removeCallbacks(state.runnable)
        }
        timerStates.clear()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}