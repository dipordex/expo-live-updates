package expo.modules.liveupdates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

const val MODULE_TAG = "ExpoLiveUpdatesModule"

class ExpoLiveUpdatesModule : Module() {
    private lateinit var liveUpdatesManager: LiveUpdatesManager
    private lateinit var modeClass: Class<out Service>

    override fun definition() = ModuleDefinition {
        Name("ExpoLiveUpdatesModule")

        Events(
            LiveUpdatesModuleEvents.ON_NOTIFICATION_STATE_CHANGE,
            LiveUpdatesModuleEvents.ON_TOKEN_CHANGE,
            LiveUpdatesModuleEvents.ON_BUTTON_PRESSED
        )

        OnCreate {
            Log.d(MODULE_TAG, "===== OnCreate called =====")
            initializeModule()
        }

        // FIXED startLiveUpdate function
        Function("startLiveUpdate") { state: LiveUpdateState, config: LiveUpdateConfig? ->
            Log.d(MODULE_TAG, "")
            Log.d(MODULE_TAG, "========================================")
            Log.d(MODULE_TAG, "startLiveUpdate CALLED")
            Log.d(MODULE_TAG, "Mode: ${state.mode}")
            Log.d(MODULE_TAG, "========================================")

            if (!context.checkPostNotificationPermission()) {
                Log.e(MODULE_TAG, "POST_NOTIFICATIONS permission NOT granted!")
                throw CodedException(
                    "${Manifest.permission.POST_NOTIFICATIONS} permission is not granted."
                )
            }
            Log.d(MODULE_TAG, "Permission check passed ✓")
            Log.d(MODULE_TAG, "config:${config}")
            modeClass = when (state.mode) {
                "stopwatch" -> LiveStopWatchService::class.java
                "timer" -> LiveTimerService::class.java
                "task" -> LiveTaskService::class.java
                "tapin" -> LiveTapInService::class.java
                else -> throw CodedException("Invalid mode: ${state.mode}")
            }
            when (state.mode) {
                "stopwatch", "timer", "task", "tapin" -> {
                    Log.d(MODULE_TAG, "Mode is ${state.mode} - starting service")

                    try {
                        val modeId = when (state.mode) {
                            "stopwatch" -> state.stopwatch?.id
                            "timer" -> state.timer?.id
                            "task" -> state.task?.id
                            "tapin" -> state.tapIn?.id
                            else -> null
                        }

                        val notificationId = modeId?.toIntOrNull() ?: run {
                            Log.e(MODULE_TAG, "Invalid or missing ${state.mode} ID")
                            throw CodedException("${state.mode} ID must be a valid integer")
                        }
                        Log.d(MODULE_TAG, "Using ${state.mode} ID as notification ID: $notificationId")

                        config?.let {
                            startTimerService(context, state, notificationId, config)
                        } ?: run {
                            Log.e(MODULE_TAG, "Config is null!")
                            throw CodedException("Config cannot be null")
                        }

                        Log.d(MODULE_TAG, "Service start completed!")
                        Log.d(MODULE_TAG, "Returning notification ID: $notificationId")

                        notificationId
                    } catch (e: Exception) {
                        Log.e(MODULE_TAG, "FATAL: Failed to start service!", e)
                        e.printStackTrace()
                        throw e
                    }
                }
                else -> {
                    Log.e(MODULE_TAG, "Invalid mode: ${state.mode}")
                    throw CodedException("Invalid mode: ${state.mode}")
                }
            }
        }

        Function("stopLiveUpdate") { notificationId: Int ->
            Log.d(MODULE_TAG, "")
            Log.d(MODULE_TAG, "stopLiveUpdate called for notification: $notificationId")
            stopTimerService(context, notificationId)
        }

        Function("updateLiveUpdate") {
                notificationId: Int,
                state: LiveUpdateState,
                config: LiveUpdateConfig? ->
            if (!context.checkPostNotificationPermission()) {
                throw CodedException(
                    "${Manifest.permission.POST_NOTIFICATIONS} permission is not granted."
                )
            }

            Log.d(MODULE_TAG, "")
            Log.d(MODULE_TAG, "========================================")
            Log.d(MODULE_TAG, "updateLiveUpdate called")
            Log.d(MODULE_TAG, "Notification ID: $notificationId")
            Log.d(MODULE_TAG, "Mode: ${state.mode}")
            Log.d(MODULE_TAG, "========================================")

            // Update for stopwatch/timer modes via service
            when (state.mode) {
                "stopwatch" -> {
                    Log.d(MODULE_TAG, "Updating timer via service")

                    state.stopwatch?.let { stopwatch ->
                        Log.d(MODULE_TAG, "Stopwatch update:")
                        Log.d(MODULE_TAG, "  isRunning: ${stopwatch.isRunning}")
                        Log.d(MODULE_TAG, "  accumulated: ${stopwatch.accumulated}")
                        Log.d(MODULE_TAG, "  lapCount: ${stopwatch.lapCount}")

                        // Update with full state including accumulated
                        updateTimerState(context, notificationId, stopwatch)
                    }
                }
                "task" -> {
                    Log.d(MODULE_TAG, "Task update handled separately or simply ignored")
                }
                "tapin" -> {
                    Log.d(MODULE_TAG, "TapIn update handled separately or simply ignored")
                }
                else -> {
                    state.timer?.let { timer ->
                        Log.d(MODULE_TAG, "timer update:")
                        Log.d(MODULE_TAG, "Timer update - isRunning: ${timer.isRunning}")
                        Log.d(MODULE_TAG, "  duration: ${timer.duration}")
                        Log.d(MODULE_TAG, "  remining: ${timer.remaining}")
                        updateTimerStateSimple(context, notificationId, timer)
                    }
                }
            }
        }

        // Add function to get current timer state (for syncing back to React Native)
        Function("getTimerState") { notificationId: Int ->
            getTimerStateFromService(context, notificationId)
        }

        OnStartObserving {
            Log.d(MODULE_TAG, "OnStartObserving called")
            if (FirebaseService.isFirebaseAvailable(context)) {
                TokenChangeHandler.sendEvent = this@ExpoLiveUpdatesModule::sendEvent
            }
        }

        OnNewIntent { intent ->
            if (isIntentSafe(intent)) {
                emitNotificationClickedEvent(intent)
            } else {
                Log.w(MODULE_TAG, "Rejected unsafe intent")
            }
        }
    }

    private fun isIntentSafe(intent: Intent): Boolean =
        intent.action == Intent.ACTION_VIEW && intent.`package` == context.packageName

    private fun emitNotificationClickedEvent(intent: Intent) {
        val (action, notificationId,mode) = getNotificationClickIntentExtra(intent)

        if (notificationId != null && action != null) {
            NotificationStateTriggredEventEmitter.emit(notificationId, action, mode)
        }
    }

    private val context
        get() = requireNotNull(appContext.reactContext)

    private fun initializeModule() {
        Log.d(MODULE_TAG, "initializeModule START")

        val channelId: String = getChannelId(context)
        val channelName: String = getChannelName(context)
        Log.d(MODULE_TAG, "Channel - ID: $channelId, Name: $channelName")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel =
                NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )

            val androidNotificationManager =
                getSystemService(context, NotificationManager::class.java)
            androidNotificationManager?.createNotificationChannel(serviceChannel)

            Log.d(MODULE_TAG, "Notification channel created")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val status =
                    if (androidNotificationManager?.canPostPromotedNotifications() == true) "✅ can"
                    else "❌ cannot"
                Log.i(MODULE_TAG, "$status post Live Updates")
            }
        }

        liveUpdatesManager = LiveUpdatesManager(context)
        NotificationStateTriggredEventEmitter.sendEvent = ::sendEvent

        Log.i(MODULE_TAG, "✅ ExpoLiveUpdatesModule initialized successfully")
        Log.i(MODULE_TAG, "✅ Context: ${context.packageName}")
        Log.i(MODULE_TAG, "✅ LiveStopWatchService class: ${LiveStopWatchService::class.java.name}")
    }
    private fun startTimerService(context: Context, state: LiveUpdateState, notificationId: Int,config: LiveUpdateConfig) {
        Log.d(MODULE_TAG, "")
        Log.d(MODULE_TAG, ">>> startTimerService START <<<")
        Log.d(MODULE_TAG, "Notification ID: $notificationId")
        Log.d(MODULE_TAG, "Context package: ${context.packageName}")

        val intent = Intent(context,modeClass ).apply {
            action = when (state.mode) {
                "stopwatch" -> LiveStopWatchService.ACTION_START
                "timer" -> LiveTimerService.ACTION_START
                "task" -> LiveTaskService.ACTION_START
                "tapin" -> LiveTapInService.ACTION_START
                else -> ""
            }

            Log.d(MODULE_TAG, "title: ${state.title}, mode: ${state.mode}")

            state.stopwatch?.let { stopwatch ->
                Log.d(MODULE_TAG, "Stopwatch data:")
                Log.d(MODULE_TAG, "  id: ${stopwatch.id}")
                Log.d(MODULE_TAG, "  isRunning: ${stopwatch.isRunning}")
                Log.d(MODULE_TAG, "  accumulated: ${stopwatch.accumulated}")
                Log.d(MODULE_TAG, "  lapCount: ${stopwatch.lapCount}")
                Log.d(MODULE_TAG, "  startedAt: ${stopwatch.startedAt}")

                val elapsedSeconds = runningStartSeconds(
                    stopwatch.startedAt,
                    stopwatch.accumulated
                )
                Log.d(MODULE_TAG, "Time Diffrenc: ${elapsedSeconds}")
                putExtra(LiveStopWatchService.EXTRA_IS_RUNNING, stopwatch.isRunning)
                putExtra(LiveStopWatchService.EXTRA_ACCUMULATED, elapsedSeconds)
                putExtra(LiveStopWatchService.EXTRA_LAP_COUNT, stopwatch.lapCount)
                putExtra(LiveStopWatchService.EXTRA_CONFIG,config)
                putExtra(LiveStopWatchService.EXTRA_TITLE, state.title)
                putExtra(LiveStopWatchService.EXTRA_SUBTITLE, state.subtitle)
                putExtra(LiveStopWatchService.EXTRA_NOTIFICATION_ID, notificationId)

                Log.d(MODULE_TAG, "✅ Stopwatch extras added")
            } ?: Log.w(MODULE_TAG, "⚠️ No stopwatch data")

            state.timer?.let { timer ->
                Log.d(MODULE_TAG, "Timer data:${timer}")
                Log.d(MODULE_TAG, "  isRunning: ${timer.isRunning}")
                Log.d(MODULE_TAG, "  duration: ${timer.duration}")
                putExtra(LiveTimerService.EXTRA_IS_RUNNING, timer.isRunning ?: true)
                putExtra(LiveTimerService.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(LiveTimerService.EXTRA_DURATION, timer.duration ?: 0)
                putExtra(LiveTimerService.EXTRA_REMAINING, timer.remaining ?: 0)
                putExtra(LiveTimerService.EXTRA_CONFIG,config)
                putExtra(LiveTimerService.EXTRA_TITLE, state.title)
                putExtra(LiveTimerService.EXTRA_SUBTITLE, state.subtitle)

                Log.d(MODULE_TAG, "✅ Timer extras added")
            }

            state.task?.let { task ->
                Log.d(MODULE_TAG, "Task data: ${task}")
                putExtra(LiveTaskService.EXTRA_START_DATE, task.startDate ?: System.currentTimeMillis())
                putExtra(LiveTaskService.EXTRA_TITLE, state.title)
                putExtra(LiveTaskService.EXTRA_SUBTITLE, state.subtitle)
                putExtra(LiveTaskService.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(LiveTaskService.EXTRA_CONFIG, config)

                Log.d(MODULE_TAG, "✅ Task extras added")
            }

            state.tapIn?.let { tapIn ->
                Log.d(MODULE_TAG, "TapIn data: ${tapIn}")
                putExtra(LiveTapInService.EXTRA_START_DATE, tapIn.startDate ?: System.currentTimeMillis())
                putExtra(LiveTapInService.EXTRA_TITLE, state.title)
                putExtra(LiveTapInService.EXTRA_SUBTITLE, state.subtitle)
                putExtra(LiveTapInService.EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(LiveTapInService.EXTRA_CONFIG, config)

                Log.d(MODULE_TAG, "✅ TapIn extras added")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
                Log.d(MODULE_TAG, "✅ startForegroundService called")
            } else {
                context.startService(intent)
                Log.d(MODULE_TAG, "✅ startService called")
            }

            Log.d(MODULE_TAG, "✅ Service start initiated for ID: $notificationId")
            Log.d(MODULE_TAG, ">>> startTimerService END <<<")
            Log.d(MODULE_TAG, "")
        } catch (e: Exception) {
            Log.e(MODULE_TAG, "❌ FATAL: Failed to start service!", e)
            e.printStackTrace()
            throw e
        }
    }

    fun runningStartSeconds(
        startedAtMillis: Long?,
        accumulatedSeconds: Long
    ): Long {
        if (startedAtMillis == null) return 0L
        val nowMillis = System.currentTimeMillis()
        return ((nowMillis - startedAtMillis ) / 1000)
            .coerceAtLeast(0)
    }

    private fun stopTimerService(context: Context, notificationId: Int) {
        Log.d(MODULE_TAG, "stopTimerService called for ID: $notificationId")

        val intent = Intent(context, modeClass).apply {
            action = LiveStopWatchService.ACTION_STOP
            putExtra(LiveStopWatchService.EXTRA_NOTIFICATION_ID, notificationId)
        }

        try {
            context.startService(intent)
            Log.d(MODULE_TAG, "✅ Stop request sent for ID: $notificationId")
        } catch (e: Exception) {
            Log.e(MODULE_TAG, "❌ Failed to stop timer $notificationId", e)
        }
    }

    // Update with full stopwatch state (including accumulated)
    private fun updateTimerState(context: Context, notificationId: Int, stopwatch: Stopwatch) {
        Log.d(MODULE_TAG, "updateTimerState - ID: $notificationId")
        Log.d(MODULE_TAG, "  isRunning: ${stopwatch.isRunning}")
        Log.d(MODULE_TAG, "  accumulated: ${stopwatch.accumulated}")
        Log.d(MODULE_TAG, "  lapCount: ${stopwatch.lapCount}")

        val action = when {
            stopwatch.isRunning && stopwatch.lapCount > 0-> {
                Log.d(MODULE_TAG, "Detected Lap")
                LiveStopWatchService.ACTION_LAP
            }
            stopwatch.accumulated == 0L && !stopwatch.isRunning -> {
                // Restart case: accumulated is 0, want to start fresh
                Log.d(MODULE_TAG, "Detected RESTART (accumulated=0, isRunning=true)")
                LiveStopWatchService.ACTION_RESTART
            }
            stopwatch.isRunning -> {
                Log.d(MODULE_TAG, "Detected RESUME")
                LiveStopWatchService.ACTION_RESUME
            }
            else -> {
                Log.d(MODULE_TAG, "Detected PAUSE")
                LiveStopWatchService.ACTION_PAUSE
            }
        }

        Log.d(MODULE_TAG, "Sending action: $action")

        val intent = Intent(context, LiveStopWatchService::class.java).apply {
            this.action = action
            putExtra(LiveStopWatchService.EXTRA_NOTIFICATION_ID, notificationId)
            // Pass the accumulated value for restart case
            if (action == LiveStopWatchService.ACTION_RESTART) {
                putExtra(LiveStopWatchService.EXTRA_ACCUMULATED, 0.0)
                putExtra(LiveStopWatchService.EXTRA_IS_RUNNING, true)
            }
        }

        try {
            context.startService(intent)
            Log.d(MODULE_TAG, "✅ Update request sent for ID: $notificationId")
        } catch (e: Exception) {
            Log.e(MODULE_TAG, "❌ Failed to update timer $notificationId", e)
        }
    }

    // Simple update (just pause/resume)
    private fun updateTimerStateSimple(context: Context, notificationId: Int, timer: Timer) {
        Log.d(MODULE_TAG, "updateTimerStateSimple - ID: $notificationId, isRunning: ${timer.isRunning}")


        val action = when {
            timer.isRunning!! && timer.duration!! > 0.00-> {
                LiveTimerService.ACTION_RESUME
            }
            !timer.isRunning!! -> {
                LiveTimerService.ACTION_PAUSE
            }
            else -> {
                ""
            }
        }

        Log.d(MODULE_TAG, "Sending action: $action")

        val intent = Intent(context, LiveTimerService::class.java).apply {
            this.action = action
            putExtra(LiveTimerService.EXTRA_NOTIFICATION_ID, notificationId)
        }

        try {
            context.startService(intent)
            Log.d(MODULE_TAG, "✅ Update request sent for ID: $notificationId")
        } catch (e: Exception) {
            Log.e(MODULE_TAG, "❌ Failed to update timer $notificationId", e)
        }
    }

    // Get current state from service (for syncing back to React Native)
    private fun getTimerStateFromService(context: Context, notificationId: Int): Map<String, Any>? {
        // This would require the service to expose its state
        // For now, return null - React Native should track state locally
        Log.d(MODULE_TAG, "getTimerState called for ID: $notificationId")
        return null
    }

    private fun getNotificationClickIntentExtra(intent: Intent): Triple<String?, Int?, String?> {
        val action = intent.action

        val notificationId =
            intent.getIntExtra(LiveStopWatchService.EXTRA_NOTIFICATION_ID, -1).takeIf { it != -1 }

        val mode = intent.getStringExtra("mode") ?: "stopwatch"

        return Triple(action, notificationId, mode)
    }
}