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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

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

        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val EXTRA_CONFIG = "EXTRA_CONFIG"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_RUNNING = "isRunning"
        const val EXTRA_ACCUMULATED = "accumulated"
        const val EXTRA_LAP_COUNT = "EXTRA_LAP_COUNT"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }

    private data class TimerState(
        var id: String,
        var accumulated: Long,
        var isRunning: Boolean,
        var lapCount: Int,
        var startedAt: Long?,
        var title: String,
        var subtitle: String?,
        var mode: String,
        val runnable: Runnable
    )

    private val handler = Handler(Looper.getMainLooper())
    private val timerStates = mutableMapOf<Int, TimerState>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
//    private var bgColor: String? = "#1B4332"
    private var apiServiceForStopWatch: ApiServices? = null
    private var apiServiceForLapCount: ApiServices? = null

    override fun onCreate() {
        super.onCreate()
        // Don't create a single channel here anymore
        Log.d(TAG, "Service onCreate - channels will be created per notification")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1

        val fromNotification =
            intent?.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false) == true

        val config: LiveUpdateConfig? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_CONFIG, LiveUpdateConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_CONFIG)
            }
//        if (config?.backgroundColor != null) {
//            bgColor = config.backgroundColor
//        }
        if (apiServiceForStopWatch == null && config != null) {
            apiServiceForStopWatch = createApiServiceForStopWatch(config)
        }
        if (apiServiceForLapCount == null && config != null){
            apiServiceForLapCount = createApiServiceForLapCount(config)
        }

        when (intent?.action) {
            ACTION_START -> startTimer(intent, notificationId)
            ACTION_PAUSE -> pauseTimer(notificationId, fromNotification)
            ACTION_RESUME -> resumeTimer(notificationId, fromNotification)
            ACTION_STOP -> stopTimer(notificationId, fromNotification)
            ACTION_LAP -> addLap(notificationId, fromNotification)
            ACTION_RESTART -> restartTimer(notificationId, fromNotification)
        }

        return START_NOT_STICKY
    }

    // ✅ NEW: Get unique channel ID for each notification
    private fun getChannelIdForNotification(notificationId: Int): String {
        return "${CHANNEL_ID}_$notificationId"
    }

    // ✅ NEW: Create channel for specific notification
    private fun createChannelForNotification(notificationId: Int, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getChannelIdForNotification(notificationId)
            val channelName = "Timer: $title"

            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Stopwatch notification for $title"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)

            Log.d(TAG, "Created channel: $channelId for notification: $notificationId")
        }
    }

    // ✅ NEW: Delete channel when notification is removed
    private fun deleteChannelForNotification(notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getChannelIdForNotification(notificationId)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.deleteNotificationChannel(channelId)

            Log.d(TAG, "Deleted channel: $channelId for notification: $notificationId")
        }
    }

//    private fun safeBgColor(): Int {
//        return try {
//            (bgColor ?: "#1B4332").toColorInt()
//        } catch (e: Exception) {
//            "#1B4332".toColorInt()
//        }
//    }

    // ---------------- API ----------------

    private fun createApiServiceForStopWatch(config: LiveUpdateConfig): ApiServices {
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

        val baseUrl = config.apiEndpoint!!.stopwatchEndpoints!!.common + "/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiServices::class.java)
    }

    private fun createApiServiceForLapCount(config: LiveUpdateConfig): ApiServices {
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

        val baseUrl = config.apiEndpoint!!.stopwatchEndpoints!!.lap + "/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiServices::class.java)
    }

    private fun postStopWatchStatus(id: Int, status: String) {
        val api = apiServiceForStopWatch ?: return
        serviceScope.launch {
            try {
                api.postStopWatchStatus(id, status)
            } catch (e: Exception) {
                Log.e(TAG, "API Error", e)
            }
        }
    }

    private fun postLapCreation(name:String,duration:Int,id:Int){
        val api = apiServiceForLapCount ?: return
        val lapObject = LapObject(
            name = name,
            duration = duration,
            stopwatch = id
        )
        serviceScope.launch {
            try {
                api.postLapCreation(lapObject)
            } catch (e: Exception) {
                Log.e(TAG, "API Error", e)
            }
        }
    }

    // ---------------- TIMER LOGIC ----------------

    private fun startTimer(intent: Intent, notificationId: Int) {
        if (timerStates.containsKey(notificationId)) {
            resumeTimer(notificationId, false)
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Timer"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE)

        val accumulated = intent.getLongExtra(EXTRA_ACCUMULATED, 0L)
        Log.d("LVS", accumulated.toString())
        // ✅ Create unique channel for this notification
        createChannelForNotification(notificationId, title)

        val runnable = object : Runnable {
            override fun run() {
                timerStates[notificationId]?.let {
                    if (it.isRunning) {
                        it.accumulated++
                        updateNotification(notificationId)
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }

        timerStates[notificationId] = TimerState(
            id = notificationId.toString(),
            accumulated = accumulated,
            isRunning = true,
            lapCount = 0,
            startedAt = System.currentTimeMillis(),
            title = title,
            subtitle = subtitle,
            mode = "stopwatch",
            runnable = runnable
        )

        startForeground(notificationId, buildNotification(notificationId))
        handler.postDelayed(runnable, 1000)
    }

    private fun pauseTimer(id: Int, fromNotification: Boolean) {
        timerStates[id]?.apply {
            isRunning = false
            handler.removeCallbacks(runnable)
            updateNotification(id)
            if (fromNotification) {
                postStopWatchStatus(id, "stop")
                NotificationStateTriggredEventEmitter.emit(
                    id,
                    "pause",
                    "stopwatch"
                )
            }
        }
    }

    private fun resumeTimer(id: Int, fromNotification: Boolean) {
        timerStates[id]?.apply {
            if (!isRunning) {
                isRunning = true
                handler.postDelayed(runnable, 1000)
                updateNotification(id)
                if (fromNotification){
                    postStopWatchStatus(id, "start")
                    NotificationStateTriggredEventEmitter.emit(
                        id,
                        "start",
                        "stopwatch"
                    )
                }
            }
        }
    }

    private fun stopTimer(id: Int, fromNotification: Boolean) {
        timerStates[id]?.apply {
            isRunning = false
            handler.removeCallbacks(runnable)
            timerStates.remove(id)
            getSystemService(NotificationManager::class.java)?.cancel(id)

            // ✅ Delete the channel when notification is stopped
            deleteChannelForNotification(id)

            if (fromNotification) {
                postStopWatchStatus(id, "stop")
                NotificationStateTriggredEventEmitter.emit(
                    id,
                    "pause",
                    "stopwatch"
                )
            }
        }
        if (timerStates.isEmpty()) stopSelf()
    }

    private fun addLap(id: Int, fromNotification: Boolean) {
        timerStates[id]?.apply {
            lapCount++
            updateNotification(id)
            if (fromNotification){
                postLapCreation("Lap $lapCount",timerStates[id]?.accumulated!!.toInt(),id)
                NotificationStateTriggredEventEmitter.emit(
                    id,
                    "lap",
                    "stopwatch"
                )
            }
        }
    }

    private fun restartTimer(id: Int,fromNotification: Boolean) {
        timerStates[id]?.apply {
            accumulated = 0L
            lapCount = 0
            isRunning = false
            if (startedAt == null) {
                startedAt = System.currentTimeMillis()
            }

            handler.removeCallbacks(runnable)
            updateNotification(id)
            if (fromNotification) {
                postStopWatchStatus(id,"reset")
                NotificationStateTriggredEventEmitter.emit(
                    id,
                    "reset",
                    "stopwatch"
                )
            }
        }
    }

    // ---------------- UI ----------------

    private fun updateNotification(id: Int) {
        getSystemService(NotificationManager::class.java)
            ?.notify(id, buildNotification(id))
    }

    private fun buildNotification(id: Int): Notification {
        val state = timerStates[id] ?: return NotificationCompat.Builder(
            this,
            getChannelIdForNotification(id)  // ✅ Use unique channel
        ).build()

        val rv = RemoteViews(packageName, R.layout.notification_stopwatch_timer)

        rv.setTextViewText(R.id.tvTimer, formatTime(state.accumulated.toInt()))
        rv.setTextViewText(R.id.tvTitle, state.title)
        val isDark = isDarkMode()

        val textColor = if (isDark) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.BLACK
        }

        val stopWatchIcon = if (isDark) R.drawable.ic_stopwatch else R.drawable.ic_stopwatch_black
        val playIcon = if (isDark){
            if (state.isRunning) R.drawable.ic_pause else R.drawable.ic_play
        }else{
            if (state.isRunning) R.drawable.ic_pause_black else R.drawable.ic_play_black
        }
        val flagIcon = if (isDark) R.drawable.ic_flag else R.drawable.ic_flag_black
        val restartIcon = if (isDark) R.drawable.ic_rotate else R.drawable.ic_rotate_black

        rv.setImageViewResource(R.id.ivPlay, playIcon)
        rv.setImageViewResource(R.id.ivView, stopWatchIcon)
        rv.setImageViewResource(R.id.ivFlag, flagIcon)
        rv.setImageViewResource(R.id.ivRestart, restartIcon)
        rv.setTextColor(R.id.tvTitle,textColor)
        rv.setTextColor(R.id.tvLap,textColor)
        rv.setTextColor(R.id.tvTimer,textColor)

        rv.setViewVisibility(R.id.ivRestart, if (state.isRunning) View.GONE else View.VISIBLE)
        rv.setViewVisibility(R.id.ivFlag, if (state.isRunning) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.tvPause, if (state.isRunning) View.GONE else View.VISIBLE)

        if (!state.subtitle.isNullOrBlank()) {
            rv.setViewVisibility(R.id.tvSubtitle, View.VISIBLE)
            rv.setTextViewText(R.id.tvSubtitle, state.subtitle)
            rv.setTextColor(R.id.tvSubtitle, textColor)
        } else {
            rv.setViewVisibility(R.id.tvSubtitle, View.GONE)
        }

        rv.setOnClickPendingIntent(R.id.ivRestart, createActionIntent(id, ACTION_RESTART))
        rv.setOnClickPendingIntent(
            R.id.ivPlay,
            createActionIntent(id, if (state.isRunning) ACTION_PAUSE else ACTION_RESUME)
        )
        rv.setOnClickPendingIntent(R.id.ivFlag, createActionIntent(id, ACTION_LAP))

        if (state.lapCount > 0) {
            rv.setViewVisibility(R.id.tvLap, View.VISIBLE)
            rv.setTextViewText(R.id.tvLap, "Lap: ${state.lapCount}")
        } else {
            rv.setViewVisibility(R.id.tvLap, View.GONE)
            rv.setTextViewText(R.id.tvLap, "")
        }

        // ✅ Use unique channel ID for this notification
        return NotificationCompat.Builder(this, getChannelIdForNotification(id))
            .setSmallIcon(R.drawable.ic_stopwatch)
            .setCustomBigContentView(rv)
            .setCustomContentView(rv)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
            Intent(this, LiveStopWatchService::class.java).apply {
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

        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    // ✅ REMOVED: No longer needed as we create channels dynamically
    // private fun createChannel() { ... }

    override fun onBind(intent: Intent?): IBinder? = null
}
