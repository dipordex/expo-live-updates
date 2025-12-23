package expo.modules.liveupdates

import android.app.NotificationChannel
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.concurrent.atomic.AtomicInteger

const val MODULE_TAG = "ExpoLiveUpdatesModule"

class ExpoLiveUpdatesModule : Module() {
    private lateinit var liveUpdatesManager: LiveUpdatesManager

    // Generate unique notification IDs
    private var notificationId : Int = 2022

    override fun definition() = ModuleDefinition {
        Name("ExpoLiveUpdatesModule")

        Events(
            LiveUpdatesModuleEvents.ON_NOTIFICATION_STATE_CHANGE,
            LiveUpdatesModuleEvents.ON_TOKEN_CHANGE,
        )

        OnCreate {
            Log.d(MODULE_TAG, "===== OnCreate called =====")
            initializeModule()
        }

        Function("startLiveUpdate") { state: LiveUpdateState, config: LiveUpdateConfig? ->
            Log.d(MODULE_TAG, "")
            Log.d(MODULE_TAG, "========================================")
            Log.d(MODULE_TAG, "startLiveUpdate CALLED")
            Log.d(MODULE_TAG, "========================================")
            Log.d(MODULE_TAG, "State - mode: $state")

            if (!context.checkPostNotificationPermission()) {
                Log.e(MODULE_TAG, "POST_NOTIFICATIONS permission NOT granted!")
                throw CodedException(
                    "${android.Manifest.permission.POST_NOTIFICATIONS} permission is not granted."
                )
            }
            Log.d(MODULE_TAG, "Permission check passed ✓")

            // For stopwatch/timer modes, use the foreground service
            when (state.mode) {
                "stopwatch", "timer" -> {
                    Log.d(MODULE_TAG, "Mode is stopwatch/timer - starting service")

                    try {
                        // Generate unique notification ID
                        state.stopwatch?.let {
                            notificationId = it.id.toInt()
                        }

                        Log.d(MODULE_TAG, "Generated notification ID: $notificationId")

                        startTimerService(context, state, notificationId)
                        Log.d(MODULE_TAG, "Service start completed!")
                        Log.d(MODULE_TAG, "Returning notification ID: $notificationId")

                        // Return the unique notification ID
                        notificationId
                    } catch (e: Exception) {
                        Log.e(MODULE_TAG, "FATAL: Failed to start service!", e)
                        throw e
                    }
                }
                else -> {
                    // For other types, use the manager
                    Log.d(MODULE_TAG, "Using LiveUpdatesManager for mode: ${state.mode}")
                    liveUpdatesManager.startLiveUpdateNotification(state, config)
                }
            }
        }

        Function("stopLiveUpdate") { notificationId: Int ->
            Log.d(MODULE_TAG, "")
            Log.d(MODULE_TAG, "stopLiveUpdate called for notification: $notificationId")

            // Stop the specific timer
            stopTimerService(context, notificationId)
        }

        Function("updateLiveUpdate") {
                notificationId: Int,
                state: LiveUpdateState,
                config: LiveUpdateConfig? ->
            if (!context.checkPostNotificationPermission()) {
                throw CodedException(
                    "${android.Manifest.permission.POST_NOTIFICATIONS} permission is not granted."
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
                "stopwatch", "timer" -> {
                    Log.d(MODULE_TAG, "Updating timer via service")

                    state.stopwatch?.let { stopwatch ->
                        Log.d(MODULE_TAG, "Stopwatch update - isRunning: ${stopwatch.isRunning}")
                        updateTimerState(context, notificationId, stopwatch.isRunning)
                    }

                    state.timer?.let { timer ->
                        Log.d(MODULE_TAG, "Timer update - isRunning: ${timer.isRunning}")
                        updateTimerState(context, notificationId, timer.isRunning ?: false)
                    }
                }
                else -> {
                    Log.d(MODULE_TAG, "Updating via manager")
                    liveUpdatesManager.updateLiveUpdateNotification(notificationId, state, config)
                }
            }
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
        val (action, notificationId) = getNotificationClickIntentExtra(intent)

        notificationId
            .takeIf { action == NotificationAction.CLICKED }
            ?.let { NotificationStateEventEmitter.emit(it, NotificationAction.CLICKED) }
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
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                )

            val androidNotificationManager =
                getSystemService(context, android.app.NotificationManager::class.java)
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
        NotificationStateEventEmitter.sendEvent = ::sendEvent

        Log.i(MODULE_TAG, "✅ ExpoLiveUpdatesModule initialized successfully")
        Log.i(MODULE_TAG, "✅ Context: ${context.packageName}")
        Log.i(MODULE_TAG, "✅ LiveStopWatchService class: ${LiveStopWatchService::class.java.name}")
    }
}

private fun startTimerService(context: Context, state: LiveUpdateState, notificationId: Int) {
    Log.d(MODULE_TAG, "")
    Log.d(MODULE_TAG, ">>> startTimerService START <<<")
    Log.d(MODULE_TAG, "Notification ID: $notificationId")
    Log.d(MODULE_TAG, "Context package: ${context.packageName}")

    val intent = Intent(context, LiveStopWatchService::class.java).apply {
        action = LiveStopWatchService.ACTION_START

        // CRITICAL: Pass the notification ID
        putExtra(LiveStopWatchService.EXTRA_NOTIFICATION_ID, notificationId)

        putExtra(LiveStopWatchService.EXTRA_TITLE, state.title)
        putExtra(LiveStopWatchService.EXTRA_MODE, state.mode)
        Log.d(MODULE_TAG, "Basic extras - ID: $notificationId, title: ${state.title}, mode: ${state.mode}")

        state.stopwatch?.let { stopwatch ->
            Log.d(MODULE_TAG, "Stopwatch data:")
            Log.d(MODULE_TAG, "  isRunning: ${stopwatch.isRunning}")
            Log.d(MODULE_TAG, "  accumulated: ${stopwatch.accumulated}")
            Log.d(MODULE_TAG, "  lapCount: ${stopwatch.lapCount}")

            putExtra(LiveStopWatchService.EXTRA_IS_RUNNING, stopwatch.isRunning)
            putExtra(LiveStopWatchService.EXTRA_ACCUMULATED, stopwatch.accumulated)
            putExtra(LiveStopWatchService.EXTRA_LAP_COUNT, stopwatch.lapCount)

            Log.d(MODULE_TAG, "✅ Stopwatch extras added")
        } ?: Log.w(MODULE_TAG, "⚠️ No stopwatch data")

        state.timer?.let { timer ->
            Log.d(MODULE_TAG, "Timer data:")
            Log.d(MODULE_TAG, "  isRunning: ${timer.isRunning}")
            Log.d(MODULE_TAG, "  duration: ${timer.duration}")

            putExtra(LiveStopWatchService.EXTRA_IS_RUNNING, timer.isRunning ?: true)
            putExtra(LiveStopWatchService.EXTRA_DURATION, timer.duration ?: 0.0)

            Log.d(MODULE_TAG, "✅ Timer extras added")
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

private fun stopTimerService(context: Context, notificationId: Int) {
    Log.d(MODULE_TAG, "stopTimerService called for ID: $notificationId")

    val intent = Intent(context, LiveStopWatchService::class.java).apply {
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

private fun updateTimerState(context: Context, notificationId: Int, isRunning: Boolean) {
    Log.d(MODULE_TAG, "updateTimerState - ID: $notificationId, isRunning: $isRunning")

    val action = if (isRunning) {
        LiveStopWatchService.ACTION_RESUME
    } else {
        LiveStopWatchService.ACTION_PAUSE
    }

    Log.d(MODULE_TAG, "Sending action: $action")

    val intent = Intent(context, LiveStopWatchService::class.java).apply {
        this.action = action
        putExtra(LiveStopWatchService.EXTRA_NOTIFICATION_ID, notificationId)
    }

    try {
        context.startService(intent)
        Log.d(MODULE_TAG, "✅ Update request sent for ID: $notificationId")
    } catch (e: Exception) {
        Log.e(MODULE_TAG, "❌ Failed to update timer $notificationId", e)
    }
}

private fun getNotificationClickIntentExtra(intent: Intent): Pair<NotificationAction?, Int?> {
    val action =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(
                NotificationActionExtra.NOTIFICATION_ACTION,
                NotificationAction::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(NotificationActionExtra.NOTIFICATION_ACTION)
                    as? NotificationAction
        }

    val notificationId =
        intent.getIntExtra(NotificationActionExtra.NOTIFICATION_ID, -1).takeIf { it != -1 }

    return action to notificationId
}