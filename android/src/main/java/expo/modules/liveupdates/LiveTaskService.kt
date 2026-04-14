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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class LiveTaskService : Service() {

    companion object {
        const val TAG = "LiveTaskService"
        const val CHANNEL_ID = "live_task_channel"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISMISS = "ACTION_DISMISS"

        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_CONFIG = "EXTRA_CONFIG"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val EXTRA_START_DATE = "startDate"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }

    private data class TaskState(
        var id: String,
        var startDate: Long,
        var title: String,
        var subtitle: String?,
        val runnable: Runnable,
        var isDismissed: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val taskStates = mutableMapOf<Int, TaskState>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var apiServiceForTask: ApiServices? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate - channels will be created per notification")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1
        val fromNotification = intent?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true

        val config: LiveUpdateConfig? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_CONFIG, LiveUpdateConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_CONFIG)
            }

        if (apiServiceForTask == null && config != null) {
            apiServiceForTask = createApiServiceForTask(config)
        }

        when (intent?.action) {
            ACTION_START -> startTask(intent, notificationId)
            ACTION_STOP -> stopTask(notificationId, fromNotification)
            ACTION_DISMISS -> dismissTask(notificationId)
        }

        return START_NOT_STICKY
    }

    private fun getChannelIdForNotification(notificationId: Int): String {
        return "${CHANNEL_ID}_$notificationId"
    }

    private fun createChannelForNotification(notificationId: Int, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getChannelIdForNotification(notificationId)
            val channelName = "Task: $title"

            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Task notification for $title"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)

            Log.d(TAG, "Created channel: $channelId for notification: $notificationId")
        }
    }

    private fun deleteChannelForNotification(notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getChannelIdForNotification(notificationId)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.deleteNotificationChannel(channelId)

            Log.d(TAG, "Deleted channel: $channelId for notification: $notificationId")
        }
    }

    private fun startTask(intent: Intent, notificationId: Int) {
        if (taskStates.containsKey(notificationId)) {
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Task"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)
        val startDate = intent.getLongExtra(EXTRA_START_DATE, System.currentTimeMillis())

        createChannelForNotification(notificationId, title)

        val runnable = object : Runnable {
            override fun run() {
                taskStates[notificationId]?.let {
                    updateNotification(notificationId)
                    handler.postDelayed(this, 1000)
                }
            }
        }

        taskStates[notificationId] = TaskState(
            id = notificationId.toString(),
            startDate = startDate,
            title = title,
            subtitle = subtitle,
            runnable = runnable,
            isDismissed = false
        )

        startForeground(notificationId, buildNotification(notificationId))
        handler.postDelayed(runnable, 1000)
    }

    private fun stopTask(id: Int, fromNotification: Boolean) {
        taskStates[id]?.let {
            handler.removeCallbacks(it.runnable)
            taskStates.remove(id)
            
            if (taskStates.isEmpty()) {
                stopForeground(true)
            }

            getSystemService(NotificationManager::class.java)?.cancel(id)
            
            try {
                deleteChannelForNotification(id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete notification channel", e)
            }

            if (fromNotification) {
                postTaskStop(id)
                NotificationStateTriggredEventEmitter.emit(
                    id,
                    "stop",
                    "task"
                )
            }
        }
        if (taskStates.isEmpty()) stopSelf()
    }

    private fun dismissTask(id: Int) {
        taskStates[id]?.apply {
            isDismissed = true
        }
    }

    private fun updateNotification(id: Int) {
        val state = taskStates[id] ?: return
        if (state.isDismissed) return

        getSystemService(NotificationManager::class.java)
            ?.notify(id, buildNotification(id))
    }

    private fun buildNotification(id: Int): Notification {
        val state = taskStates[id] ?: return NotificationCompat.Builder(
            this,
            getChannelIdForNotification(id)
        ).build()

        val rv = RemoteViews(packageName, R.layout.notification_stopwatch_timer)

        val now = System.currentTimeMillis()
        val elapsedSeconds = ((now - state.startDate) / 1000).coerceAtLeast(0).toInt()

        rv.setTextViewText(R.id.tvTimer, formatTime(elapsedSeconds))
        rv.setTextViewText(R.id.tvTitle, state.title)
        val isDark = isDarkMode()

        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }

        val taskIcon = if (isDark) R.drawable.ic_checkmark_circle_fill else R.drawable.ic_checkmark_circle_fill_black // Fallback to timer icon for task
        val stopIcon =  if (isDark) R.drawable.ic_stop else R.drawable.ic_stop_black

        rv.setImageViewResource(R.id.ivView, taskIcon)
        rv.setImageViewResource(R.id.ivPlay, stopIcon) // Repurpose Play to Stop for simplicity
        
        rv.setViewVisibility(R.id.ivRestart, View.GONE)
        rv.setViewVisibility(R.id.ivFlag, View.GONE)
        rv.setViewVisibility(R.id.tvPause, View.GONE)
        rv.setViewVisibility(R.id.tvLap, View.GONE)
        
        rv.setTextColor(R.id.tvTitle, textColor)
        rv.setTextColor(R.id.tvTimer, textColor)

        if (!state.subtitle.isNullOrBlank()) {
            rv.setViewVisibility(R.id.tvSubtitle, View.VISIBLE)
            rv.setTextViewText(R.id.tvSubtitle, state.subtitle)
            rv.setTextColor(R.id.tvSubtitle, textColor)
        } else {
            rv.setViewVisibility(R.id.tvSubtitle, View.GONE)
        }

        // Only one action for task: Stop
        rv.setOnClickPendingIntent(R.id.ivPlay, createActionIntent(id, ACTION_STOP))

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, getChannelIdForNotification(id))
            .setSmallIcon(taskIcon)
            .setCustomBigContentView(rv)
            .setCustomContentView(rv)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(createActionIntent(id, ACTION_DISMISS))
            .setContentIntent(contentIntent)
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
            Intent(this, LiveTaskService::class.java).apply {
                this.action = action
                putExtra(EXTRA_NOTIFICATION_ID, id)
                putExtra(EXTRA_FROM_NOTIFICATION, true)
                putExtra("mode", "task")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun formatTime(sec: Int): String {
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        val seconds = sec % 60

        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun createApiServiceForTask(config: LiveUpdateConfig): ApiServices? {
        val endpoint = config.apiEndpoint?.taskEndpoints ?: return null
        
        val logging = HttpLoggingInterceptor { Log.d("API_HTTP", it) }
            .apply { level = HttpLoggingInterceptor.Level.BODY }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${config.accessToken}")
                        .addHeader("Content-Type", "application/json")
                        .build()
                )
            }
            .build()

        val baseUrl = if (endpoint.endsWith("/")) endpoint else "$endpoint/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiServices::class.java)
    }

    private fun postTaskStop(id: Int) {
        val api = apiServiceForTask ?: return
        serviceScope.launch {
            try {
                api.postTaskStop(id)
            } catch (e: Exception) {
                Log.e(TAG, "API Error: postTaskStop failed", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
