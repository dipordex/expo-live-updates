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
import androidx.core.widget.RemoteViewsCompat.setViewBackgroundColor

class LiveTimerService : Service() {

    companion object {
        const val TAG = "LiveTimerService"
        const val CHANNEL_ID = "live_timer_channel"

        const val ACTION_START = "ACTION_START"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESUME = "ACTION_RESUME"

        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_CONFIG = "EXTRA_CONFIG"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val EXTRA_DURATION = "EXTRA_DURATION"
        const val EXTRA_REMAINING = "EXTRA_REMAINING"

        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }

    private data class TimerState(
        var id: String,
        var duration: Double,
        var isRunning: Boolean,
        var remaining: Double,
        var title: String,
        var mode: String,
        val runnable: Runnable
    )

    private val handler = Handler(Looper.getMainLooper())
    private val timerStates = mutableMapOf<Int, TimerState>()
    private var bgColor: String? = "#1B4332"

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
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "========================================")
        Log.d(TAG, "onStartCommand CALLED")
        Log.d(TAG, "Action: ${intent?.action}")
        Log.d(TAG, "========================================")

        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1
        Log.d(TAG, "Notification ID: $notificationId")

        if (notificationId == -1) {
            Log.e(TAG, "Invalid notification ID!")
            return START_NOT_STICKY
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

        if (config?.backgroundColor != null) {
            bgColor = config.backgroundColor
            Log.d(TAG, "Background color set to: $bgColor")
        }

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
            else -> {
                Log.e(TAG, "Unknown action: ${intent?.action}")
            }
        }

        return START_NOT_STICKY
    }


    private fun safeBgColor(): Int {
        return try {
            (bgColor ?: "#1B4332").toColorInt()
        } catch (e: Exception) {
            "#1B4332".toColorInt()
        }
    }

    private fun startTimer(intent: Intent, notificationId: Int) {
        Log.d(TAG, "startTimer called for ID: $notificationId")

        if (timerStates.containsKey(notificationId)) {
            Log.d(TAG, "Timer already exists, resuming")
            resumeTimer(notificationId, false)
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Timer"
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
                timerStates[notificationId]?.let {
                    if (it.isRunning && it.remaining > 0) {
                        it.remaining--
                        updateNotification(notificationId)
                        handler.postDelayed(this, 1000)
                    } else if (it.remaining <= 0) {
                        Log.d(TAG, "Timer $notificationId finished")
                        it.isRunning = false
                        handler.removeCallbacks(this)
                        stopTimer(notificationId, false)
                    }
                }
            }
        }

        timerStates[notificationId] = TimerState(
            id = notificationId.toString(),
            duration = duration,
            isRunning = isRunning,
            remaining = remaining,
            title = title,
            mode = "timer",
            runnable = runnable
        )

        Log.d(TAG, "Starting foreground with notification")
        try {
            startForeground(notificationId, buildNotification(notificationId))
            Log.d(TAG, "✅ Foreground started successfully")
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
        timerStates[id]?.apply {
            isRunning = false
            handler.removeCallbacks(runnable)
            updateNotification(id)
            if (fromNotification) {
                NotificationStateTriggredEventEmitter.emit(
                    id,
                    "pause",
                    "timer"
                )
            }
        }
    }

    private fun stopTimer(id: Int, fromNotification: Boolean) {
        timerStates[id]?.apply {
            isRunning = false
            handler.removeCallbacks(runnable)
            timerStates.remove(id)
            getSystemService(NotificationManager::class.java)?.cancel(id)
            if (fromNotification) {
                NotificationStateTriggredEventEmitter.emit(
                    id,
                    "stop",
                    "timer"
                )
            }
        }
        if (timerStates.isEmpty()) stopSelf()
    }

    private fun updateNotification(id: Int) {
        getSystemService(NotificationManager::class.java)
            ?.notify(id, buildNotification(id))
    }

    private fun buildNotification(id: Int): Notification {
        val state = timerStates[id] ?: return NotificationCompat.Builder(this,
            CHANNEL_ID
        ).build()

        val rv = RemoteViews(packageName, R.layout.notification_timer)
        rv.setTextViewText(R.id.tvTimer, formatTime(state.remaining.toInt()))
        rv.setTextViewText(R.id.tvTitle, timerStates[id]?.title)
        rv.setViewBackgroundColor(
            R.id.llMain,
            safeBgColor()
        )
        rv.setImageViewResource(
            R.id.ivPlay1,
            if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play
        )
        rv.setOnClickPendingIntent(
            R.id.ivPlay1,
            createActionIntent(id, if (state.isRunning) ACTION_PAUSE else ACTION_RESUME)
        )
        rv.setViewVisibility(
            R.id.tvPause,
            if (state.isRunning) View.GONE else View.VISIBLE
        )
        rv.setOnClickPendingIntent(
            R.id.ivStop,
            createActionIntent(id, ACTION_STOP)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setColor(safeBgColor())
            .setColorized(true)
            .build()
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
        timerStates[id]?.apply {
            if (!isRunning) {
                isRunning = true
                handler.postDelayed(runnable, 1000)
                updateNotification(id)
                if (fromNotification){
                    NotificationStateTriggredEventEmitter.emit(
                        id,
                        "resume",
                        "timer"
                    )
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}